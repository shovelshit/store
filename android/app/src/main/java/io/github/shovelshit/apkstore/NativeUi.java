package io.github.shovelshit.apkstore;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Shared visual primitives for the native screens; activities own their navigation and state. */
final class NativeUi {
    static final int BLUE = Color.rgb(37, 99, 235);
    static final int BACKGROUND = Color.rgb(244, 246, 251);
    static final int INK = Color.rgb(15, 23, 42);
    static final int MUTED = Color.rgb(100, 116, 139);
    static final int BORDER = Color.rgb(221, 228, 238);
    static final int DANGER = Color.rgb(185, 28, 28);

    private NativeUi() { }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    static GradientDrawable shape(Context context, int color, int radius, boolean stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        if (stroke) shape.setStroke(dp(context, 1), BORDER);
        return shape;
    }

    static TextView text(Context context, String value, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    static TextView headerAction(Activity activity, String label, String description, Runnable action) {
        TextView view = text(activity, label, 14, BLUE);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(dp(activity, 48));
        view.setMinHeight(dp(activity, 48));
        view.setPadding(dp(activity, 8), 0, dp(activity, 8), 0);
        view.setContentDescription(description);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    static LinearLayout header(Activity activity, String title, Runnable back) {
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), dp(activity, 4));
        bar.setBackgroundColor(Color.WHITE);
        bar.setMinimumHeight(dp(activity, 56));
        TextView arrow = headerAction(activity, "←", "返回", back);
        arrow.setTextSize(24);
        bar.addView(arrow, new LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)));
        TextView heading = text(activity, title, 19, INK);
        heading.setSingleLine(true);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.setPadding(dp(activity, 8), 0, 0, 0);
        bar.addView(heading, new LinearLayout.LayoutParams(0, dp(activity, 48), 1));
        return bar;
    }

    static Button button(Activity activity, String label, Runnable action) {
        Button view = new Button(activity);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(14);
        view.setTextColor(Color.WHITE);
        view.setBackground(shape(activity, BLUE, 6, false));
        view.setMinHeight(dp(activity, 48));
        view.setMinimumHeight(dp(activity, 48));
        view.setOnClickListener(v -> action.run());
        return view;
    }

    static void secondary(Button button) {
        button.setTextColor(BLUE);
        button.setBackground(shape(button.getContext(), Color.WHITE, 6, true));
    }

    static void destructive(Button button) {
        button.setTextColor(DANGER);
        button.setBackground(shape(button.getContext(), Color.WHITE, 6, true));
    }

    static void tab(Button button, boolean selected, boolean busy) {
        button.setEnabled(!busy);
        button.setSelected(selected);
        button.setTextColor(selected ? BLUE : MUTED);
        button.setBackground(shape(button.getContext(), selected ? Color.WHITE : BACKGROUND, 0, false));
        button.setAlpha(busy ? .55f : 1f);
    }

    static EditText input(Activity activity, String hint, String value) {
        EditText view = new EditText(activity);
        view.setSingleLine(true);
        view.setTextSize(14);
        view.setHint(hint);
        view.setText(value);
        view.setTextColor(INK);
        view.setHintTextColor(MUTED);
        view.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        view.setBackground(shape(activity, Color.WHITE, 6, true));
        return view;
    }

    static void card(View view, int padding) {
        Context context = view.getContext();
        view.setBackground(shape(context, Color.WHITE, 6, true));
        view.setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding));
    }

    /** Decor often already excludes system bars. Add only the portion overlapping content. */
    static void applyInsets(Activity activity, View root) {
        final int left = root.getPaddingLeft(), top = root.getPaddingTop();
        final int right = root.getPaddingRight(), bottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int[] origin = new int[2];
            view.getLocationOnScreen(origin);
            Point screen = new Point();
            activity.getWindowManager().getDefaultDisplay().getRealSize(screen);
            int insetLeft = Math.max(0, insets.getSystemWindowInsetLeft() - origin[0]);
            int insetTop = Math.max(0, insets.getSystemWindowInsetTop() - origin[1]);
            int insetRight = Math.max(0, insets.getSystemWindowInsetRight() - (screen.x - origin[0] - view.getWidth()));
            int insetBottom = Math.max(0, insets.getSystemWindowInsetBottom() - (screen.y - origin[1] - view.getHeight()));
            view.setPadding(left + insetLeft, top + insetTop, right + insetRight, bottom + insetBottom);
            return insets;
        });
        root.post(root::requestApplyInsets);
    }
}
