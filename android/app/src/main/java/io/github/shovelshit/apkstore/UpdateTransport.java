package io.github.shovelshit.apkstore;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

final class UpdateTransport {
    private static final int MAX_INDEX = 65536;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15,TimeUnit.SECONDS)
            .readTimeout(15,TimeUnit.SECONDS)
            .build();

    private static Response open(String address, boolean localTest, long deadlineNanos) throws IOException {
        for (int hop = 0; hop < 4; hop++) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new IOException("更新请求超时");
            HttpUrl url = HttpUrl.parse(address);
            if (url == null || (!url.isHttps() && !(localTest && "http".equals(url.scheme())))
                    || !url.username().isEmpty() || !url.password().isEmpty()) throw new IOException("更新地址必须使用 HTTPS");
            Request request = new Request.Builder().url(url).get().build();
            Call call = CLIENT.newCall(request);
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Response> value = new AtomicReference<>();
            AtomicReference<IOException> error = new AtomicReference<>();
            AtomicBoolean timedOut = new AtomicBoolean(false);
            call.enqueue(new Callback() {
                @Override public void onFailure(Call ignored, IOException failure) { error.set(failure); done.countDown(); }
                @Override public void onResponse(Call ignored, Response response) {
                    if (timedOut.get()) response.close(); else value.set(response);
                    done.countDown();
                }
            });
            try {
                if (!done.await(remaining,TimeUnit.NANOSECONDS)) {
                    timedOut.set(true);
                    Response late = value.getAndSet(null);
                    if (late != null) late.close();
                    call.cancel();
                    throw new IOException("更新请求超时");
                }
            } catch (InterruptedException e) { timedOut.set(true); call.cancel(); Thread.currentThread().interrupt(); throw new IOException("更新请求中断",e); }
            if (error.get() != null) throw error.get();
            Response response = value.get();
            if (response == null) throw new IOException("更新响应为空");
            if (response.code() >= 300 && response.code() < 400) {
                String location = response.header("Location");
                HttpUrl next = location == null ? null : url.resolve(location);
                response.close();
                if (next == null || !next.isHttps() || !next.username().isEmpty() || !next.password().isEmpty())
                    throw new IOException("不安全的更新重定向");
                address = next.toString(); continue;
            }
            if (response.code() != 200) { int code = response.code(); response.close(); throw new IOException("更新请求失败: HTTP " + code); }
            return response;
        }
        throw new IOException("更新重定向过多");
    }

    static byte[] readIndex(String url, boolean localTest) throws IOException { return readIndex(url,localTest,15000); }
    static byte[] readIndex(String url, boolean localTest, int timeoutMillis) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try (Response response = open(url,localTest,deadline)) {
            if (response.body() == null || response.body().contentLength() > MAX_INDEX) throw new IOException("更新索引过大");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (InputStream input = response.body().byteStream()) {
                byte[] buffer = new byte[8192]; int n;
                while (true) {
                    if (System.nanoTime() >= deadline) throw new IOException("更新索引读取超时");
                    int remaining = (int)Math.max(1,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()));
                    response.body().source().timeout().timeout(remaining,TimeUnit.MILLISECONDS);
                    n = input.read(buffer);
                    if (n == -1) break;
                    if (System.nanoTime() >= deadline) throw new IOException("更新索引读取超时");
                    if (output.size() + n > MAX_INDEX) throw new IOException("更新索引过大");
                    output.write(buffer,0,n);
                }
            }
            return output.toByteArray();
        }
    }

    static void transfer(String url, File part, long size, String hash, LongConsumer progress, boolean localTest) throws IOException {
        if (size <= 0 || size > UpdatePolicy.MAX_APK) throw new IOException("安装包大小无效");
        try (Response response = open(url,localTest,System.nanoTime()+TimeUnit.SECONDS.toNanos(15))) {
            if (response.body() == null || response.body().contentLength() > size) throw new IOException("安装包大小不匹配");
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException e) { throw new IOException(e); }
            long total = 0; byte[] buffer = new byte[65536]; int n;
            try (InputStream input = response.body().byteStream(); FileOutputStream output = new FileOutputStream(part)) {
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
        }
    }
}
