package io.github.shovelshit.apkstore;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 应用内下载管理：进度、安装（含「未知来源」授权引导）、取消、删除 */
public class DownloadsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout container;
    private TextView empty;
    private long pendingInstall = 0;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F4F6FB"));
        root.setPadding(dp(16), dp(18), dp(16), dp(10));

        // 顶部返回栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextSize(24);
        back.setTextColor(Color.parseColor("#0F172A"));
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(6), dp(2), dp(14), dp(2));
        back.setContentDescription("返回");
        back.setOnClickListener(view -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("下载管理");
        title.setTextSize(20);
        title.setTextColor(Color.parseColor("#0F172A"));
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        empty = new TextView(this);
        empty.setText("还没有下载任务，回到页面点「下载」即可");
        empty.setTextSize(13);
        empty.setTextColor(Color.parseColor("#64748B"));
        empty.setPadding(0, dp(20), 0, 0);
        root.addView(empty);

        ScrollView scroll = new ScrollView(this);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(12), 0, 0);
        scroll.addView(container);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
        if (pendingInstall != 0 && canInstall()) {
            long id = pendingInstall;
            pendingInstall = 0;
            install(id);
        }
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private boolean canInstall() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls();
    }

    private void render() {
        if (container == null) return;
        JSONArray items = DownloadStore.read(this);
        container.removeAllViews();
        empty.setVisibility(items.length() == 0 ? View.VISIBLE : View.GONE);

        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        for (int i = 0; i < items.length(); i += 1) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            container.addView(buildRow(manager, item.optLong("id"), item.optString("name")));
        }
    }

    private View buildRow(DownloadManager manager, long id, String fallbackName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(14));
        row.setBackground(background);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(10);
        row.setLayoutParams(rowParams);

        String name = fallbackName;
        String status = "记录已失效";
        int percent = -1;
        boolean success = false;
        boolean active = false;

        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
                String title = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE));
                if (title != null && !title.isEmpty()) name = title;
                int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                long done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                switch (state) {
                    case DownloadManager.STATUS_SUCCESSFUL:
                        success = true;
                        status = "已完成";
                        percent = 100;
                        break;
                    case DownloadManager.STATUS_RUNNING:
                        active = true;
                        percent = total > 0 ? (int) (done * 100 / total) : -1;
                        status = percent >= 0
                                ? String.format("下载中 %d%%  %s / %s", percent, size(done), size(total))
                                : "下载中…";
                        break;
                    case DownloadManager.STATUS_PENDING:
                        active = true;
                        status = "等待中…";
                        break;
                    case DownloadManager.STATUS_PAUSED:
                        active = true;
                        status = "已暂停";
                        break;
                    case DownloadManager.STATUS_FAILED:
                        status = "下载失败";
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception error) {
            status = "读取状态失败";
        }

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(14);
        nameView.setTextColor(Color.parseColor("#0F172A"));
        nameView.setSingleLine(true);
        row.addView(nameView);

        TextView statusView = new TextView(this);
        statusView.setText(status);
        statusView.setTextSize(12);
        statusView.setTextColor(success
                ? Color.parseColor("#047857")
                : Color.parseColor("#64748B"));
        statusView.setPadding(0, dp(4), 0, 0);
        row.addView(statusView);

        if (active) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            if (percent >= 0) bar.setProgress(percent);
            else bar.setIndeterminate(true);
            bar.setPadding(0, dp(6), 0, 0);
            row.addView(bar);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(8), 0, 0);

        if (success) {
            actions.addView(action("安装", "#2563EB", Color.WHITE, () -> install(id)));
        }
        if (active) {
            actions.addView(action("取消", "#F1F5F9", Color.parseColor("#0F172A"), () -> {
                manager.remove(id);
                DownloadStore.remove(this, id);
                render();
            }));
        }
        actions.addView(action("删除", "#F1F5F9", Color.parseColor("#0F172A"), () -> {
            manager.remove(id);
            DownloadStore.remove(this, id);
            toast("已删除");
            render();
        }));
        row.addView(actions);
        return row;
    }

    private Button action(String label, String background, int textColor, Runnable onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setTextColor(textColor);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.parseColor(background));
        shape.setCornerRadius(dp(9));
        button.setBackground(shape);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
        params.leftMargin = dp(8);
        button.setLayoutParams(params);
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    /** 安装：没有「未知来源」权限时先去系统设置授权，返回后自动继续 */
    private void install(long id) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        Uri uri = manager.getUriForDownloadedFile(id);
        if (uri == null) {
            toast("安装包不存在或已被删除");
            return;
        }
        if (!canInstall()) {
            pendingInstall = id;
            toast("请允许「安装未知应用」，返回后会自动继续");
            try {
                Intent settings = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(settings);
            } catch (Exception error) {
                toast("无法打开授权页面，请在系统设置中手动允许");
            }
            return;
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception error) {
            toast("打开安装器失败：" + error.getMessage());
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String size(long bytes) {
        if (bytes <= 0) return "—";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index += 1;
        }
        return String.format(index == 0 ? "%.0f %s" : "%.1f %s", value, units[index]);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
