package com.diy.tapologger;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Calendar;
import java.util.Locale;

/**
 * Energy usage history in the style of the Tapo app:
 * Today / Past 7 days / Past 30 days summary, then Day (hourly), Month (daily) and
 * Year (monthly) bar charts with ‹ › navigation and tap-a-bar readouts.
 * Data comes from the plug's own memory (get_energy_data); finished periods are cached.
 */
public class HistoryActivity extends Activity {

    private static final int DAY = 0, MONTH = 1, YEAR = 2;

    private final Handler ui = new Handler();
    private int mode = DAY;
    private Calendar cursor = Calendar.getInstance();
    private int requestNo;

    private TextView[] tabs = new TextView[3];
    private TextView period, next, total, average, pick, note, sToday, s7, s30;
    private Bars bars;
    private History.Series shown;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        int p = Ui.pad();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(p, p, p, p);

        // ---- header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹ ", Ui.title(), Ui.TEXT, true);
        back.setPadding(0, 0, p, 0);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        head.addView(back);
        head.addView(Ui.text(this, "Energy Usage", Ui.title(), Ui.TEXT, true));
        col.addView(head, Ui.gap(-1, 0));

        // ---- summary cards
        LinearLayout sum = new LinearLayout(this);
        sToday = summary(sum, "Today", true);
        s7 = summary(sum, "Past 7 Days", true);
        s30 = summary(sum, "Past 30 Days", false);
        col.addView(sum, Ui.gap(-1, 0));

