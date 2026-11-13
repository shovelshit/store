package io.github.shovelshit.apkstore;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.github.shovelshit.apkstore.adb.AdbBatchGrant;
import io.github.shovelshit.apkstore.adb.AdbOperations;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Dialog rendering and cancellation state for one package's batch grant. */
final class AdbBatchGrantUi {
    interface Starter { void start(String packageName, List<String> selected, AtomicBoolean stop); }
    private final Activity activity;
    private AlertDialog confirmation, running, reportDialog;
    private TextView detail;
    private AtomicBoolean stop;
    private AdbBatchGrant.Progress last;
    private String packageName;

    AdbBatchGrantUi(Activity activity) { this.activity = activity; }

    void confirm(AdbOperations.PermissionSnapshot snapshot, String target, Starter starter) {
        List<AdbOperations.Permission> candidates = new ArrayList<>();
        for (AdbOperations.Permission permission : AdbPresentation.orderedDeclared(snapshot.permissions))
            if (permission.canGrant()) candidates.add(permission);
        if (candidates.isEmpty()) {
            if (reportDialog != null) reportDialog.dismiss();
            reportDialog = new AlertDialog.Builder(activity).setTitle("一键授权")
                    .setMessage("此应用目前没有可授权的权限。请刷新权限后重试。")
                    .setPositiveButton("关闭", null).show();
            return;
        }
        LinearLayout choices = new LinearLayout(activity);
        choices.setOrientation(LinearLayout.VERTICAL);
        choices.setPadding(dp(16), dp(8), dp(16), dp(8));
        choices.setBackgroundColor(NativeUi.BACKGROUND);
        TextView summary = label("目标：" + target + " · 用户 0\n应用：" + snapshot.packageName
                + "\n可授权 " + candidates.size() + " 项（逐项执行并回读确认）", 14);
        choices.addView(summary);
        List<CheckBox> boxes = new ArrayList<>();
        for (AdbOperations.Permission permission : candidates) {
            CheckBox box = new CheckBox(activity);
            box.setText(AdbPresentation.permissionTitle(permission) + "\n" + permission.name
                    + (permission.name.equals("android.permission.SYSTEM_ALERT_WINDOW")
                    || permission.name.equals("android.permission.WRITE_SETTINGS")
                    || permission.name.equals("android.permission.PACKAGE_USAGE_STATS") ? " · 特殊权限" : ""));
            box.setTextSize(13);
            box.setTextColor(NativeUi.INK);
            box.setMinHeight(dp(48));
            box.setChecked(true);
            choices.addView(box);
            boxes.add(box);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(choices);
        if (confirmation != null) confirmation.dismiss();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("确认一键授权")
                .setView(scroll).setNegativeButton("取消", null).setPositiveButton("开始", null).create();
        confirmation = dialog;
        dialog.setOnShowListener(ignored -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            List<String> selected = new ArrayList<>();
            for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isChecked()) selected.add(candidates.get(i).name);
            if (selected.isEmpty()) {
                summary.setText("请至少勾选一项权限。\n目标：" + target + " · 用户 0\n应用：" + snapshot.packageName);
                return;
            }
            dialog.dismiss();
            confirmation = null;
            AtomicBoolean cancel = new AtomicBoolean();
            begin(snapshot.packageName, selected, cancel);
            starter.start(snapshot.packageName, selected, cancel);
        }));
        dialog.show();
    }

    private void begin(String pkg, List<String> selected, AtomicBoolean cancel) {
        packageName = pkg; stop = cancel;
        // Progress is delivered by the worker; before the first delivery no command is confirmed.
        last = null;
        detail = label("准备授权 " + selected.size() + " 项…", 14);
        detail.setPadding(dp(20), dp(16), dp(20), dp(16));
        running = new AlertDialog.Builder(activity).setTitle("正在一键授权 · " + pkg)
                .setView(detail).setPositiveButton("停止后续操作", null).create();
        running.setCancelable(false);
        running.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) activity.onBackPressed();
            return true;
        });
        running.setOnShowListener(ignored -> running.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            cancel.set(true);
            detail.setText(detail.getText() + "\n已请求停止；当前项完成后停止。未确认项请重新读取核对。");
            running.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);
        }));
        running.show();
    }

    void progress(AdbBatchGrant.Progress value) {
        if (!active(value.packageName)) return;
        last = value;
        detail.setText("目标应用：" + value.packageName + "\n已确认授权 " + value.grantedCount()
                + " · 跳过 " + value.skippedCount() + " · 失败 " + value.failedCount()
                + " · 未确认 " + value.pendingCount() + "\n当前："
                + (value.current == null ? "准备下一项" : value.current)
                + (stop != null && stop.get() ? "\n已请求停止；当前项完成后停止。未确认项请重新读取核对。" : ""));
    }

    void finish(AdbBatchGrant.Result result, Runnable retry) {
        if (!active(result.packageName)) return;
        last = result;
        if (running != null) running.dismiss();
        running = null;
        report(result, (result.cancelled ? "已停止；未处理项可能未执行，重新读取后可重试。"
                : result.interrupted ? "读取或连接中断；未确认项可能已生效，重新连接后核对。" : "逐项核对完成。")
                + (result.reason.isEmpty() ? "" : "\n原因：" + result.reason), retry, null);
        packageName = null; stop = null;
    }

    boolean disconnect(Runnable afterSummary) {
        if (stop != null) stop.set(true);
        boolean showingSummary = running != null;
        if (running != null) {
            running.dismiss(); running = null;
            report(last, "连接已断开；未确认项可能已生效，重新连接后核对。", null, afterSummary);
        }
        packageName = null; stop = null;
        return showingSummary;
    }

    void disconnect() { disconnect(null); }

    void close() {
        if (stop != null) stop.set(true);
        if (confirmation != null) confirmation.dismiss();
        if (running != null) running.dismiss();
        if (reportDialog != null) reportDialog.dismiss();
        confirmation = null; running = null; reportDialog = null; packageName = null; stop = null;
    }

    private boolean active(String pkg) { return packageName != null && packageName.equals(pkg); }
    private void report(AdbBatchGrant.Progress value, String note, Runnable retry, Runnable afterSummary) {
        StringBuilder message = new StringBuilder(note);
        if (value != null) {
            message.append("\n已确认授权 ").append(value.grantedCount()).append(" · 跳过 ")
                    .append(value.skippedCount()).append(" · 失败 ").append(value.failedCount())
                    .append(" · 未确认 ").append(value.pendingCount());
            for (AdbBatchGrant.Entry entry : value.entries)
                message.append("\n").append(entry.permission)
                        .append("：").append(outcome(entry.outcome)).append(" · ").append(entry.reason);
        } else message.append("\n尚无已确认的授权结果。");
        if (reportDialog != null) reportDialog.dismiss();
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity).setTitle("一键授权结果")
                .setMessage(message.toString()).setNegativeButton("关闭", (d, w) -> {
                    if (afterSummary != null) afterSummary.run();
                });
        if (retry != null) dialog.setPositiveButton("重试未授权项", (d, w) -> retry.run());
        reportDialog = dialog.create();
        if (afterSummary != null) reportDialog.setCancelable(false);
        reportDialog.show();
    }
    private static String outcome(AdbBatchGrant.Outcome outcome) {
        switch (outcome) {
            case GRANTED: return "已确认";
            case SKIPPED: return "跳过";
            case FAILED: return "失败";
            default: return "未确认";
        }
    }
    private TextView label(String text, int size) {
        TextView view = new TextView(activity); view.setText(text); view.setTextSize(size);
        view.setTextColor(NativeUi.INK); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private int dp(int value) { return (int) (value * activity.getResources().getDisplayMetrics().density + .5f); }
}
