package io.github.shovelshit.apkstore;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class UpdateCoordinator {
    private static final String INDEX = "https://store.ltools.asia/android/update.json";
    enum Phase { IDLE, CHECKING, CURRENT, INCOMPATIBLE, AVAILABLE, DOWNLOADING, READY, ERROR }
    static final class State {
        final Phase phase; final UpdatePolicy.Release release; final String message; final long done;
        State(Phase phase, UpdatePolicy.Release release, String message, long done) {
            this.phase = phase; this.release = release; this.message = message; this.done = done;
        }
    }
    interface Listener { void changed(State state); }
    interface IndexSource { byte[] load() throws Exception; }
    private static UpdateCoordinator instance;
    static synchronized UpdateCoordinator get(Context context) {
        if (instance == null) instance = new UpdateCoordinator(context.getApplicationContext());
        return instance;
    }
    private final Context app;
    private final IndexSource indexSource;
    private final SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final UpdateGate gate = new UpdateGate();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile State state = new State(Phase.IDLE,null,"",0);
    private UpdateCoordinator(Context context) { this(context, () -> UpdateTransport.readIndex(INDEX,false)); }
    // Instrumentation can supply controlled bytes; the production singleton always uses INDEX.
    UpdateCoordinator(Context context, IndexSource indexSource) {
        app = context;
        this.indexSource = indexSource;
        preferences = app.getSharedPreferences("update_state",Context.MODE_PRIVATE);
        if (preferences.getBoolean("working",false)) {
            state = new State(Phase.ERROR,null,"上次更新任务中断，请重试",0);
            preferences.edit().putBoolean("working",false).apply();
        }
        String saved = preferences.getString("ready",null);
        if (saved != null) {
            try {
                UpdatePolicy.Release release = parse(new JSONObject(saved));
                if (completed(release).isFile()) state = new State(Phase.READY,release,"安装包已下载，安装前将重新校验",release.size);
            } catch (Exception ignored) { preferences.edit().remove("ready").apply(); }
        }
    }
    State state() { return state; }
    void subscribe(Listener listener) { listeners.addIfAbsent(listener); main.post(() -> { if (listeners.contains(listener)) listener.changed(state); }); }
    void unsubscribe(Listener listener) { listeners.remove(listener); }
    private void publish(State next) {
        state = next;
        main.post(() -> { for (Listener listener : listeners) listener.changed(state); });
    }
    private static UpdatePolicy.Release parse(JSONObject json) throws Exception {
        Map<String,Object> values = new HashMap<>();
        String[] keys = {"schema","packageName","versionCode","versionName","minSdk","channel","url","size","sha256","certificateSha256","notes"};
        if (json.length() != keys.length) throw new IllegalArgumentException("更新索引字段数量错误");
        for (String key : keys) { if (!json.has(key) || json.isNull(key)) throw new IllegalArgumentException("缺少更新字段: " + key); values.put(key,json.get(key)); }
        return UpdatePolicy.parse(values);
    }
    private File directory() { File dir = new File(app.getFilesDir(),"updates"); if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建更新目录"); return dir; }
    File completed(UpdatePolicy.Release release) { return new File(directory(),release.sha256 + ".apk"); }
    synchronized void check(boolean force) {
        if (state.phase == Phase.CHECKING || state.phase == Phase.DOWNLOADING) return;
        long now = System.currentTimeMillis();
        if (!UpdatePolicy.shouldCheck(now,preferences.getLong("checked",0),force)) return;
        if (!gate.begin()) return;
        preferences.edit().putLong("checked",now).apply();
        publish(new State(Phase.CHECKING,state.release,"正在检查更新",0));
        worker.execute(() -> {
            try {
                JSONObject json = new JSONObject(new String(indexSource.load(), StandardCharsets.UTF_8));
                UpdatePolicy.Release release = parse(json);
                UpdatePolicy.Decision decision = UpdatePolicy.decide(release,UpdateArchive.installed(app));
                publish(new State(decision == UpdatePolicy.Decision.AVAILABLE ? Phase.AVAILABLE
                        : decision == UpdatePolicy.Decision.INCOMPATIBLE ? Phase.INCOMPATIBLE : Phase.CURRENT,
                        decision == UpdatePolicy.Decision.AVAILABLE ? release : null,
                        decision == UpdatePolicy.Decision.AVAILABLE ? "发现新版本"
                                : decision == UpdatePolicy.Decision.INCOMPATIBLE ? "新版本不支持当前 Android 系统" : "已是当前版本",0));
            } catch (Exception e) { publish(new State(Phase.ERROR,null,"检查失败: " + e.getMessage(),0)); }
            finally { gate.end(); }
        });
    }
    synchronized void download() {
        if (state.phase != Phase.AVAILABLE && state.phase != Phase.ERROR) return;
        UpdatePolicy.Release release = state.release;
        if (release == null) return;
        if (!gate.begin()) return;
        publish(new State(Phase.DOWNLOADING,release,"正在下载",0));
        preferences.edit().putBoolean("working",true).apply();
        worker.execute(() -> {
            File part = new File(directory(),release.sha256 + ".part");
            try {
                UpdateTransport.transfer(release.url,part,release.size,release.sha256,
                        done -> publish(new State(Phase.DOWNLOADING,release,"正在下载",done)),false);
                UpdateArchive.verify(app,part,release);
                File ready = completed(release);
                java.nio.file.Files.move(part.toPath(),ready.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                JSONObject data = new JSONObject();
                data.put("schema",1).put("packageName",release.packageName).put("versionCode",release.versionCode)
                        .put("versionName",release.versionName).put("minSdk",release.minSdk).put("channel",release.channel)
                        .put("url",release.url).put("size",release.size).put("sha256",release.sha256)
                        .put("certificateSha256",release.certificateSha256).put("notes",release.notes);
                preferences.edit().putString("ready",data.toString()).putBoolean("working",false).apply();
                publish(new State(Phase.READY,release,"安装包已校验",release.size));
            } catch (Exception e) {
                preferences.edit().putBoolean("working",false).apply();
                publish(new State(Phase.ERROR,release,"下载或校验失败: " + e.getMessage(),0));
            } finally { part.delete(); gate.end(); }
        });
    }
    void revalidate(Listener callback) {
        State snapshot = state;
        if (snapshot.phase != Phase.READY) return;
        worker.execute(() -> {
            try { UpdateArchive.verify(app,completed(snapshot.release),snapshot.release); main.post(() -> callback.changed(snapshot)); }
            catch (Exception e) { preferences.edit().remove("ready").apply(); publish(new State(Phase.ERROR,snapshot.release,"安装前校验失败: " + e.getMessage(),0)); }
        });
    }
}
