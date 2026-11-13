package io.github.shovelshit.apkstore;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class UpdateActivity extends Activity {
    private static final int UNKNOWN_SOURCES = 701;
    private UpdateCoordinator coordinator;
    private TextView status, details;
    private Button action;
    private ProgressBar progress;
    private UpdateCoordinator.Listener listener;
    private boolean installPending;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        coordinator = UpdateCoordinator.get(getApplicationContext());
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(20,16,20,20); content.setBackgroundColor(Color.WHITE);
        TextView back = new TextView(this); back.setText("←  应用更新"); back.setTextSize(20);
        back.setPadding(0,8,0,20); back.setOnClickListener(v -> finish()); content.addView(back);
        TextView version = new TextView(this);
        try { android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(),0);
            version.setText("当前版本 " + info.versionName + " (" + info.versionCode + ")"); }
        catch (Exception e) { version.setText("当前版本信息不可用"); }
        content.addView(version);
        status = new TextView(this); status.setTextSize(17); status.setPadding(0,24,0,12); content.addView(status);
        details = new TextView(this); details.setTextSize(15); content.addView(details);
        progress = new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        content.addView(progress,new LinearLayout.LayoutParams(-1,10));
        action = new Button(this); content.addView(action);
        Button check = new Button(this); check.setText("检查更新"); check.setOnClickListener(v -> coordinator.check(true)); content.addView(check);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
        listener = this::render;
        coordinator.subscribe(listener);
    }
    private void render(UpdateCoordinator.State state) {
        status.setText(state.message.isEmpty() ? "尚未检查更新" : state.message);
        UpdatePolicy.Release release = state.release;
        details.setText(release == null ? "" : "版本 " + release.versionName + " (" + release.versionCode + ")\n\n" + release.notes);
        progress.setVisibility(state.phase == UpdateCoordinator.Phase.DOWNLOADING ? View.VISIBLE : View.GONE);
        progress.setProgress(release == null ? 0 : (int)(state.done*100/release.size));
        boolean ready = state.phase == UpdateCoordinator.Phase.READY;
        boolean available = state.phase == UpdateCoordinator.Phase.AVAILABLE;
        boolean retry = state.phase == UpdateCoordinator.Phase.ERROR && release != null;
        action.setVisibility(ready || available || retry ? View.VISIBLE : View.GONE);
        action.setEnabled(true);
        action.setText(ready ? "安装更新" : retry ? "重试下载" : "下载更新");
        action.setOnClickListener(v -> { if (ready) install(); else coordinator.download(); });
    }
    private void install() {
        action.setEnabled(false);
        coordinator.revalidate(verified -> {
            if (isFinishing() || isDestroyed()) return;
            action.setEnabled(true);
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                installPending = true;
                try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:"+getPackageName())),UNKNOWN_SOURCES); }
                catch (Exception e) { Toast.makeText(this,"无法打开安装权限设置",Toast.LENGTH_LONG).show(); }
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(UpdateApkProvider.uri(this,verified.release),UpdateApkProvider.MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(intent); }
            catch (Exception e) { Toast.makeText(this,"无法打开系统安装界面: " + e.getMessage(),Toast.LENGTH_LONG).show(); }
        });
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request == UNKNOWN_SOURCES && installPending) {
            installPending = false;
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) install();
            else Toast.makeText(this,"请允许本应用安装未知来源应用",Toast.LENGTH_LONG).show();
        }
    }
    @Override protected void onDestroy() { coordinator.unsubscribe(listener); super.onDestroy(); }
}
