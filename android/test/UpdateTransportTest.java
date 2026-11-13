package io.github.shovelshit.apkstore;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

public final class UpdateTransportTest {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    static boolean rejects(Runnable action) { try { action.run(); return false; } catch (Exception expected) { return true; } }
    public static void main(String[] args) throws Exception {
        byte[] bytes = "verified payload".getBytes("UTF-8");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ok", exchange -> { exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
        server.createContext("/bad", exchange -> { exchange.sendResponseHeaders(500,0); exchange.close(); });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "http://127.0.0.1/"); exchange.sendResponseHeaders(302,-1); exchange.close(); });
        server.createContext("/large", exchange -> { byte[] large = new byte[65537]; exchange.sendResponseHeaders(200,0); exchange.getResponseBody().write(large); exchange.close(); });
        server.start();
        try {
            String base = "http://127.0.0.1:"+server.getAddress().getPort();
            Path file = Files.createTempFile("update-test", ".part");
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                StringBuilder hex = new StringBuilder(); for (byte b : digest) hex.append(String.format("%02x",b & 255));
                UpdateTransport.transfer(base+"/ok", file.toFile(), bytes.length, hex.toString(), n -> {}, true);
                check(Arrays.equals(bytes, Files.readAllBytes(file)));
                check(rejects(() -> { try { UpdateTransport.transfer(base+"/ok",file.toFile(),bytes.length+1,hex.toString(),n->{},true); } catch(Exception e) { throw new RuntimeException(e); } }));
                check(rejects(() -> { try { UpdateTransport.transfer(base+"/ok",file.toFile(),bytes.length,"0".repeat(64),n->{},true); } catch(Exception e) { throw new RuntimeException(e); } }));
                check(rejects(() -> { try { UpdateTransport.transfer(base+"/bad",file.toFile(),bytes.length,hex.toString(),n->{},true); } catch(Exception e) { throw new RuntimeException(e); } }));
                check(rejects(() -> { try { UpdateTransport.readIndex(base+"/redirect",true); } catch(Exception e) { throw new RuntimeException(e); } }));
                check(rejects(() -> { try { UpdateTransport.readIndex(base+"/large",true); } catch(Exception e) { throw new RuntimeException(e); } }));
                check(rejects(() -> { try { UpdateTransport.readIndex(base+"/ok",true); } catch(Exception e) { throw new RuntimeException(e); } }) == false);
            } finally { Files.deleteIfExists(file); }
        } finally { server.stop(0); }
    }
}
