package io.github.shovelshit.apkstore;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.shovelshit.apkstore.adb.AdbClient;
import io.github.shovelshit.apkstore.adb.AdbIdentity;
import io.github.shovelshit.apkstore.adb.AdbOperations;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native, explicitly user-driven ADB tool. No WebView bridge or arbitrary command entry. */
public final class AdbActivity extends Activity {
    public static final String EXTRA_DOWNLOAD_ID = "download_id";
    private static final int PICK_APK = 91;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
    private AdbController controller;
    private ApkInstallSource source;
    private int sourceEpoch;
    private boolean connected, busy;
    private EditText host, port, search;
    private TextView status, selected, target;
    private Button connect, install, cancel, choose;
    private LinearLayout apps, permissions;
    private CheckBox system;
    private List<AdbOperations.Application> applicationList = new ArrayList<>();
    private String selectedPackage;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        controller = new AdbController(main::post, () -> AdbController.forClient(
                new AdbClient(AdbIdentity.loadOrCreate(new File(getFilesDir(), "adb")))));
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(24));
        root.setBackgroundColor(Color.parseColor("#F4F6FB"));
        scroll.addView(root);
        setContentView(scroll);
        root.addView(text("ADB 设备管理", 20));
        root.addView(text("请在目标设备开启无线 ADB，并确认首次授权提示。仅连接已知设备。", 13));
        host = input("目标 IP 地址", "127.0.0.1");
        port = input("端口", "5555");
        port.setInputType(2);
        root.addView(host); root.addView(port);
        target = text("未连接", 14); root.addView(target);
        status = text("输入目标地址并连接", 13); root.addView(status);
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        connect = button("连接 / 重试", () -> connect()); controls.addView(connect);
        controls.addView(button("断开", () -> disconnect(false)));
        cancel = button("取消当前操作", () -> disconnect(true)); controls.addView(cancel);
        root.addView(controls);
        TextWatcher targetChange = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { disconnect(false); }
            public void afterTextChanged(Editable e) { }
        };
        host.addTextChangedListener(targetChange); port.addTextChangedListener(targetChange);
        root.addView(text("安装 APK", 17));
        selected = text("尚未选择安装包", 13); root.addView(selected);
        choose = button("选择本地 APK", () -> {
            if (busy) return;
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.setType("application/vnd.android.package-archive");
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(picker, PICK_APK);
        });
        root.addView(choose);
        install = button("安装到目标设备", this::confirmInstall); root.addView(install);
        root.addView(text("应用与权限", 17));
        search = input("搜索包名", ""); root.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { renderApps(); }
            public void afterTextChanged(Editable e) { }
        });
        system = new CheckBox(this); system.setText("包含系统应用"); system.setOnCheckedChangeListener((v, checked) -> loadApps()); root.addView(system);
        root.addView(button("刷新应用列表", this::loadApps));
        apps = new LinearLayout(this); apps.setOrientation(LinearLayout.VERTICAL); root.addView(apps);
        permissions = new LinearLayout(this); permissions.setOrientation(LinearLayout.VERTICAL); root.addView(permissions);
        updateButtons();
        long download = getIntent().getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (download > 0) {
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri uri = manager.getUriForDownloadedFile(download);
            if (uri == null) status.setText("下载记录不存在，请重新选择安装包");
            else selectSource(uri);
        }
    }

    private void connect() {
        if (busy) return;
        String address = host.getText().toString().trim();
        int number;
        try { number = Integer.parseInt(port.getText().toString().trim()); }
        catch (NumberFormatException error) { status.setText("端口必须是 1 到 65535"); return; }
        connected = false; busy = true; status.setText("正在连接，请在目标设备确认授权…"); updateButtons();
        controller.connect(address, number, new AdbController.Callback<String>() {
            public void success(String result) {
                if (result.startsWith("等待")) { status.setText(result); return; }
                busy = false; connected = true;
                target.setText("目标设备：" + result + " · 本机授权身份保存在应用私有目录");
                status.setText("已连接"); updateButtons();
                controller.submit(s -> new AdbOperations(cmd -> s.shell(cmd, 30000)).androidVersion(), new AdbController.Callback<String>() {
                    public void success(String version) { target.append(" · Android " + version); loadApps(); }
                    public void failure(Exception error) { status.setText("读取设备版本失败：" + error.getMessage()); loadApps(); }
                });
            }
            public void failure(Exception error) { busy = false; status.setText("连接失败：" + error.getMessage()); updateButtons(); }
        });
    }
    private void disconnect(boolean cancelWork) {
        if (controller == null) return;
        boolean inFlight = busy;
        controller.disconnect(); connected = false; busy = false;
        target.setText("未连接");
        if (cancelWork && inFlight) status.setText("本机操作已取消；设备端安装可能仍在进行，请检查目标应用状态");
        else status.setText("已断开；更改目标后需手动连接");
        applicationList.clear(); selectedPackage = null;
        if (apps != null) apps.removeAllViews();
        if (permissions != null) permissions.removeAllViews();
        updateButtons();
    }
    private void updateButtons() {
        if (connect == null) return;
        connect.setEnabled(!busy);
        install.setEnabled(connected && !busy && source != null);
        cancel.setEnabled(busy);
        choose.setEnabled(!busy);
    }
    private void loadApps() {
        if (!connected || busy) return;
        busy = true; updateButtons(); status.setText("正在读取应用…");
        boolean includeSystem = system.isChecked();
        controller.submit(s -> {
            AdbOperations op = new AdbOperations(cmd -> s.shell(cmd, 30000));
            List<AdbOperations.Application> all = op.listApplications(false);
            if (includeSystem) all.addAll(op.listApplications(true));
            return all;
        }, new AdbController.Callback<List<AdbOperations.Application>>() {
            public void success(List<AdbOperations.Application> result) {
                busy = false; applicationList = result; status.setText("已读取 " + result.size() + " 个应用"); renderApps(); updateButtons();
            }
            public void failure(Exception error) { busy = false; status.setText("读取应用失败：" + error.getMessage()); updateButtons(); }
        });
    }
    private void renderApps() {
        if (apps == null) return;
        apps.removeAllViews();
        String query = search.getText().toString().trim().toLowerCase(java.util.Locale.US);
        int count = 0;
        for (AdbOperations.Application app : applicationList) {
            if (!app.packageName.toLowerCase(java.util.Locale.US).contains(query)) continue;
            if (++count > 100) { apps.addView(text("仅显示前 100 项，请输入包名缩小范围", 12)); break; }
            apps.addView(button(app.packageName, () -> showPermissions(app.packageName)));
        }
    }
    private void showPermissions(String packageName) {
        if (!connected || busy) return;
        selectedPackage = packageName; busy = true; updateButtons(); status.setText("读取 " + packageName + " 的实际权限…");
        controller.submit(s -> new AdbOperations(cmd -> s.shell(cmd, 30000)).readPermissions(packageName), permissionCallback());
    }
    private AdbController.Callback<AdbOperations.PermissionSnapshot> permissionCallback() {
        return new AdbController.Callback<AdbOperations.PermissionSnapshot>() {
            public void success(AdbOperations.PermissionSnapshot snapshot) {
                busy = false; updateButtons(); status.setText("已读取 " + snapshot.packageName + " 的权限");
                permissions.removeAllViews(); permissions.addView(text(snapshot.packageName, 16));
                for (AdbOperations.Permission permission : snapshot.permissions) {
                    LinearLayout line = row();
                    TextView label = text(permission.name.substring("android.permission.".length()) + " · " + permission.state, 12);
                    line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    if (permission.mutable && permission.state != AdbOperations.State.UNKNOWN) {
                        line.addView(button(permission.state == AdbOperations.State.GRANTED ? "撤销" : "授予", () -> {
                            boolean grant = permission.state != AdbOperations.State.GRANTED;
                            new AlertDialog.Builder(AdbActivity.this).setTitle("确认修改目标权限")
                                    .setMessage(snapshot.packageName + "\n" + permission.name + "\n" + (grant ? "授予" : "撤销"))
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("确认", (dialog, which) -> mutatePermission(snapshot.packageName, permission.name, grant)).show();
                        }));
                    }
                    permissions.addView(line);
                }
            }
            public void failure(Exception error) { busy = false; updateButtons(); status.setText("权限操作失败：" + error.getMessage()); }
        };
    }
    private void mutatePermission(String pkg, String permission, boolean grant) {
        if (!connected || busy || !pkg.equals(selectedPackage)) return;
        busy = true; updateButtons(); status.setText("修改并核对目标权限…");
        controller.submit(s -> new AdbOperations(cmd -> s.shell(cmd, 30000)).setPermission(pkg, permission, grant), permissionCallback());
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK && result == RESULT_OK && data != null && data.getData() != null && !busy) selectSource(data.getData());
    }
    private void selectSource(Uri uri) {
        final int epoch = ++sourceEpoch;
        if (source != null) { source.close(); source = null; }
        selected.setText("正在读取安装包…"); updateButtons();
        fileWorker.execute(() -> {
            ApkInstallSource candidate = null;
            Exception failure = null;
            try { candidate = ApkInstallSource.fromUri(this, uri); }
            catch (Exception error) { failure = error; }
            final ApkInstallSource ready = candidate;
            final Exception problem = failure;
            main.post(() -> {
                if (isFinishing() || isDestroyed() || epoch != sourceEpoch) { if (ready != null) ready.close(); return; }
                if (problem != null) selected.setText("无法读取安装包：" + problem.getMessage());
                else {
                    source = ready;
                    selected.setText(ready.name + " · " + ready.length + " 字节\n"
                            + (ready.packageName == null ? "未识别到 APK 包信息" : ready.packageName + " " + ready.versionName));
                }
                updateButtons();
            });
        });
    }
    private void confirmInstall() {
        if (!connected || busy || source == null) return;
        String destination = host.getText().toString().trim() + ":" + port.getText().toString().trim();
        new AlertDialog.Builder(this).setTitle("确认 ADB 安装")
                .setMessage("目标：" + destination + "\n安装包：" + source.name + "\n包名："
                        + (source.packageName == null ? "未知" : source.packageName) + "\n将覆盖安装现有版本（如有）")
                .setNegativeButton("取消", null).setPositiveButton("安装", (dialog, which) -> install()).show();
    }
    private void install() {
        if (!connected || busy || source == null) return;
        busy = true; updateButtons(); status.setText("正在上传安装包…");
        ApkInstallSource chosen = source;
        controller.submit(session -> {
            AdbOperations ops = new AdbOperations(cmd -> session.shell(cmd, 120000));
            try (InputStream stream = chosen.open()) {
                return ops.install(stream, chosen.length, session::push,
                        (done, total) -> controller.postCurrent(() -> status.setText(done == total
                                ? "上传完成，正在设备安装…" : "上传 " + (done * 100 / total) + "%")));
            }
        }, new AdbController.Callback<String>() {
            public void success(String result) { busy = false; status.setText("目标设备安装成功：" + result); updateButtons(); }
            public void failure(Exception error) {
                busy = false; status.setText("安装失败或结果不确定：" + error.getMessage() + "；请检查目标设备状态"); updateButtons();
            }
        });
    }
    @Override protected void onDestroy() {
        sourceEpoch++;
        controller.close(); fileWorker.shutdown();
        if (source != null) source.close();
        super.onDestroy();
    }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.parseColor("#17212F")); view.setPadding(0, dp(9), 0, dp(9)); return view;
    }
    private EditText input(String hint, String value) { EditText view = new EditText(this); view.setSingleLine(true); view.setHint(hint); view.setText(value); return view; }
    private Button button(String label, Runnable action) {
        Button view = new Button(this); view.setText(label); view.setAllCaps(false);
        view.setTextSize(12); view.setOnClickListener(v -> action.run()); return view;
    }
    private int dp(int size) { return (int) (size * getResources().getDisplayMetrics().density + .5f); }
}
