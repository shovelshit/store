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
import android.text.TextUtils;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.shovelshit.apkstore.adb.AdbClient;
import io.github.shovelshit.apkstore.adb.AdbIdentity;
import io.github.shovelshit.apkstore.adb.AdbOperations;
import io.github.shovelshit.apkstore.adb.AdbBatchGrant;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private boolean connected, busy, installing;
    private final AdbNavigation navigation = new AdbNavigation();
    private EditText host, port, search;
    private TextView status, selected, target, identity, deviceDetail, installStatus;
    private Button connect, install, cancel, choose, deviceTab, appsTab, installTab;
    private LinearLayout permissions, appListPane, permissionPane;
    private ListView appList;
    private BaseAdapter appAdapter;
    private ProgressBar installBar;
    private final AdbPresentation.InstallProgress installProgress = new AdbPresentation.InstallProgress();
    private View devicePage, appsPage, installPage;
    private CheckBox system;
    private List<AdbOperations.Application> applicationList = new ArrayList<>();
    private List<AdbOperations.Application> visibleApplications = new ArrayList<>();
    private String selectedPackage;
    private AdbOperations.PermissionSnapshot displayedPermissions;
    private AdbBatchGrantUi batchUi;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        controller = new AdbController(main::post, () -> AdbController.forClient(
                new AdbClient(AdbIdentity.loadOrCreate(new File(getFilesDir(), "adb")))));
        batchUi = new AdbBatchGrantUi(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NativeUi.BACKGROUND);
        setContentView(root);
        NativeUi.applyInsets(this, root);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(12), dp(4), dp(12), dp(6));
        header.setBackgroundColor(Color.WHITE);
        LinearLayout title = row();
        TextView back = compactText("←", 20);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> handleBack());
        title.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView heading = compactText("ADB 设备管理", 18);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView details = compactText("详情 ›", 13);
        details.setGravity(Gravity.CENTER);
        details.setContentDescription("查看设备详情");
        details.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("设备详情")
                .setMessage(deviceDetail.getText().length() == 0 ? "暂无设备详情" : deviceDetail.getText())
                .setPositiveButton("关闭", null).show());
        title.addView(details, new LinearLayout.LayoutParams(dp(64), dp(48)));
        header.addView(title);
        target = compactText("未连接", 14);
        target.setSingleLine(true); target.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(target, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        identity = compactText("目标：未连接 · 用户 0", 12);
        identity.setSingleLine(true); identity.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(identity, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        deviceDetail = text("", 12);
        root.addView(header);

        LinearLayout tabs = row();
        deviceTab = button("设备", () -> showTab(AdbNavigation.Tab.DEVICE));
        appsTab = button("应用", () -> showTab(AdbNavigation.Tab.APPS));
        installTab = button("安装", () -> showTab(AdbNavigation.Tab.INSTALL));
        for (Button tab : new Button[] {deviceTab, appsTab, installTab})
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(tabs);
        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        ScrollView deviceScroll = new ScrollView(this);
        LinearLayout device = pane(); deviceScroll.addView(device);
        devicePage = deviceScroll; content.addView(devicePage);
        device.addView(text("无线 ADB 目标", 17));
        host = input("目标 IP 地址", getSharedPreferences("adb_target", MODE_PRIVATE)
                .getString("host", "127.0.0.1"));
        port = input("端口", String.valueOf(getSharedPreferences("adb_target", MODE_PRIVATE)
                .getInt("port", 5555)));
        port.setInputType(2);
        LinearLayout address = row();
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(0, dp(48), 3);
        hostParams.rightMargin = dp(8);
        address.addView(host, hostParams);
        address.addView(port, new LinearLayout.LayoutParams(0, dp(48), 1));
        device.addView(address);
        identity.setText(host.getText() + ":" + port.getText() + " · 未连接 · 用户 0");
        status = compactText("", 12);
        status.setSingleLine(true); status.setEllipsize(TextUtils.TruncateAt.END);
        status.setVisibility(View.GONE);
        header.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26)));
        connect = button("连接", () -> { if (connected) disconnect(false); else connect(); });
        device.addView(connect);
        cancel = button("取消当前操作", () -> disconnect(true)); NativeUi.destructive(cancel); device.addView(cancel);
        TextWatcher targetChange = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { disconnect(false); }
            public void afterTextChanged(Editable e) { }
        };
        host.addTextChangedListener(targetChange); port.addTextChangedListener(targetChange);
        ScrollView installScroll = new ScrollView(this);
        LinearLayout installPane = pane(); installScroll.addView(installPane);
        installPage = installScroll; content.addView(installPage);
        installPane.addView(text("安装 APK", 17));
        selected = text("尚未选择安装包", 13);
        NativeUi.card(selected, 12);
        installPane.addView(selected);
        choose = button("选择本地 APK", () -> {
            if (busy) return;
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.setType("application/vnd.android.package-archive");
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(picker, PICK_APK);
        });
        installPane.addView(choose);
        install = button("安装到目标设备", this::confirmInstall); installPane.addView(install);
        installBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installBar.setMax(100);
        installBar.setVisibility(View.GONE);
        installPane.addView(installBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)));
        installStatus = text(installProgress.detail(), 13); installPane.addView(installStatus);
        LinearLayout appPage = new LinearLayout(this); appPage.setOrientation(LinearLayout.VERTICAL);
        appPage.setPadding(dp(16), 0, dp(16), dp(12));
        appsPage = appPage; content.addView(appsPage);
        appListPane = new LinearLayout(this); appListPane.setOrientation(LinearLayout.VERTICAL);
        permissionPane = new LinearLayout(this); permissionPane.setOrientation(LinearLayout.VERTICAL);
        appPage.addView(appListPane, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        appPage.addView(permissionPane, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        search = input("搜索包名", ""); appListPane.addView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                navigation.showApplications(s.toString(), 0);
                renderApps();
                appList.setSelection(0);
            }
            public void afterTextChanged(Editable e) { }
        });
        system = new CheckBox(this); system.setText("包含系统应用"); system.setTextColor(NativeUi.INK);
        system.setMinHeight(dp(48)); system.setOnCheckedChangeListener((v, checked) -> loadApps()); appListPane.addView(system);
        appListPane.addView(button("刷新应用列表", this::loadApps));
        appList = new ListView(this);
        appAdapter = new BaseAdapter() {
            @Override public int getCount() { return visibleApplications.size(); }
            @Override public Object getItem(int position) { return visibleApplications.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView label = convertView instanceof TextView ? (TextView) convertView : text("", 13);
                label.setMinHeight(dp(44));
                label.setPadding(dp(8), dp(10), dp(8), dp(10));
                label.setText(visibleApplications.get(position).packageName);
                return label;
            }
        };
        appList.setAdapter(appAdapter);
        appList.setOnItemClickListener((parent, view, position, id) -> showPermissions(visibleApplications.get(position).packageName));
        appListPane.addView(appList, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        ScrollView permissionScroll = new ScrollView(this);
        permissionPane.addView(permissionScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        permissions = new LinearLayout(this); permissions.setOrientation(LinearLayout.VERTICAL); permissionScroll.addView(permissions);
        showTab(AdbNavigation.Tab.DEVICE);
        updateButtons();
        long download = getIntent().getLongExtra(EXTRA_DOWNLOAD_ID, -1);
        if (download > 0) {
            showTab(AdbNavigation.Tab.INSTALL);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            Uri uri = manager.getUriForDownloadedFile(download);
            if (uri == null) showStatus("下载记录不存在，请重新选择安装包");
            else selectSource(uri);
        }
    }

    private void connect() {
        if (busy) return;
        String address = host.getText().toString().trim();
        int number;
        try { number = Integer.parseInt(port.getText().toString().trim()); }
        catch (NumberFormatException error) { showStatus("端口必须是 1 到 65535"); return; }
        if (address.isEmpty() || number < 1 || number > 65535) {
            showStatus("目标地址或端口无效"); return;
        }
        connected = false; busy = true; showStatus("正在连接，请在目标设备确认授权…"); updateButtons();
        controller.connect(address, number, new AdbController.Callback<String>() {
            public void success(String result) {
                if (result.startsWith("等待")) { showStatus(result); return; }
                busy = false; connected = true;
                getSharedPreferences("adb_target", MODE_PRIVATE).edit()
                        .putString("host", address).putInt("port", number).apply();
                navigation.setConnected(true);
                identity.setText(result + " · shell · 用户 0");
                target.setText("已连接 · 正在读取设备信息…");
                status.setVisibility(View.GONE); updateButtons();
                controller.submit(s -> new AdbOperations(cmd -> s.shell(cmd, 30000)).deviceInfo(), new AdbController.Callback<AdbOperations.DeviceInfo>() {
                    public void success(AdbOperations.DeviceInfo info) {
                        target.setText("已连接 · " + info.manufacturer + " " + info.model + " · Android " + info.androidVersion);
                        identity.setText(result + " · " + (info.shellIdentity.startsWith("uid=0") ? "root" : "shell") + " · 用户 0");
                        deviceDetail.setText("设备：" + info.manufacturer + " " + info.model + " · Android " + info.androidVersion
                                + "\n目标：" + result + " · 用户 0\n执行身份：" + info.shellIdentity
                                + "\n本机授权身份保存在应用私有目录");
                        loadApps();
                    }
                    public void failure(Exception error) {
                        target.setText("已连接 · 设备信息不可用");
                        deviceDetail.setText("读取设备信息失败：" + error.getMessage());
                        loadApps(); showStatus("设备信息不可用，请查看详情");
                    }
                });
            }
            public void failure(Exception error) { busy = false; showStatus("连接失败，请查看详情"); deviceDetail.setText("连接失败：" + error.getMessage()); updateButtons(); }
        });
    }
    private void disconnect(boolean cancelWork) { disconnect(cancelWork, null); }
    private boolean disconnect(boolean cancelWork, Runnable afterSummary) {
        if (controller == null) return false;
        boolean showingSummary = batchUi != null && batchUi.disconnect(afterSummary);
        boolean inFlight = busy;
        controller.disconnect(); connected = false; busy = false;
        boolean cancelledInstall = installing;
        installing = false;
        navigation.setConnected(false);
        target.setText("未连接");
        identity.setText(host.getText() + ":" + port.getText() + " · 未连接 · 用户 0");
        deviceDetail.setText("");
        if (cancelledInstall) { installProgress.cancelled(); showInstallProgress(); }
        else if (cancelWork && inFlight) showStatus("本机操作已取消");
        else status.setVisibility(View.GONE);
        applicationList.clear(); selectedPackage = null; displayedPermissions = null;
        if (navigation.permissionPackage() != null) {
            navigation.showApplications(search.getText().toString(), appList.getFirstVisiblePosition());
            renderPane();
        }
        renderApps();
        if (permissions != null) permissions.removeAllViews();
        updateButtons();
        return showingSummary;
    }
    private void updateButtons() {
        if (connect == null) return;
        connect.setEnabled(!busy);
        connect.setText(connected ? "断开" : "连接 / 重试");
        install.setEnabled(connected && !busy && source != null);
        cancel.setEnabled(busy);
        choose.setEnabled(!busy);
        if (deviceTab != null) {
            NativeUi.tab(deviceTab, navigation.tab() == AdbNavigation.Tab.DEVICE, busy);
            NativeUi.tab(appsTab, navigation.tab() == AdbNavigation.Tab.APPS, busy);
            NativeUi.tab(installTab, navigation.tab() == AdbNavigation.Tab.INSTALL, busy);
            host.setEnabled(!busy); port.setEnabled(!busy);
            search.setEnabled(!busy); system.setEnabled(!busy);
            appList.setEnabled(!busy);
        }
    }
    private void loadApps() {
        if (!connected || busy) return;
        busy = true; updateButtons(); showStatus("正在读取应用…");
        boolean includeSystem = system.isChecked();
        controller.submit(s -> {
            AdbOperations op = new AdbOperations(cmd -> s.shell(cmd, 30000));
            List<AdbOperations.Application> all = op.listApplications(false);
            if (includeSystem) all.addAll(op.listApplications(true));
            return all;
        }, new AdbController.Callback<List<AdbOperations.Application>>() {
            public void success(List<AdbOperations.Application> result) {
                busy = false; applicationList = result; status.setVisibility(View.GONE); renderApps(); updateButtons();
            }
            public void failure(Exception error) { busy = false; showStatus("读取应用失败：" + error.getMessage()); updateButtons(); }
        });
    }
    private void renderApps() {
        visibleApplications = AdbPresentation.filterApps(applicationList, search.getText().toString());
        appAdapter.notifyDataSetChanged();
    }
    private void showPermissions(String packageName) {
        if (!connected || busy) return;
        navigation.showApplications(search.getText().toString(), appList.getFirstVisiblePosition());
        navigation.openPermissions(packageName);
        renderPane();
        selectedPackage = packageName; busy = true; updateButtons(); showStatus("正在读取应用权限…");
        controller.submit(s -> new AdbOperations(cmd -> s.shell(cmd, 30000)).readPermissions(packageName), permissionCallback());
    }
    private AdbController.Callback<AdbOperations.PermissionSnapshot> permissionCallback() {
        return new AdbController.Callback<AdbOperations.PermissionSnapshot>() {
            public void success(AdbOperations.PermissionSnapshot snapshot) {
                if (isFinishing() || isDestroyed() || !snapshot.packageName.equals(selectedPackage)) return;
                busy = false; updateButtons(); status.setVisibility(View.GONE);
                displayedPermissions = snapshot;
                permissions.removeAllViews(); permissions.addView(text(snapshot.packageName, 16));
                permissions.addView(button("一键授权", () -> {
                    if (!connected || busy || !snapshot.packageName.equals(selectedPackage)) return;
                    batchUi.confirm(displayedPermissions, host.getText().toString().trim() + ":"
                            + port.getText().toString().trim(), AdbActivity.this::startBatchGrant);
                }));
                int shown = 0;
                for (AdbOperations.Permission permission : AdbPresentation.orderedDeclared(snapshot.permissions)) {
                    shown++;
                    LinearLayout line = row();
                    TextView label = text(AdbPresentation.permissionTitle(permission) + "\n"
                            + AdbPresentation.permissionDetail(permission), 12);
                    line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                    if (permission.canGrant() || permission.canRevoke()) {
                        line.addView(button(permission.canRevoke() ? "撤销" : "授予", () -> {
                            boolean grant = permission.canGrant();
                            new AlertDialog.Builder(AdbActivity.this).setTitle("确认修改目标权限")
                                    .setMessage(snapshot.packageName + "\n" + permission.name + "\n" + (grant ? "授予" : "撤销"))
                                    .setNegativeButton("取消", null)
                                    .setPositiveButton("确认", (dialog, which) -> mutatePermission(snapshot.packageName, permission.name, grant)).show();
                        }));
                    }
                    permissions.addView(line);
                }
                if (shown == 0) permissions.addView(text("此应用未声明可查看的权限", 13));
            }
            public void failure(Exception error) { busy = false; updateButtons(); showStatus("权限操作失败：" + error.getMessage()); }
        };
    }
    private void mutatePermission(String pkg, String permission, boolean grant) {
        if (!connected || busy || !pkg.equals(selectedPackage)) return;
        busy = true; updateButtons(); showStatus("修改并核对目标权限…");
        controller.submit(s -> new AdbOperations(cmd -> s.shell(cmd, 30000)).setPermission(pkg, permission, grant), permissionCallback());
    }

    private void startBatchGrant(String pkg, List<String> selected, AtomicBoolean stop) {
        if (!connected || busy || !pkg.equals(selectedPackage)) { batchUi.disconnect(); return; }
        busy = true; updateButtons(); showStatus("正在逐项授权并核对…");
        controller.submitWithProgress((session, poster) -> AdbBatchGrant.run(
                new AdbOperations(cmd -> session.shell(cmd, 30000)), pkg, selected,
                stop::get, value -> poster.post(() -> {
                    if (!isFinishing() && !isDestroyed() && pkg.equals(selectedPackage)) batchUi.progress(value);
                })), new AdbController.Callback<AdbBatchGrant.Result>() {
            public void success(AdbBatchGrant.Result result) {
                if (isFinishing() || isDestroyed() || !pkg.equals(selectedPackage)) return;
                busy = false; updateButtons(); status.setVisibility(View.GONE);
                batchUi.finish(result, AdbActivity.this::previewBatchGrant);
                if (result.snapshot != null) permissionCallback().success(result.snapshot);
                else {
                    displayedPermissions = null;
                    permissions.removeAllViews();
                    permissions.addView(text("当前权限状态不可读。请重新连接后选择此应用核对权限。", 13));
                }
            }
            public void failure(Exception error) {
                if (isFinishing() || isDestroyed() || !pkg.equals(selectedPackage)) return;
                busy = false; updateButtons(); showStatus("批量授权失败：" + error.getMessage());
                batchUi.disconnect();
            }
        });
    }

    private void previewBatchGrant() {
        if (!connected || busy || selectedPackage == null) return;
        String pkg = selectedPackage;
        busy = true; updateButtons(); showStatus("正在重新读取可授权权限…");
        controller.submit(s -> new AdbOperations(cmd -> s.shell(cmd, 30000)).readPermissions(pkg),
                new AdbController.Callback<AdbOperations.PermissionSnapshot>() {
            public void success(AdbOperations.PermissionSnapshot snapshot) {
                if (isFinishing() || isDestroyed() || !pkg.equals(selectedPackage)) return;
                permissionCallback().success(snapshot);
                batchUi.confirm(snapshot, host.getText().toString().trim() + ":"
                        + port.getText().toString().trim(), AdbActivity.this::startBatchGrant);
            }
            public void failure(Exception error) {
                busy = false; updateButtons(); showStatus("读取权限失败：" + error.getMessage());
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK && result == RESULT_OK && data != null && data.getData() != null && !busy) selectSource(data.getData());
    }
    private void selectSource(Uri uri) {
        navigation.selectFile(uri.toString());
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
                            + (ready.packageName == null ? "未识别到 APK 包信息" : ready.packageName
                            + (ready.versionName == null || ready.versionName.isEmpty() ? "" : " " + ready.versionName)));
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
        busy = true; installing = true; updateButtons();
        installProgress.start(); showInstallProgress();
        ApkInstallSource chosen = source;
        controller.submitWithProgress((session, progress) -> {
            AdbOperations ops = new AdbOperations(cmd -> session.shell(cmd, 120000));
            try (InputStream stream = chosen.open()) {
                return ops.install(stream, chosen.length, session::push,
                        (done, total) -> progress.post(() -> {
                            installProgress.uploaded(done, total);
                            showInstallProgress();
                        }));
            }
        }, new AdbController.Callback<String>() {
            public void success(String result) {
                busy = false; installing = false; installProgress.succeeded(result); showInstallProgress(); updateButtons();
            }
            public void failure(Exception error) {
                busy = false; installing = false; installProgress.failed(error.getMessage()); showInstallProgress(); updateButtons();
            }
        });
    }
    @Override protected void onDestroy() {
        sourceEpoch++;
        if (batchUi != null) batchUi.close();
        controller.close(); fileWorker.shutdown();
        if (source != null) source.close();
        super.onDestroy();
    }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); return view; }
    private TextView text(String value, int size) {
        TextView view = NativeUi.text(this, value, size, NativeUi.INK);
        view.setPadding(0, dp(9), 0, dp(9)); return view;
    }
    private TextView compactText(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size);
        view.setTextColor(NativeUi.INK);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private EditText input(String hint, String value) { return NativeUi.input(this, hint, value); }
    private Button button(String label, Runnable action) {
        return NativeUi.button(this, label, action);
    }
    private int dp(int size) { return (int) (size * getResources().getDisplayMetrics().density + .5f); }

    private void showStatus(String value) {
        status.setText(value);
        status.setVisibility(View.VISIBLE);
    }
    private void showInstallProgress() {
        showStatus(installProgress.summary());
        installStatus.setText(installProgress.detail());
        installBar.setVisibility(View.VISIBLE);
        installBar.setProgress(installProgress.percent());
    }

    private LinearLayout pane() {
        LinearLayout pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setPadding(dp(16), dp(8), dp(16), dp(20));
        return pane;
    }
    private void showTab(AdbNavigation.Tab tab) {
        navigation.selectTab(tab);
        renderPane();
    }
    private void renderPane() {
        devicePage.setVisibility(navigation.tab() == AdbNavigation.Tab.DEVICE ? View.VISIBLE : View.GONE);
        appsPage.setVisibility(navigation.tab() == AdbNavigation.Tab.APPS ? View.VISIBLE : View.GONE);
        installPage.setVisibility(navigation.tab() == AdbNavigation.Tab.INSTALL ? View.VISIBLE : View.GONE);
        appListPane.setVisibility(navigation.permissionPackage() == null ? View.VISIBLE : View.GONE);
        permissionPane.setVisibility(navigation.permissionPackage() == null ? View.GONE : View.VISIBLE);
        NativeUi.tab(deviceTab, navigation.tab() == AdbNavigation.Tab.DEVICE, busy);
        NativeUi.tab(appsTab, navigation.tab() == AdbNavigation.Tab.APPS, busy);
        NativeUi.tab(installTab, navigation.tab() == AdbNavigation.Tab.INSTALL, busy);
    }
    private void handleBack() {
        AdbNavigation.Back action = navigation.back(busy);
        if (action == AdbNavigation.Back.RETURN_TO_LIST) {
            selectedPackage = null;
            renderPane();
            appList.post(() -> appList.setSelection(navigation.listPosition()));
        } else if (action == AdbNavigation.Back.CONFIRM_EXIT) {
            new AlertDialog.Builder(this).setTitle("取消当前操作并退出？")
                    .setMessage("本机操作将取消；设备端已开始的安装可能继续，请检查目标应用状态。")
                    .setNegativeButton("继续等待", null)
                    .setPositiveButton("退出", (dialog, which) -> {
                        if (!disconnect(true, this::finish)) finish();
                    }).show();
        } else finish();
    }
    @Override public void onBackPressed() { handleBack(); }

}
