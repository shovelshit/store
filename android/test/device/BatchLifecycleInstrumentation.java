package io.github.shovelshit.apkstore.device;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercises saved batch summaries through real Activity recreation on a device. */
public final class BatchLifecycleInstrumentation extends Instrumentation {
    private static final String PKG = "com.example.camera";
    private static final String CAMERA = "android.permission.CAMERA";
    private static final String AUDIO = "android.permission.RECORD_AUDIO";
    private static final String TARGET = "127.0.0.1:5555";

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle output = new Bundle();
        Activity activity = null;
        try {
            Intent launch = new Intent().setComponent(new ComponentName(getTargetContext().getPackageName(),
                    "io.github.shovelshit.apkstore.AdbActivity"));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(launch);
            waitForIdleSync();
            Object ui = field(activity, "batchUi");
            Method begin = method(ui.getClass(), "begin", String.class, List.class, AtomicBoolean.class);
            Object runningUi = ui;
            runOnMainSync(() -> invoke(begin, runningUi, PKG, Arrays.asList(CAMERA, AUDIO), new AtomicBoolean()));
            activity = recreate(activity);
            String beforeProgress = screenText(activity);
            check(beforeProgress.contains(CAMERA) && beforeProgress.contains(AUDIO)
                    && beforeProgress.contains("未确认 2") && beforeProgress.contains(PKG)
                    && beforeProgress.contains(TARGET), "lost selected pending items before first progress: " + beforeProgress);
            ui = field(activity, "batchUi");
            Object progressedUi = ui;
            runOnMainSync(() -> invoke(begin, progressedUi, PKG, Arrays.asList(CAMERA, AUDIO), new AtomicBoolean()));
            Object entry = entry(CAMERA, "GRANTED", "回读确认已授权");
            Object pending = entry(AUDIO, "PENDING", "尚未处理");
            Class<?> progress = Class.forName("io.github.shovelshit.apkstore.adb.AdbBatchGrant$Progress");
            Constructor<?> progressConstructor = progress.getDeclaredConstructor(String.class, List.class, String.class);
            progressConstructor.setAccessible(true);
            Object value = progressConstructor.newInstance(PKG, Arrays.asList(entry, pending), AUDIO);
            Method deliver = method(ui.getClass(), "progress", progress);
            runOnMainSync(() -> invoke(deliver, progressedUi, value));
            activity = recreate(activity);
            String interrupted = screenText(activity);
            check(interrupted.contains("一键授权结果") && interrupted.contains("已确认授权 1")
                    && interrupted.contains(CAMERA) && interrupted.contains(AUDIO)
                    && interrupted.contains("未确认") && interrupted.contains(PKG) && interrupted.contains(TARGET),
                    "lost interrupted result: " + interrupted);

            // A previously visible completed result must survive another ordinary recreation.
            ui = field(activity, "batchUi");
            Object completedUi = ui;
            runOnMainSync(() -> invoke(begin, completedUi, PKG, Arrays.asList(CAMERA, AUDIO), new AtomicBoolean()));
            Class<?> resultType = Class.forName("io.github.shovelshit.apkstore.adb.AdbBatchGrant$Result");
            Class<?> snapshotType = Class.forName("io.github.shovelshit.apkstore.adb.AdbOperations$PermissionSnapshot");
            Constructor<?> resultConstructor = resultType.getDeclaredConstructor(String.class, List.class,
                    snapshotType, boolean.class, boolean.class, String.class);
            resultConstructor.setAccessible(true);
            Object result = resultConstructor.newInstance(PKG, Arrays.asList(entry, pending), null, true, false, "连接中断");
            Method finishBatch = method(ui.getClass(), "finish", resultType, Runnable.class);
            runOnMainSync(() -> invoke(finishBatch, completedUi, result, null));
            activity = recreate(activity);
            String completed = screenText(activity);
            check(completed.contains("已确认授权 1") && completed.contains(CAMERA)
                    && completed.contains(AUDIO) && completed.contains(PKG) && completed.contains(TARGET),
                    "lost completed result: " + completed);
            Activity acknowledged = activity;
            runOnMainSync(() -> {
                try { ((AlertDialog) field(field(acknowledged, "batchUi"), "reportDialog"))
                        .getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            activity = recreate(activity);
            check(!screenText(activity).contains("一键授权结果"), "acknowledged result resurrected");
            output.putString("PASS", "real AdbActivity recreation preserves interrupted and visible batch summaries");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable error) {
            output.putString("FAIL", error.toString());
            finish(Activity.RESULT_CANCELED, output);
        } finally {
            if (activity != null) {
                Activity last = activity;
                runOnMainSync(last::finish);
            }
        }
    }

    private Activity recreate(Activity old) {
        ActivityMonitor monitor = addMonitor(old.getClass().getName(), null, false);
        runOnMainSync(old::recreate);
        Activity next = waitForMonitorWithTimeout(monitor, 10000);
        removeMonitor(monitor);
        check(next != null && next != old, "Activity did not recreate");
        waitForIdleSync();
        return next;
    }

    private static Object entry(String name, String outcome, String reason) throws Exception {
        Class<?> entryType = Class.forName("io.github.shovelshit.apkstore.adb.AdbBatchGrant$Entry");
        Class<?> outcomeType = Class.forName("io.github.shovelshit.apkstore.adb.AdbBatchGrant$Outcome");
        Constructor<?> constructor = entryType.getDeclaredConstructor(String.class, outcomeType, String.class);
        constructor.setAccessible(true);
        @SuppressWarnings({"unchecked", "rawtypes"}) Object state = Enum.valueOf((Class) outcomeType, outcome);
        return constructor.newInstance(name, state, reason);
    }

    private static String screenText(Activity activity) {
        StringBuilder text = new StringBuilder();
        collect(activity.getWindow().getDecorView(), text);
        try {
            AlertDialog dialog = (AlertDialog) field(field(activity, "batchUi"), "reportDialog");
            if (dialog != null && dialog.isShowing()) collect(dialog.getWindow().getDecorView(), text);
        } catch (Exception error) { throw new RuntimeException(error); }
        return text.toString();
    }

    private static void collect(View view, StringBuilder text) {
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), text);
        }
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    private static void invoke(Method method, Object target, Object... args) {
        try { method.invoke(target, args); }
        catch (Exception error) { throw new RuntimeException(error); }
    }

    private static void check(boolean valid, String reason) {
        if (!valid) throw new AssertionError(reason);
    }
}
