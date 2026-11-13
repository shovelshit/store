package io.github.shovelshit.apkstore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.LongConsumer;

final class UpdateTransport {
    private static final int TIMEOUT = 15000;
    private static final int MAX_INDEX = 65536;
    private static HttpURLConnection open(String address, boolean localTest, long deadline) throws IOException {
        for (int hop = 0; hop < 4; hop++) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) throw new IOException("更新请求超时");
            URL url = new URL(address);
            if (!"https".equalsIgnoreCase(url.getProtocol()) && !(localTest && "http".equalsIgnoreCase(url.getProtocol())))
                throw new IOException("更新地址必须使用 HTTPS");
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false);
            int timeout = (int)Math.max(1,Math.min(remaining,TIMEOUT));
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
            connection.setUseCaches(false);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String next = connection.getHeaderField("Location");
                connection.disconnect();
                if (next == null) throw new IOException("无效的更新重定向");
                URL resolved = new URL(url, next);
                if (!"https".equalsIgnoreCase(resolved.getProtocol())) throw new IOException("不安全的更新重定向");
                address = resolved.toString(); continue;
            }
            if (status != 200) { connection.disconnect(); throw new IOException("更新请求失败: HTTP " + status); }
            return connection;
        }
        throw new IOException("更新重定向过多");
    }
    static byte[] readIndex(String url, boolean localTest) throws IOException {
        long deadline = System.nanoTime() + 15_000_000_000L;
        HttpURLConnection c = open(url,localTest,deadline);
        try (InputStream input = c.getInputStream(); java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            if (c.getContentLengthLong() > MAX_INDEX) throw new IOException("更新索引过大");
            byte[] buffer = new byte[8192]; int n;
            while (true) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0) throw new IOException("更新索引读取超时");
                c.setReadTimeout((int)Math.max(1,Math.min(remaining,TIMEOUT)));
                n = input.read(buffer);
                if (n == -1) break;
                if (output.size() + n > MAX_INDEX) throw new IOException("更新索引过大");
                output.write(buffer,0,n);
            }
            return output.toByteArray();
        } finally { c.disconnect(); }
    }
    static void transfer(String url, File part, long size, String hash, LongConsumer progress, boolean localTest) throws IOException {
        if (size <= 0 || size > UpdatePolicy.MAX_APK) throw new IOException("安装包大小无效");
        HttpURLConnection c = open(url,localTest,System.nanoTime() + 15_000_000_000L);
        try {
            if (c.getContentLengthLong() > size) throw new IOException("安装包大小不匹配");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IOException(e); }
            long total = 0; byte[] buffer = new byte[65536]; int n;
            try (InputStream input = c.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
                while ((n = input.read(buffer)) != -1) {
                    total += n;
                    if (total > size) throw new IOException("安装包超出预期大小");
                    digest.update(buffer,0,n); output.write(buffer,0,n); progress.accept(total);
                }
                output.getFD().sync();
            }
            StringBuilder actual = new StringBuilder();
            for (byte b : digest.digest()) actual.append(String.format(Locale.ROOT,"%02x",b & 255));
            if (total != size || !actual.toString().equalsIgnoreCase(hash)) throw new IOException("安装包大小或 SHA-256 校验失败");
        } finally { c.disconnect(); }
    }
}
