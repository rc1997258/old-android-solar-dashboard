package com.diy.tapologger;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Look and feel in the style of the Tapo app: light grey page, white rounded
 * cards, teal accent, dark-grey text with lighter captions. All sizes scale
 * from the screen width so it works on 240 px and 720 px wide phones.
 */
public final class Ui {

    private Ui() {}

    public static final int PAGE = 0xFFF3F4F6;
    public static final int CARD = 0xFFFFFFFF;
    public static final int LINE = 0xFFE5E7EB;
    public static final int TEAL = 0xFF19B3A6;
    public static final int TEAL_DARK = 0xFF0E8C82;
    public static final int TEAL_LIGHT = 0xFFD9F3F0;
    public static final int TEXT = 0xFF1F2329;
    public static final int SUB = 0xFF8A909A;
    public static final int RED = 0xFFE5484D;
    public static final int AMBER = 0xFFE89A1C;

    public static int w;   // screen width in px, set by each activity

    public static void init(Context c) {
        w = c.getResources().getDisplayMetrics().widthPixels;
    }

    /** Text sizes as a fraction of screen width. */
    public static float big() { return w / 7.5f; }
    public static float title() { return w / 15f; }
    public static float body() { return w / 18f; }
    public static float small() { return w / 23f; }
    public static int pad() { return Math.max(4, w / 28); }
    public static int radius() { return Math.max(6, w / 24); }

    public static GradientDrawable round(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius());
        if (stroke != 0) g.setStroke(Math.max(1, w / 300), stroke);
        return g;
    }

    /** White rounded card with inner padding. */
    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        setBg(l, round(CARD, LINE));
        int p = pad();
        l.setPadding(p, p, p, p);
        return l;
    }

    /** Layout params with the standard gap below. */
    public static LinearLayout.LayoutParams gap(int width, float weight) {
        LinearLayout.LayoutParams lp = weight > 0
                ? new LinearLayout.LayoutParams(0, -2, weight)
                : new LinearLayout.LayoutParams(width, -2);
        lp.bottomMargin = pad() / 2 + 2;
        return lp;
    }

    public static TextView text(Context c, String s, float px, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Teal pill button (filled) or outlined button. */
    public static TextView button(Context c, String s, boolean filled) {
        TextView b = text(c, s, body(), filled ? 0xFFFFFFFF : TEAL_DARK, true);
        b.setGravity(Gravity.CENTER);
        int p = pad();
        b.setPadding(p, p * 2 / 3, p, p * 2 / 3);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_pressed },
                round(filled ? TEAL_DARK : TEAL_LIGHT, filled ? 0 : TEAL));
        sl.addState(new int[0], round(filled ? TEAL : CARD, filled ? 0 : TEAL));
        setBg(b, sl);
        b.setClickable(true);
        return b;
    }

    @SuppressWarnings("deprecation")
    public static void setBg(View v, android.graphics.drawable.Drawable d) {
        v.setBackgroundDrawable(d);     // setBackground() needs API 16; the Galaxy Mini path is fine either way
    }

    public static String kwh(double wh) {
        if (Double.isNaN(wh) || wh < 0) return "---";
        return wh >= 100000 ? String.format(java.util.Locale.US, "%.0f", wh / 1000.0)
                : wh >= 10000 ? String.format(java.util.Locale.US, "%.1f", wh / 1000.0)
                : String.format(java.util.Locale.US, "%.2f", wh / 1000.0);
    }

    public static String runtime(int minutes) {
        if (minutes < 0) return "---";
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }
}
