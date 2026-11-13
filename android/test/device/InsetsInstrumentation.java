package io.github.shovelshit.apkstore.device;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Point;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Test-only real-activity geometry probe. Never packaged into the product APK. */
public final class InsetsInstrumentation extends Instrumentation {
    private static final int EXTRA_TOP = 12;
    private static final int EXTRA_BOTTOM = 14;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Activity activity = null;
        try {
            Intent launch = new Intent();
            launch.setComponent(new ComponentName(getTargetContext().getPackageName(),
                    "io.github.shovelshit.apkstore.MainActivity"));
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity = startActivitySync(launch);
            waitForIdleSync();
            final Activity screen = activity;
            final View root = ((FrameLayout) screen.findViewById(android.R.id.content)).getChildAt(0);
            final LinearLayout layout = (LinearLayout) root;
            final View toolbar = layout.getChildAt(0);
            final View web = layout.getChildAt(2);
            WindowInsets original = root.getRootWindowInsets();
            if (original == null) throw new AssertionError("no reported WindowInsets");
            int[] location = new int[2];
            root.getLocationOnScreen(location);
            Point real = new Point();
            screen.getWindowManager().getDefaultDisplay().getRealSize(real);
            int excludedTop = location[1];
            int excludedBottom = real.y - location[1] - root.getHeight();
            int initialTop = root.getPaddingTop();
            int initialBottom = root.getPaddingBottom();
            StringBuilder evidence = new StringBuilder("reported=")
                    .append(original.getSystemWindowInsetTop()).append('/')
                    .append(original.getSystemWindowInsetBottom()).append(" decorExcluded=")
                    .append(excludedTop).append('/').append(excludedBottom);

            State baseline = state(root, toolbar, web);
            dispatch(screen, root, original, excludedTop, excludedBottom);
            State consumed = state(root, toolbar, web);
            check(consumed.topPadding == initialTop && consumed.bottomPadding == initialBottom,
                    "decor-excluded insets added padding: " + consumed);
            check(consumed.toolbarTop == baseline.toolbarTop && consumed.webBottom == baseline.webBottom,
                    "decor-excluded insets shifted content: " + consumed);
            evidence.append(" consumed=").append(consumed);

            dispatch(screen, root, original, excludedTop + EXTRA_TOP, excludedBottom + EXTRA_BOTTOM);
            State changed = state(root, toolbar, web);
            check(changed.topPadding == initialTop + EXTRA_TOP
                            && changed.bottomPadding == initialBottom + EXTRA_BOTTOM,
                    "changed insets did not add only unconsumed area: " + changed);
            check(changed.toolbarTop == baseline.toolbarTop + EXTRA_TOP
                            && changed.webBottom == baseline.webBottom - EXTRA_BOTTOM,
                    "changed insets did not move rendered content: " + changed);
            evidence.append(" changed=").append(changed);

            dispatch(screen, root, original, excludedTop + EXTRA_TOP, excludedBottom + EXTRA_BOTTOM);
            State repeated = state(root, toolbar, web);
            check(repeated.equals(changed), "repeated dispatch accumulated padding: " + repeated);
            evidence.append(" repeated=").append(repeated);

            dispatch(screen, root, original, excludedTop, excludedBottom);
            State restored = state(root, toolbar, web);
            check(restored.equals(consumed), "restoring insets retained padding: " + restored);
            evidence.append(" restored=").append(restored);
            runOnMainSync(screen::finish);
            Intent adb = new Intent();
            adb.setComponent(new ComponentName(getTargetContext().getPackageName(),
                    "io.github.shovelshit.apkstore.AdbActivity"));
            adb.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(adb);
            waitForIdleSync();
            Button cancel = findButton(((FrameLayout) activity.findViewById(android.R.id.content)), "取消当前操作");
            check(cancel != null && !cancel.isEnabled(), "ADB cancel must be disabled when idle");
            check(cancel.getCurrentTextColor() == 0xff64748b && cancel.getBackground().isStateful(),
                    "disabled ADB action still looks actionable: text="
                            + Integer.toHexString(cancel.getCurrentTextColor()));
            result.putString("states", evidence.toString());
            result.putString("PASS", "real activity inset dispatch geometry");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            result.putString("FAIL", failure.toString());
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            if (activity != null) {
                final Activity screen = activity;
                runOnMainSync(screen::finish);
            }
        }
    }

    private void dispatch(Activity activity, View root, WindowInsets original, int top, int bottom) {
        runOnMainSync(() -> root.dispatchApplyWindowInsets(original.replaceSystemWindowInsets(
                original.getSystemWindowInsetLeft(), top, original.getSystemWindowInsetRight(), bottom)));
        waitForIdleSync();
    }

    private Button findButton(View view, String label) {
        if (view instanceof Button && label.contentEquals(((Button) view).getText())) return (Button) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private State state(View root, View toolbar, View web) {
        int[] position = new int[2];
        toolbar.getLocationOnScreen(position);
        int toolbarTop = position[1];
        web.getLocationOnScreen(position);
        return new State(root.getPaddingTop(), root.getPaddingBottom(), toolbarTop, position[1] + web.getHeight());
    }

    private static void check(boolean valid, String problem) {
        if (!valid) throw new AssertionError(problem);
    }

    private static final class State {
        final int topPadding, bottomPadding, toolbarTop, webBottom;
        State(int topPadding, int bottomPadding, int toolbarTop, int webBottom) {
            this.topPadding = topPadding;
            this.bottomPadding = bottomPadding;
            this.toolbarTop = toolbarTop;
            this.webBottom = webBottom;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof State)) return false;
            State state = (State) other;
            return topPadding == state.topPadding && bottomPadding == state.bottomPadding
                    && toolbarTop == state.toolbarTop && webBottom == state.webBottom;
        }
        @Override public String toString() {
            return "padding(" + topPadding + ',' + bottomPadding + ") bounds(" + toolbarTop + ',' + webBottom + ')';
        }
    }
}
