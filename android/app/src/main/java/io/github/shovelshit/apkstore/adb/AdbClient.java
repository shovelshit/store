package io.github.shovelshit.apkstore.adb;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AdbClient implements AutoCloseable {
    public interface AuthListener { void onWaitingForAuthorization(); }
    public interface ProgressListener { void onProgress(long transferred, long total); }
    public static final class ShellResult {
        public final String output;
        public final int exitCode;
        public ShellResult(String output, int exitCode) { this.output = output; this.exitCode = exitCode; }
    }

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f;
    private static final int OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43;
    private static final int VERSION = 0x01000000, LIMIT = 2 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Timer DEADLINE_TIMER = new Timer("adb-deadlines", true);
    private final KeyPair identity;
    private volatile Socket socket;
    private volatile boolean closed;
    private InputStream input;
    private OutputStream output;
    private int maxData = 4096, nextId = 1, peerVersion = VERSION;
    private long deadline;
    private volatile long operationEpoch;
    private final Map<Integer, Integer> completedStreams = new LinkedHashMap<Integer, Integer>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Integer> entry) {
            return size() > 32;
        }
    };

    private static final class Packet {
        final int command, local, remote;
        final byte[] body;
        Packet(int command, int local, int remote, byte[] body) {
            this.command = command; this.local = local; this.remote = remote; this.body = body;
        }
    }
    private static final class Stream {
        final int local, remote;
        Stream(int local, int remote) { this.local = local; this.remote = remote; }
    }

    public AdbClient(KeyPair identity) { this.identity = identity; }

    public synchronized void connect(String host, int port, AuthListener listener) throws IOException {
        if (closed) throw new IOException("ADB connection closed");
        if (socket != null) throw new IOException("ADB connection already attempted");
        if (port <= 0 || port > 65535) throw new IOException("Invalid ADB port");
        deadline = System.nanoTime() + 300_000_000_000L;
        TimerTask watchdog = armDeadline(300000);
        Socket created = new Socket();
        socket = created;
        try {
            created.connect(new InetSocketAddress(host, port), 10000);
            input = created.getInputStream(); output = created.getOutputStream();
            send(CNXN, VERSION, 4096, "host::features=\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false, published = false;
            for (;;) {
                Packet p = receive();
                if (p.command == CNXN) {
                    if (p.local < VERSION || p.local > 0x01000001 || p.remote <= 0)
                        throw new IOException("Unsupported ADB protocol version or maxdata");
                    peerVersion = p.local;
                    maxData = Math.min(p.remote, LIMIT);
                    return;
                }
                if (p.command != AUTH || p.local != 1 || p.body.length != 20)
                    throw new IOException("Unexpected ADB handshake packet");
                if (!signed) {
                    send(AUTH, 2, 0, AdbIdentity.signToken(identity, p.body));
                    signed = true;
                } else if (!published) {
                    if (listener != null) listener.onWaitingForAuthorization();
                    byte[] key = (AdbIdentity.publicKeyPayload(identity) + "\0").getBytes(StandardCharsets.UTF_8);
                    send(AUTH, 3, 0, key);
                    published = true;
                } else {
                    // adbd may repeat tokens while the user authorization prompt is open.
                    if (closed) throw new IOException("ADB connection closed");
                }
            }
        } catch (IOException e) { close(); throw timedFailure(e, "ADB connect"); }
        catch (RuntimeException e) { close(); throw e; }
        finally { watchdog.cancel(); }
    }

    public synchronized ShellResult shell(String command, int timeoutMillis) throws IOException {
        ready();
        if (timeoutMillis <= 0) throw new IOException("Invalid shell timeout");
        deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        TimerTask watchdog = armDeadline(timeoutMillis);
        byte[] random = new byte[16]; RANDOM.nextBytes(random);
        StringBuilder hex = new StringBuilder();
        for (byte b : random) hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        String marker = "__ADB_EXIT_" + hex + "__";
        String shell = command + "; printf '\\n" + marker + ":%d\\n' $?";
        try {
            Stream stream = open("shell:" + shell);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            for (;;) {
                Packet p = receiveStream(stream);
                if (p.command == CLSE) {
                    completedStreams.put(stream.local, stream.remote);
                    String content = new String(result.toByteArray(), StandardCharsets.UTF_8);
                    Matcher match = Pattern.compile("(?s)\\n" + Pattern.quote(marker) + ":(-?\\d+)\\r?\\n$").matcher(content);
                    if (!match.find()) throw new IOException("ADB shell exit marker missing");
                    try {
                        int exit = Integer.parseInt(match.group(1));
                        String text = content.substring(0, match.start());
                        return new ShellResult(text, exit);
                    } catch (NumberFormatException e) { throw new IOException("Invalid ADB shell exit status", e); }
                }
                if (p.command != WRTE) throw new IOException("Unexpected ADB shell packet");
                if (result.size() + p.body.length > LIMIT) throw new IOException("ADB shell output exceeds 2 MiB");
                result.write(p.body, 0, p.body.length);
                send(OKAY, stream.local, stream.remote, new byte[0]);
            }
        } catch (IOException e) { close(); throw timedFailure(e, "ADB shell"); }
        finally { watchdog.cancel(); }
    }

    public synchronized void push(InputStream source, long length, String remotePath, ProgressListener listener) throws IOException {
        push(source, length, remotePath, listener, 600000);
    }

    public synchronized void push(InputStream source, long length, String remotePath, ProgressListener listener,
        int timeoutMillis) throws IOException {
        ready();
        if (timeoutMillis <= 0 || source == null || length < 0 || remotePath == null || remotePath.isEmpty() || remotePath.indexOf(',') >= 0 || remotePath.indexOf('\0') >= 0)
            throw new IOException("Invalid ADB sync source, length or remote path");
        deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        TimerTask watchdog = armDeadline(timeoutMillis);
        try {
            Stream stream = open("sync:");
            writeSync(stream, "SEND", (remotePath + ",33206").getBytes(StandardCharsets.UTF_8));
            long transferred = 0;
            byte[] chunk = new byte[Math.min(64 * 1024, Math.max(1, maxData - 8))];
            if (listener != null) listener.onProgress(0, length);
            while (transferred < length) {
                int count = source.read(chunk, 0, (int)Math.min(chunk.length, length - transferred));
                if (count < 0) throw new EOFException("ADB push source truncated");
                if (count == 0) throw new IOException("ADB push source returned no data");
                writeSync(stream, "DATA", Arrays.copyOf(chunk, count));
                transferred += count;
                if (listener != null) listener.onProgress(transferred, length);
            }
            writeSync(stream, "DONE", null);
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            while (response.size() < 8 || response.size() < 8 + syncLength(response.toByteArray())) {
                Packet p = receiveStream(stream);
                if (p.command != WRTE) throw new IOException("ADB sync response truncated");
                if (response.size() + p.body.length > LIMIT) throw new IOException("ADB sync response too long");
                response.write(p.body);
                send(OKAY, stream.local, stream.remote, new byte[0]);
            }
            byte[] status = response.toByteArray();
            String code = new String(status, 0, 4, StandardCharsets.US_ASCII);
            int n = syncLength(status);
            if (code.equals("FAIL")) throw new IOException("ADB sync FAIL: " + new String(status, 8, n, StandardCharsets.UTF_8));
            if (!code.equals("OKAY") || n != 0 || status.length != 8) throw new IOException("Invalid ADB sync status");
            send(CLSE, stream.local, stream.remote, new byte[0]);
            Packet close = receiveStream(stream);
            if (close.command != CLSE || close.body.length != 0)
                throw new IOException("ADB sync stream close missing");
            completedStreams.put(stream.local, stream.remote);
        } catch (IOException e) { close(); throw timedFailure(e, "ADB push"); }
        finally { watchdog.cancel(); }
    }

    private TimerTask armDeadline(int timeoutMillis) {
        final long epoch = ++operationEpoch;
        TimerTask task = new TimerTask() {
            @Override public void run() {
                if (!closed && operationEpoch == epoch) close();
            }
        };
        DEADLINE_TIMER.schedule(task, timeoutMillis);
        return task;
    }

    private IOException timedFailure(IOException cause, String operation) {
        if (System.nanoTime() < deadline) return cause;
        SocketTimeoutException timeout = new SocketTimeoutException(operation + " deadline exceeded");
        timeout.initCause(cause);
        return timeout;
    }

    private int syncLength(byte[] response) throws IOException {
        if (response.length < 8) return 0;
        int n = getInt(response, 4);
        if (n < 0 || n > LIMIT) throw new IOException("Invalid ADB sync response length");
        return n;
    }

    private void writeSync(Stream stream, String command, byte[] data) throws IOException {
        int size = data == null ? 0 : data.length;
        byte[] frame = new byte[8 + size];
        byte[] code = command.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(code, 0, frame, 0, 4);
        putInt(frame, 4, size);
        if (data != null) System.arraycopy(data, 0, frame, 8, size);
        int offset = 0;
        while (offset < frame.length) {
            int n = Math.min(maxData, frame.length - offset);
            send(WRTE, stream.local, stream.remote, Arrays.copyOfRange(frame, offset, offset + n));
            Packet ack = receiveStream(stream);
            if (ack.command != OKAY) throw new IOException("ADB sync write not acknowledged");
            offset += n;
        }
    }

    private Stream open(String destination) throws IOException {
        byte[] target = (destination + "\0").getBytes(StandardCharsets.UTF_8);
        if (target.length > maxData) throw new IOException("ADB service command exceeds negotiated maxdata");
        int local = nextId++;
        send(OPEN, local, 0, target);
        Packet p = receiveCurrent();
        if (p.command == CLSE && p.remote == local) throw new IOException("ADB service rejected: " + destination);
        if (p.command != OKAY || p.remote != local || p.local == 0)
            throw new IOException("ADB stream open ID mismatch: command=" + Integer.toHexString(p.command)
                + " peer=" + p.local + " local=" + p.remote + " expectedLocal=" + local);
        return new Stream(local, p.local);
    }

    private Packet receiveStream(Stream s) throws IOException {
        Packet p = receiveCurrent();
        if (p.remote != s.local || p.local != s.remote) throw new IOException("ADB stream ID mismatch");
        return p;
    }

    private Packet receiveCurrent() throws IOException {
        for (int skipped = 0; skipped <= 32; skipped++) {
            Packet p = receive();
            if (p.command == CLSE && p.body.length == 0 && completedStreams.containsKey(p.remote)
                && completedStreams.get(p.remote) == p.local) continue;
            return p;
        }
        throw new IOException("Too many delayed ADB stream closes");
    }

    private void ready() throws IOException {
        if (closed || socket == null || !socket.isConnected() || input == null || maxData <= 0)
            throw new IOException("ADB connection unavailable");
    }

    private void send(int cmd, int a, int b, byte[] body) throws IOException {
        if (closed) throw new IOException("ADB connection closed");
        if (body.length > LIMIT) throw new IOException("ADB payload too large");
        byte[] header = new byte[24];
        putInt(header, 0, cmd); putInt(header, 4, a); putInt(header, 8, b);
        putInt(header, 12, body.length);
        int checksum = 0; for (byte value : body) checksum += value & 255;
        putInt(header, 16, checksum); putInt(header, 20, cmd ^ -1);
        output.write(header); output.write(body); output.flush();
    }

    private Packet receive() throws IOException {
        byte[] header = read(24);
        int cmd = getInt(header, 0), n = getInt(header, 12);
        if (getInt(header, 20) != (cmd ^ -1)) throw new IOException("Invalid ADB header magic");
        if (n < 0 || n > LIMIT || (cmd != CNXN && cmd != AUTH && n > maxData)) throw new IOException("Invalid ADB payload length");
        byte[] body = read(n);
        int sum = 0; for (byte value : body) sum += value & 255;
        if ((peerVersion < 0x01000001 && (cmd != CNXN || getInt(header, 4) < 0x01000001))
            && sum != getInt(header, 16)) throw new IOException("Invalid ADB payload checksum");
        return new Packet(cmd, getInt(header, 4), getInt(header, 8), body);
    }

    private byte[] read(int n) throws IOException {
        byte[] bytes = new byte[n];
        for (int pos = 0; pos < n;) {
            if (closed) throw new IOException("ADB connection closed");
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) throw new SocketTimeoutException("ADB operation deadline exceeded");
            socket.setSoTimeout((int)Math.min(remaining, 1000));
            try {
                int count = input.read(bytes, pos, n - pos);
                if (count < 0) throw new EOFException("ADB connection truncated");
                pos += count;
            } catch (SocketTimeoutException e) {
                if (System.nanoTime() >= deadline) throw new SocketTimeoutException("ADB operation deadline exceeded");
            }
        }
        return bytes;
    }

    private static int getInt(byte[] b, int offset) {
        return (b[offset] & 255) | ((b[offset + 1] & 255) << 8) |
            ((b[offset + 2] & 255) << 16) | ((b[offset + 3] & 255) << 24);
    }
    private static void putInt(byte[] b, int offset, int n) {
        for (int i = 0; i < 4; i++) b[offset + i] = (byte)(n >>> (8 * i));
    }

    @Override public void close() {
        closed = true;
        Socket current = socket;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }
}
