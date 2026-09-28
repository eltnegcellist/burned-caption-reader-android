package jp.hidemaru.burnedcaptionreader;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small shared visual language for the URL home and settings screens. */
final class ReaderUi {
    static final int INK = Color.rgb(21, 38, 54);
    static final int MUTED = Color.rgb(87, 106, 120);
    static final int TEAL = Color.rgb(0, 111, 128);
    static final int SURFACE = Color.rgb(244, 248, 249);
    private ReaderUi() {}

    static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int color, int radius, int stroke) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(context, radius));
        if (stroke != 0) result.setStroke(dp(context, 1), stroke);
        return result;
    }

    static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(context, 3), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    static LinearLayout card(Context context) {
        LinearLayout view = new LinearLayout(context);
        view.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(context, 20);
        view.setPadding(inset, inset, inset, inset);
        view.setBackground(shape(context, Color.WHITE, 22, Color.rgb(226, 235, 238)));
        view.setElevation(dp(context, 2));
        return view;
    }

    static LinearLayout.LayoutParams block(Context context, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, top);
        return params;
    }

    static Button button(Context context, String text, boolean primary) {
        Button view = new Button(context);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextSize(16);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 54));
        view.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        view.setTextColor(primary ? Color.WHITE : TEAL);
        view.setBackground(shape(context, primary ? TEAL : Color.WHITE, 14,
                primary ? 0 : Color.rgb(199, 219, 225)));
        return view;
    }
}
