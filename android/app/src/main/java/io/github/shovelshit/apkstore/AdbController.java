package io.github.shovelshit.apkstore;

import io.github.shovelshit.apkstore.adb.AdbClient;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One active target and one serialized worker; invalidation is synchronous. */
public final class AdbController implements AutoCloseable {
    public interface Session extends AutoCloseable {
        void connect(String host, int port, AdbClient.AuthListener listener) throws IOException;
        AdbClient.ShellResult shell(String command, int timeoutMillis) throws IOException;
        void push(InputStream source, long length, String path, AdbClient.ProgressListener listener) throws IOException;
        void close();
    }
    public interface Factory { Session create() throws IOException; }
    public interface Task<T> { T run(Session session) throws Exception; }
    public interface Callback<T> { void success(T result); void failure(Exception error); }
    private final Executor worker, callbackExecutor;
    private final ExecutorService ownedWorker;
    private final Factory factory;
    private long generation;
    private boolean destroyed;
    private Session session;

    public AdbController(Executor callbackExecutor, Factory factory) {
        this(Executors.newSingleThreadExecutor(), callbackExecutor, factory, true);
    }
    public AdbController(Executor worker, Executor callbackExecutor, Factory factory) {
        this(worker, callbackExecutor, factory, false);
    }
    private AdbController(Executor worker, Executor callbackExecutor, Factory factory, boolean ownsWorker) {
        this.worker = worker; this.callbackExecutor = callbackExecutor; this.factory = factory;
        this.ownedWorker = ownsWorker ? (ExecutorService) worker : null;
    }

    public void connect(String host, int port, Callback<String> callback) {
        if (host == null || host.trim().isEmpty() || port < 1 || port > 65535) {
            callback.failure(new IllegalArgumentException("目标地址或端口无效")); return;
        }
        disconnect();
        final long current;
        synchronized (this) { if (destroyed) return; current = generation; }
        worker.execute(() -> {
            Session candidate = null;
            try {
                synchronized (this) { if (!valid(current)) return; }
                candidate = factory.create();
                synchronized (this) {
                    if (!valid(current)) { candidate.close(); return; }
                    session = candidate;
                }
                candidate.connect(host, port, () -> deliver(current, () -> callback.success("等待目标设备确认 ADB 授权")));
                deliver(current, () -> callback.success(host + ":" + port));
            } catch (Exception error) {
                if (candidate != null) candidate.close();
                deliver(current, () -> callback.failure(error));
            }
        });
    }

    public <T> void submit(Task<T> task, Callback<T> callback) {
        final long current;
        synchronized (this) { current = generation; }
        worker.execute(() -> {
            Session active;
            synchronized (this) { if (!valid(current)) return; active = session; }
            if (active == null) { deliver(current, () -> callback.failure(new IOException("ADB 未连接"))); return; }
            try {
                T result = task.run(active);
                deliver(current, () -> callback.success(result));
            } catch (Exception error) { deliver(current, () -> callback.failure(error)); }
        });
    }

    public void postCurrent(Runnable callback) {
        final long current;
        synchronized (this) { current = generation; }
        deliver(current, callback);
    }
    private void deliver(long current, Runnable callback) {
        callbackExecutor.execute(() -> {
            synchronized (AdbController.this) { if (!valid(current)) return; }
            callback.run();
        });
    }
    private boolean valid(long current) { return !destroyed && current == generation; }

    public void disconnect() {
        Session old;
        synchronized (this) { generation++; old = session; session = null; }
        if (old != null) old.close();
    }
    @Override public void close() {
        disconnect();
        synchronized (this) { destroyed = true; }
        if (ownedWorker != null) ownedWorker.shutdownNow();
    }

    public static Session forClient(AdbClient client) {
        return new Session() {
            public void connect(String host, int port, AdbClient.AuthListener listener) throws IOException { client.connect(host, port, listener); }
            public AdbClient.ShellResult shell(String command, int timeout) throws IOException { return client.shell(command, timeout); }
            public void push(InputStream source, long length, String path, AdbClient.ProgressListener listener) throws IOException {
                client.push(source, length, path, listener);
            }
            public void close() { client.close(); }
        };
    }
}
