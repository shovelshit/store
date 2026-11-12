import io.github.shovelshit.apkstore.adb.AdbClient;
import io.github.shovelshit.apkstore.adb.AdbIdentity;
import java.io.*;
import java.net.*;
import java.math.BigInteger;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Cipher;

public final class AdbClientTest {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    static final int VERSION = 0x01000000;
    static final class Packet {
        final int cmd, arg0, arg1;
        final byte[] payload;
        Packet(int cmd, int arg0, int arg1, byte[] payload) {
            this.cmd = cmd; this.arg0 = arg0; this.arg1 = arg1; this.payload = payload;
        }
    }
    interface Conversation { void run(InputStream in, OutputStream out) throws Exception; }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static int number(byte[] b, int offset) {
        return (b[offset] & 255) | (b[offset + 1] & 255) << 8 |
               (b[offset + 2] & 255) << 16 | (b[offset + 3] & 255) << 24;
    }
    static void put(byte[] b, int offset, int n) {
        for (int i = 0; i < 4; i++) b[offset + i] = (byte)(n >>> (8 * i));
    }
    static int checksum(byte[] b) {
        int sum = 0; for (byte v : b) sum += v & 255; return sum;
    }
    static byte[] packet(int cmd, int a, int b, byte[] body) {
        byte[] wire = new byte[24 + body.length];
        put(wire, 0, cmd); put(wire, 4, a); put(wire, 8, b);
        put(wire, 12, body.length); put(wire, 16, checksum(body)); put(wire, 20, cmd ^ -1);
        System.arraycopy(body, 0, wire, 24, body.length);
        return wire;
    }
    static void send(OutputStream out, int cmd, int a, int b, String body) throws IOException {
        out.write(packet(cmd, a, b, bytes(body))); out.flush();
    }
    static byte[] read(InputStream in, int length) throws IOException {
        byte[] result = new byte[length];
        for (int n = 0; n < length;) {
            int r = in.read(result, n, length - n);
            if (r < 0) throw new EOFException("truncated fixture");
            n += r;
        }
        return result;
    }
    static Packet receive(InputStream in) throws IOException {
        byte[] header = read(in, 24);
        int length = number(header, 12);
        if (length < 0 || length > 2 * 1024 * 1024) throw new IOException("bad fixture length");
        return new Packet(number(header, 0), number(header, 4), number(header, 8), read(in, length));
    }
    static void check(boolean yes, String message) {
        if (!yes) throw new AssertionError(message);
    }
    interface Checked { void run() throws Exception; }
    static void fails(Checked task, String expected) throws Exception {
        try { task.run(); throw new AssertionError("Expected " + expected); }
        catch (IOException e) { check(e.getMessage().toLowerCase().contains(expected.toLowerCase()), "error: " + e); }
    }
    static void fixture(Conversation server, Conversation client) throws Exception {
        try (ServerSocket listener = new ServerSocket(0)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> future = executor.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    server.run(socket.getInputStream(), socket.getOutputStream());
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            try { client.run(null, new PortOutput(listener.getLocalPort())); future.get(6, TimeUnit.SECONDS); }
            finally { executor.shutdownNow(); }
        }
    }
    static final class PortOutput extends OutputStream {
        final int port;
        PortOutput(int port) { this.port = port; }
        public void write(int b) { throw new UnsupportedOperationException(); }
    }
    static int port(OutputStream out) { return ((PortOutput) out).port; }
    static KeyPair key() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA"); gen.initialize(2048); return gen.generateKeyPair();
    }
    static void handshake(InputStream in, OutputStream out) throws Exception {
        check(receive(in).cmd == CNXN, "client CNXN");
        byte[] wire = packet(CNXN, VERSION, 4096, bytes("device::\0"));
        for (byte b : wire) { out.write(b); out.flush(); }
    }
    static void shellFixture(InputStream in, OutputStream out, int exit) throws Exception {
        handshake(in, out);
        shellExchange(in, out, exit);
    }
    static Packet shellExchange(InputStream in, OutputStream out, int exit) throws Exception {
        return shellExchange(in, out, exit, null);
    }
    static Packet shellExchange(InputStream in, OutputStream out, int exit, Packet stale) throws Exception {
        Packet open = receive(in);
        check(open.cmd == OPEN && new String(open.payload, StandardCharsets.UTF_8).startsWith("shell:"), "shell OPEN");
        int id = open.arg0;
        send(out, OKAY, 7, id, "");
        if (stale != null) send(out, CLSE, 7, stale.arg0, "");
        String command = new String(open.payload, StandardCharsets.UTF_8);
        int marker = command.indexOf("__ADB_EXIT_");
        check(marker >= 0, "exit marker command");
        int markerEnd = command.indexOf("__", marker + 11);
        String token = command.substring(marker, markerEnd + 2);
        send(out, WRTE, 7, id, "uid=2000(shell)\n\n" + token + ":" + exit + "\n");
        check(receive(in).cmd == OKAY, "ack shell data");
        send(out, CLSE, 7, id, "");
        return open;
    }
    public static void main(String[] args) throws Exception {
        KeyPair identity = key();
        File dir = Files.createTempDirectory("adb-identity-").toFile();
        KeyPair stored = AdbIdentity.loadOrCreate(dir);
        check(Arrays.equals(stored.getPrivate().getEncoded(), AdbIdentity.loadOrCreate(dir).getPrivate().getEncoded()), "persistent identity");
        byte[] token = new byte[20]; Arrays.fill(token, (byte) 0x42);
        byte[] signature = AdbIdentity.signToken(identity, token);
        Cipher verifier = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        verifier.init(Cipher.DECRYPT_MODE, identity.getPublic());
        byte[] decoded = verifier.doFinal(signature);
        // Raw ADB AUTH tokens are already SHA-1 hashes, so RSA signs the SHA-1 DigestInfo.
        byte[] digestPrefix = { 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0, 0x04, 0x14 };
        check(decoded.length == 35 && Arrays.equals(Arrays.copyOfRange(decoded, 0, 15), digestPrefix)
            && Arrays.equals(Arrays.copyOfRange(decoded, 15, 35), token), "AUTH signature DigestInfo");
        byte[] publicKey = Base64.getDecoder().decode(AdbIdentity.publicKeyPayload(identity).split(" ")[0]);
        check(publicKey.length == 524 && number(publicKey, 0) == 64 && number(publicKey, 520) == 65537, "Android public key layout");
        byte[] modulus = ((RSAPublicKey) identity.getPublic()).getModulus().toByteArray();
        for (int i = 0; i < 256; i++) check(publicKey[8 + i] == modulus[modulus.length - 1 - i], "little endian modulus");
        check((number(publicKey, 4) * number(publicKey, 8)) == -1, "Montgomery n0 inverse");
        byte[] rr = new byte[257];
        for (int i = 0; i < 256; i++) rr[256 - i] = publicKey[264 + i];
        check(new BigInteger(rr).equals(BigInteger.ONE.shiftLeft(4096).mod(((RSAPublicKey)identity.getPublic()).getModulus())), "Montgomery R squared");

        fixture((in, out) -> shellFixture(in, out, 0), (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                AdbClient.ShellResult result = client.shell("id", 2000);
                check(result.exitCode == 0 && result.output.equals("uid=2000(shell)\n"), "shell success");
            }
        });
        fixture((in, out) -> shellFixture(in, out, 42), (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                check(client.shell("false", 2000).exitCode == 42, "shell error status");
            }
        });
        fixture((in, out) -> {
            handshake(in, out);
            Packet first = shellExchange(in, out, 0);
            send(out, CLSE, 7, first.arg0, ""); // Actual adbd repeats this close before the next stream opens.
            shellExchange(in, out, 0, first);
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                check(client.shell("id", 2000).exitCode == 0, "first shell");
                check(client.shell("id", 2000).output.equals("uid=2000(shell)\n"), "second shell on same client");
            }
        });
        fixture((in, out) -> {
            handshake(in, out);
            Packet first = shellExchange(in, out, 0);
            receive(in); // Second OPEN.
            send(out, CLSE, 99, first.arg0, ""); // Same local ID but unknown peer ID is not stale.
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                client.shell("id", 2000);
                fails(() -> client.shell("id", 2000), "ID mismatch");
            }
        });
        fixture((in, out) -> {
            check(receive(in).cmd == CNXN, "auth CNXN");
            send(out, AUTH, 1, 0, new String(token, StandardCharsets.UTF_8));
            Packet signed = receive(in);
            check(signed.cmd == AUTH && signed.arg0 == 2 && Arrays.equals(signed.payload, AdbIdentity.signToken(identity, token)), "signed token");
            send(out, AUTH, 1, 0, new String(token, StandardCharsets.UTF_8));
            Packet pub = receive(in);
            check(pub.cmd == AUTH && pub.arg0 == 3 && pub.payload[pub.payload.length - 1] == 0, "public key auth");
            send(out, CNXN, VERSION, 4096, "device::\0");
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                int[] waiting = {0};
                client.connect("127.0.0.1", port(out), () -> waiting[0]++);
                check(waiting[0] == 1, "authorization callback");
            }
        });
        byte[] upload = new byte[10239];
        for (int i = 0; i < upload.length; i++) upload[i] = (byte)(i * 13);
        fixture((in, out) -> {
            handshake(in, out);
            Packet open = receive(in);
            check(open.cmd == OPEN && Arrays.equals(open.payload, bytes("sync:\0")), "sync OPEN");
            int id = open.arg0;
            send(out, OKAY, 8, id, "");
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            while (frames.size() < 8 || !Arrays.equals(Arrays.copyOfRange(frames.toByteArray(), frames.size() - 8, frames.size() - 4), bytes("DONE"))) {
                Packet p = receive(in);
                check(p.cmd == WRTE && p.arg0 == id && p.arg1 == 8 && p.payload.length <= 4096, "sync WRTE maxdata and IDs");
                frames.write(p.payload);
                send(out, OKAY, 8, id, "");
                byte[] collected = frames.toByteArray();
                if (collected.length >= 8 && Arrays.equals(Arrays.copyOfRange(collected, collected.length - 8, collected.length - 4), bytes("DONE"))) break;
            }
            byte[] bytes = frames.toByteArray();
            int cursor = 0, copied = 0;
            check(Arrays.equals(Arrays.copyOfRange(bytes, cursor, cursor + 4), bytes("SEND")), "SEND");
            int nameLength = number(bytes, cursor + 4); cursor += 8;
            check(new String(bytes, cursor, nameLength, StandardCharsets.UTF_8).equals("/data/local/tmp/a.apk,33206"), "path and mode");
            cursor += nameLength;
            while (Arrays.equals(Arrays.copyOfRange(bytes, cursor, cursor + 4), bytes("DATA"))) {
                int n = number(bytes, cursor + 4); cursor += 8;
                check(Arrays.equals(Arrays.copyOfRange(bytes, cursor, cursor + n), Arrays.copyOfRange(upload, copied, copied + n)), "exact uploaded content");
                cursor += n; copied += n;
            }
            check(copied == upload.length && Arrays.equals(Arrays.copyOfRange(bytes, cursor, cursor + 4), bytes("DONE")), "DONE after exact length");
            check(number(bytes, cursor + 4) == 0 && cursor + 8 == bytes.length, "DONE timestamp field");
            byte[] status = { 'O', 'K', 'A', 'Y', 0, 0, 0, 0 };
            byte[] response = packet(WRTE, 8, id, status);
            out.write(response, 0, 27); out.flush();
            out.write(response, 27, response.length - 27); out.flush();
            check(receive(in).cmd == OKAY, "sync response ack");
            Packet localClose = receive(in);
            check(localClose.cmd == CLSE && localClose.arg0 == id && localClose.arg1 == 8, "sync local CLOSE");
            send(out, CLSE, 8, id, "");
            shellExchange(in, out, 0);
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                long[] progress = {-1};
                ByteArrayInputStream source = new ByteArrayInputStream(upload);
                client.push(source, upload.length, "/data/local/tmp/a.apk", (done, total) -> {
                    check(total == upload.length && done >= progress[0], "monotonic progress"); progress[0] = done;
                });
                check(progress[0] == upload.length && source.available() == 0, "complete transfer");
                check(client.shell("id", 2000).exitCode == 0, "shell after push on same client");
            }
        });
        fixture((in, out) -> {
            handshake(in, out);
            Packet open = receive(in); send(out, OKAY, 9, open.arg0, "");
            // Reject the upload immediately after SEND, without consuming the source.
            Packet send = receive(in); check(send.cmd == WRTE, "sync SEND sent");
            send(out, OKAY, 9, open.arg0, "");
            Packet data = receive(in); check(data.cmd == WRTE, "sync DATA sent");
            send(out, OKAY, 9, open.arg0, "");
            Packet done = receive(in); check(done.cmd == WRTE, "sync DONE sent");
            send(out, OKAY, 9, open.arg0, "");
            send(out, WRTE, 9, open.arg0, "FAIL\u000b\u0000\u0000\u0000permission!");
            check(receive(in).cmd == OKAY, "FAIL acknowledged");
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                fails(() -> client.push(new ByteArrayInputStream(bytes("x")), 1, "/x", null), "permission!");
            }
        });
        for (String fault : new String[] { "magic", "length", "checksum", "truncated" }) {
            fixture((in, out) -> {
                receive(in);
                byte[] malformed = packet(CNXN, VERSION, 4096, bytes("device::\0"));
                if (fault.equals("magic")) malformed[20] ^= 1;
                if (fault.equals("length")) put(malformed, 12, 2 * 1024 * 1024 + 1);
                if (fault.equals("checksum")) malformed[16] ^= 1;
                if (fault.equals("truncated")) malformed = Arrays.copyOf(malformed, 25);
                out.write(malformed); out.flush();
            }, (in, out) -> {
                try (AdbClient client = new AdbClient(identity)) {
                    fails(() -> client.connect("127.0.0.1", port(out), () -> {}), fault.equals("truncated") ? "truncated" : fault);
                }
            });
        }
        fixture((in, out) -> {
            handshake(in, out);
            Packet open = receive(in);
            send(out, OKAY, 10, open.arg0 + 1, "");
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                fails(() -> client.shell("id", 1000), "ID mismatch");
            }
        });
        fixture((in, out) -> {
            handshake(in, out);
            Packet open = receive(in);
            send(out, OKAY, 11, open.arg0, "");
            send(out, WRTE, 11, open.arg0, "ordinary output\n");
            check(receive(in).cmd == OKAY, "shell chunk ack");
            send(out, CLSE, 11, open.arg0, "");
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                fails(() -> client.shell("id", 1000), "marker missing");
            }
        });
        fixture((in, out) -> {
            handshake(in, out);
            Packet open = receive(in);
            send(out, OKAY, 12, open.arg0, "");
            byte[] partial = packet(WRTE, 12, open.arg0, bytes("unfinished"));
            out.write(partial, 0, partial.length - 4); out.flush();
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                fails(() -> client.shell("id", 1000), "truncated");
            }
        });
        fixture((in, out) -> {
            handshake(in, out);
            receive(in);
            Thread.sleep(500);
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                fails(() -> client.shell("id", 100), "deadline");
            }
        });
        CountDownLatch largeDataStarted = new CountDownLatch(1);
        AtomicBoolean observedBlockedWrite = new AtomicBoolean();
        fixture((in, out) -> {
            check(receive(in).cmd == CNXN, "slow-peer CNXN");
            send(out, CNXN, VERSION, 262144, "device::\0");
            Packet open = receive(in); send(out, OKAY, 13, open.arg0, "");
            check(receive(in).cmd == WRTE, "slow-peer SEND");
            send(out, OKAY, 13, open.arg0, "");
            byte[] header = read(in, 24);
            check(number(header, 0) == WRTE && number(header, 12) > 60000, "large DATA write started");
            largeDataStarted.countDown();
            Thread.sleep(500); // Do not consume DATA payload; a small send buffer must fill.
        }, (in, out) -> {
            try (AdbClient client = new AdbClient(identity)) {
                client.connect("127.0.0.1", port(out), () -> {});
                Field socketField = AdbClient.class.getDeclaredField("socket");
                socketField.setAccessible(true);
                ((Socket) socketField.get(client)).setSendBufferSize(1024);
                InputStream repeated = new InputStream() {
                    public int read() { return 7; }
                    public int read(byte[] buffer, int offset, int size) {
                        Arrays.fill(buffer, offset, offset + size, (byte) 7);
                        return size;
                    }
                };
                Thread uploader = Thread.currentThread();
                Thread inspector = new Thread(() -> {
                    try {
                        if (!largeDataStarted.await(1, TimeUnit.SECONDS)) return;
                        for (int i = 0; i < 50; i++) {
                            boolean writing = false;
                            for (StackTraceElement frame : uploader.getStackTrace()) {
                                if (frame.getClassName().contains("Socket") && frame.getMethodName().toLowerCase().contains("write"))
                                    writing = true;
                            }
                            if (writing) {
                                Thread.sleep(20);
                                for (StackTraceElement frame : uploader.getStackTrace()) {
                                    if (frame.getClassName().contains("Socket") && frame.getMethodName().toLowerCase().contains("write"))
                                        observedBlockedWrite.set(true);
                                }
                                if (observedBlockedWrite.get()) return;
                            }
                            Thread.sleep(2);
                        }
                    } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                });
                inspector.start();
                long start = System.nanoTime();
                fails(() -> client.push(repeated, 100_000_000, "/tmp/slow.apk", null, 200), "deadline");
                inspector.join();
                check(observedBlockedWrite.get(), "client was blocked in socket write before deadline");
                check((System.nanoTime() - start) < 2_000_000_000L, "blocked write canceled promptly");
            }
        });
        fixture((in, out) -> {
            receive(in);
            Thread.sleep(200);
        }, (in, out) -> {
            AdbClient client = new AdbClient(identity);
            Thread canceller = new Thread(() -> {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { }
                client.close(); client.close();
            });
            canceller.start();
            fails(() -> client.connect("127.0.0.1", port(out), () -> {}), "closed");
            canceller.join();
        });
        System.out.println("ADB JVM protocol tests passed");
    }
}