        // ---- Day / Month / Year tabs (segmented control)
        LinearLayout seg = new LinearLayout(this);
        Ui.setBg(seg, Ui.round(0xFFE9EBEE, 0));
        seg.setPadding(p / 4, p / 4, p / 4, p / 4);
        String[] names = { "Day", "Month", "Year" };
        for (int i = 0; i < 3; i++) {
            final int m = i;
            tabs[i] = Ui.text(this, names[i], Ui.body(), Ui.TEXT, true);
            tabs[i].setGravity(Gravity.CENTER);
            tabs[i].setPadding(0, p / 2, 0, p / 2);
            tabs[i].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mode = m;
                    cursor = Calendar.getInstance();
                    load();
                }
            });
            seg.addView(tabs[i], new LinearLayout.LayoutParams(0, -2, 1f));
        }
        col.addView(seg, Ui.gap(-1, 0));

        // ---- chart card
        LinearLayout card = Ui.card(this);
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = navButton("‹");
        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { step(-1); }
        });
        period = Ui.text(this, "", Ui.body(), Ui.TEXT, true);
        period.setGravity(Gravity.CENTER);
        next = navButton("›");
        next.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { step(1); }
        });
        nav.addView(prev);
        nav.addView(period, new LinearLayout.LayoutParams(0, -2, 1f));
        nav.addView(next);
        card.addView(nav);

        total = Ui.text(this, "", Ui.big() * 0.8f, Ui.TEAL, true);
        total.setGravity(Gravity.CENTER);
        card.addView(total, new LinearLayout.LayoutParams(-1, -2));
        average = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        average.setGravity(Gravity.CENTER);
        card.addView(average, new LinearLayout.LayoutParams(-1, -2));

        bars = new Bars(this);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, (int) (Ui.w * 0.62f));
        bl.topMargin = p / 2;
        card.addView(bars, bl);

        pick = Ui.text(this, "Tap a bar to see its value", Ui.small(), Ui.SUB, false);
        pick.setGravity(Gravity.CENTER);
        pick.setPadding(0, p / 2, 0, 0);
        card.addView(pick, new LinearLayout.LayoutParams(-1, -2));
        col.addView(card, Ui.gap(-1, 0));

        note = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        col.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.PAGE);
        scroll.addView(col);
        setContentView(scroll);

        loadSummary();
        load();
    }

    private TextView summary(LinearLayout row, String label, boolean gapRight) {
        LinearLayout c = Ui.card(this);
        TextView l = Ui.text(this, label, Ui.small() * 0.9f, Ui.SUB, false);
        l.setSingleLine(true);
        c.addView(l);
        TextView v = Ui.text(this, "…", Ui.body(), Ui.TEXT, true);
        c.addView(v);
        c.addView(Ui.text(this, "kWh", Ui.small() * 0.9f, Ui.SUB, false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        if (gapRight) lp.rightMargin = Ui.pad() / 3;
        row.addView(c, lp);
        return v;
    }

    private TextView navButton(String s) {
        TextView t = Ui.text(this, s, Ui.title(), Ui.TEAL_DARK, true);
        int p = Ui.pad();
        t.setPadding(p, 0, p, 0);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ------------------------------------------------------------------ navigation

    private void step(int dir) {
        Calendar c = (Calendar) cursor.clone();
        if (mode == DAY) c.add(Calendar.DAY_OF_MONTH, dir);
        else if (mode == MONTH) c.add(Calendar.MONTH, dir);
        else c.add(Calendar.YEAR, dir);
        if (dir > 0 && isFuture(c)) return;
        cursor = c;
        load();
    }

    private boolean isFuture(Calendar c) {
        Calendar now = Calendar.getInstance();
        if (mode == YEAR) return c.get(Calendar.YEAR) > now.get(Calendar.YEAR);
        if (mode == MONTH) return c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
                > now.get(Calendar.YEAR) * 12 + now.get(Calendar.MONTH);
        return History.startOfDay(c).after(History.startOfDay(now));
    }

    // ------------------------------------------------------------------ loading

    private void load() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == mode;
            tabs[i].setTextColor(on ? 0xFFFFFFFF : Ui.TEXT);
            if (on) Ui.setBg(tabs[i], Ui.round(Ui.TEAL, 0));
            else Ui.setBg(tabs[i], null);
        }
        Calendar probe = (Calendar) cursor.clone();
        if (mode == DAY) probe.add(Calendar.DAY_OF_MONTH, 1);
        else if (mode == MONTH) probe.add(Calendar.MONTH, 1);
        else probe.add(Calendar.YEAR, 1);
        next.setTextColor(isFuture(probe) ? Ui.LINE : Ui.TEAL_DARK);

        period.setText(label());
        total.setText("…");
        average.setText("");
        pick.setText("Loading from plug…");
        note.setText("");
        bars.set(null);

        final int my = ++requestNo;
        final int m = mode;
        final Calendar at = (Calendar) cursor.clone();
        new Thread(new Runnable() {
            public void run() {
                History.Series s = null;
                String err = null;
                try {
                    History h = new History(State.klap(HistoryActivity.this), State.historyCache(HistoryActivity.this));
                    if (m == DAY) s = h.day(at);
                    else if (m == MONTH) s = h.month(at.get(Calendar.YEAR), at.get(Calendar.MONTH));
                    else s = h.year(at.get(Calendar.YEAR));
                } catch (Exception e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                    if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException)
                        err = "Plug not reachable, and no saved copy of this period yet.";
                }
                final History.Series fs = s;
                final String fe = err;
                ui.post(new Runnable() {
                    public void run() {
                        if (my == requestNo) show(fs, fe);   // ignore replies for periods we've left
                    }
                });
            }
        }).start();
    }

    private String label() {
        String[] mon = { "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        Calendar now = Calendar.getInstance();
        if (mode == DAY) {
            if (History.startOfDay(cursor).equals(History.startOfDay(now))) return "Today";
            return cursor.get(Calendar.DAY_OF_MONTH) + " " + mon[cursor.get(Calendar.MONTH)] + " " + cursor.get(Calendar.YEAR);
        }
        if (mode == MONTH) return mon[cursor.get(Calendar.MONTH)] + " " + cursor.get(Calendar.YEAR);
        return String.valueOf(cursor.get(Calendar.YEAR));
    }

    private void show(History.Series s, String err) {
        shown = s;
        if (s == null) {
            total.setText("---");
            pick.setText(err);
            pick.setTextColor(Ui.RED);
            bars.set(null);
            return;
        }
        pick.setTextColor(Ui.SUB);
        total.setText(energy(s.total()));
        int n = Math.max(1, s.count());
        average.setText("Average " + energy(s.total() / n) + " per " + s.unitWord);
        bars.set(s);
        int peak = s.peakIndex();
        pick.setText(peak >= 0 ? "Highest: " + s.tip[peak] + "  ·  " + energy(s.wh[peak]) : "No usage recorded");
        note.setText(s.fromCache ? "Plug offline: showing the last saved copy of this period." : "");
    }

    private void loadSummary() {
        new Thread(new Runnable() {
            public void run() {
                long t = -1, a = -1, c = -1;
                try {
                    History h = new History(State.klap(HistoryActivity.this), State.historyCache(HistoryActivity.this));
                    a = h.lastDays(7);
                    c = h.lastDays(30);
                    t = h.lastDays(1);
                } catch (Exception ignored) {
                }
                final long ft = t >= 0 ? t : (long) State.todayWh, fa = a, fc = c;
                ui.post(new Runnable() {
                    public void run() {
                        sToday.setText(Ui.kwh(ft));
                        s7.setText(Ui.kwh(fa));
                        s30.setText(Ui.kwh(fc));
                    }
                });
            }
        }).start();
    }

    static String energy(long wh) {
        if (wh < 0) return "---";
        if (wh < 1000) return wh + " Wh";
        return String.format(Locale.US, wh < 100000 ? "%.2f kWh" : "%.0f kWh", wh / 1000.0);
    }

    // ------------------------------------------------------------------ bar chart

    class Bars extends View {
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private History.Series s;
        private int selected = -1;
        private final RectF r = new RectF();

        Bars(Context c) {
            super(c);
            grid.setColor(Ui.LINE);
            text.setColor(Ui.SUB);
        }

        void set(History.Series s) {
            this.s = s;
            selected = -1;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (s == null || s.wh.length == 0) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
                int i = (int) (e.getX() / getWidth() * s.wh.length);
                i = Math.max(0, Math.min(s.wh.length - 1, i));
                if (i != selected) {
                    selected = i;
                    pick.setText(s.wh[i] < 0 ? s.tip[i] + "  ·  —" : s.tip[i] + "  ·  " + energy(s.wh[i]));
                    invalidate();
                }
                return true;
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            int gw = getWidth(), gh = getHeight();
            float labelH = gh * 0.11f;
            text.setTextSize(labelH * 0.8f);
            float chartH = gh - labelH - 2;
            for (int i = 0; i <= 3; i++) {                     // grid lines
                float y = chartH * i / 3f;
                c.drawLine(0, y, gw, y, grid);
            }
            if (s == null) return;
            long max = 1;
            for (long v : s.wh) max = Math.max(max, v);
            double top = max * 1.12;
            int n = s.wh.length;
            float slot = (float) gw / n;
            float bw = Math.max(1f, slot * 0.62f);
            float rad = Math.min(bw / 2f, Ui.w / 90f);
            for (int i = 0; i < n; i++) {
                float cx = slot * i + slot / 2f;
                if (s.wh[i] >= 0) {
                    float h = (float) (s.wh[i] / top * chartH);
                    if (s.wh[i] > 0) h = Math.max(h, 2);
                    bar.setColor(i == selected ? Ui.TEAL_DARK : Ui.TEAL);
                    r.set(cx - bw / 2f, chartH - h, cx + bw / 2f, chartH);
                    c.drawRoundRect(r, rad, rad, bar);
                }
                String a = s.axis[i];
                if (a.length() > 0)
                    c.drawText(a, cx - text.measureText(a) / 2f, gh - labelH * 0.15f, text);
            }
            String m = energy(max);
            c.drawText(m, gw - text.measureText(m), labelH * 0.8f, text);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (State.prefs(this).getBoolean(State.K_SCREEN_ON, true))
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
