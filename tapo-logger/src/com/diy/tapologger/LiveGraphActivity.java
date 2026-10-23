package com.diy.tapologger;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Live power graph built from the day's CSV log, extended with each new reading.
 *   drag            scroll through time
 *   pinch / + -     zoom (buttons for phones without multi-touch)
 *   tap             read the exact time and watts of a point
 *   double-tap/Fit  show the whole day
 *   Now             jump back to live and follow new readings
 */
public class LiveGraphActivity extends Activity {

    private static final long MIN_SPAN = 60 * 1000L;           // most zoomed in: 1 minute
    private static final long DAY = 24 * 3600 * 1000L;
    private static final long LIVE_SPAN = 30 * 60 * 1000L;     // opening view for today: last 30 min

    private final Handler ui = new Handler();
    private Calendar day = History.startOfDay(Calendar.getInstance());
    private GraphData data = new GraphData();
    private boolean loading;
    private int loadNo;
    private boolean loadedOnce;
    private long pausedAt;

    private TextView dayLabel, live, readW, readTime, range, stats, nextDay;
    private Chart chart;

    private final Runnable tick = new Runnable() {
        public void run() {
            liveUpdate();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        int p = Ui.pad();

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(p, p, p, p);

        // header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.text(this, "‹ ", Ui.title(), Ui.TEXT, true);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        head.addView(back);
        head.addView(Ui.text(this, "Live Power", Ui.title(), Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));
        live = Ui.text(this, "", Ui.small(), Ui.TEAL_DARK, true);
        head.addView(live);
        col.addView(head, Ui.gap(-1, 0));

        LinearLayout card = Ui.card(this);

        // day navigation
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        TextView prev = navButton("‹");
        prev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeDay(-1); }
        });
        dayLabel = Ui.text(this, "", Ui.body(), Ui.TEXT, true);
        dayLabel.setGravity(Gravity.CENTER);
        nextDay = navButton("›");
        nextDay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { changeDay(1); }
        });
        nav.addView(prev);
        nav.addView(dayLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        nav.addView(nextDay);
        card.addView(nav);

        // readout: watts + date/time of the selected (or latest) point
        LinearLayout ro = new LinearLayout(this);
        ro.setGravity(Gravity.BOTTOM);
        readW = Ui.text(this, "---", Ui.big() * 0.75f, Ui.TEAL, true);
        ro.addView(readW);
        readTime = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        readTime.setPadding(p / 2, 0, 0, Ui.w / 60);
        ro.addView(readTime);
        card.addView(ro);

        chart = new Chart(this);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, (int) (Ui.w * 0.95f));
        cl.topMargin = p / 3;
        card.addView(chart, cl);

        range = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        range.setGravity(Gravity.CENTER);
        card.addView(range, new LinearLayout.LayoutParams(-1, -2));
        stats = Ui.text(this, "", Ui.small(), Ui.TEXT, false);
        stats.setGravity(Gravity.CENTER);
        card.addView(stats, new LinearLayout.LayoutParams(-1, -2));

        // zoom buttons (pinch may not work on single-touch screens)
        LinearLayout zb = new LinearLayout(this);
        String[] names = { "−", "Fit", "+", "Now" };
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView bt = Ui.button(this, names[i], i == 3);
            bt.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (k == 0) chart.zoom(2f, -1);
                    else if (k == 1) chart.fit();
                    else if (k == 2) chart.zoom(0.5f, -1);
                    else chart.goLive();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (i < names.length - 1) lp.rightMargin = p / 3;
            zb.addView(bt, lp);
        }
        LinearLayout.LayoutParams zl = new LinearLayout.LayoutParams(-1, -2);
        zl.topMargin = p / 2;
        card.addView(zb, zl);
        col.addView(card, Ui.gap(-1, 0));

        col.addView(Ui.text(this, "Drag to scroll  ·  pinch or − / + to zoom  ·  tap for a value  ·  "
                + "double-tap to fit. The graph uses this phone's log, so it shows the times "
                + "Tapo Logger was logging.", Ui.small(), Ui.SUB, false));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.PAGE);
        scroll.addView(col);
        setContentView(scroll);
        load();
    }

    private TextView navButton(String s) {
        TextView t = Ui.text(this, s, Ui.title(), Ui.TEAL_DARK, true);
        int p = Ui.pad();
        t.setPadding(p, 0, p, 0);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (State.prefs(this).getBoolean(State.K_SCREEN_ON, true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Back from screen-off / lock: the logger kept writing the CSV, so re-read it to fill the gap.
        // A new day may also have started meanwhile.
        if (loadedOnce && System.currentTimeMillis() - pausedAt > 5000) {
            boolean wasToday = day.equals(History.startOfDay(cal(pausedAt)));
            if (wasToday) day = History.startOfDay(Calendar.getInstance());
            if (wasToday || isToday()) reload(true);
        }
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        pausedAt = System.currentTimeMillis();
        ui.removeCallbacks(tick);
        super.onPause();
    }

    private static Calendar cal(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        return c;
    }

    // ------------------------------------------------------------------ data

    private boolean isToday() {
        return day.equals(History.startOfDay(Calendar.getInstance()));
    }

    private void changeDay(int dir) {
        Calendar c = (Calendar) day.clone();
        c.add(Calendar.DAY_OF_MONTH, dir);
        if (c.after(History.startOfDay(Calendar.getInstance()))) return;
        day = c;
        load();
    }

    private void load() {
        reload(false);
    }

    /** Read the day's CSV. keepView = keep the current zoom (and live-follow) instead of resetting. */
    private void reload(final boolean keepView) {
        final boolean wasFollow = chart.follow;
        final long keepStart = chart.start, keepSpan = chart.span;
        final int keepSel = chart.selected;
        boolean today = isToday();
        dayLabel.setText(today ? "Today" : new SimpleDateFormat("EEE d MMM yyyy", Locale.US).format(day.getTime()));
        nextDay.setTextColor(today ? Ui.LINE : Ui.TEAL_DARK);
        readW.setText("…");
        readTime.setText("Loading log…");
        loading = true;
        if (!keepView) {
            data = new GraphData();
            chart.reset();
        }
        final int my = ++loadNo;
        final File f = new File(LoggerService.logDir(this),
                "tapo_" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day.getTime()) + ".csv");
        new Thread(new Runnable() {
            public void run() {
                GraphData g = new GraphData();
                try {
                    if (f.exists()) g = GraphData.parseCsv(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                } catch (Exception ignored) {
                }
                final GraphData fg = g;
                ui.post(new Runnable() {
                    public void run() {
                        if (my != loadNo) return;
                        data = fg;
                        loading = false;
                        loadedOnce = true;
                        if (keepView) {
                            chart.selected = keepSel < data.n ? keepSel : -1;
                            if (wasFollow && isToday()) {
                                chart.span = keepSpan;
                                chart.goLive();
                            } else {
                                chart.apply(keepStart, keepSpan);
                            }
                        } else if (isToday()) chart.goLive();
                        else chart.fit();
                    }
                });
            }
        }).start();
    }

    private void liveUpdate() {
        boolean today = isToday();
        live.setText(today && chart.follow ? "● LIVE" : "");
        if (loading || !today) return;
        if (State.lastOk > 0 && !Double.isNaN(State.powerW)) {
            int before = data.n;
            data.add(State.lastOk / 1000 * 1000, (float) State.powerW);   // whole seconds, like the CSV
            if (data.n != before || chart.follow) chart.onNewData();
        }
    }

    // ------------------------------------------------------------------ chart

    class Chart extends View {
        long start, span = LIVE_SPAN;
        boolean follow;
        int selected = -1;

        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint grid = new Paint();
        private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final ScaleGestureDetector pinch;
        private final GestureDetector gestures;
        private final SimpleDateFormat fmtSec = new SimpleDateFormat("HH:mm:ss", Locale.US);
        private final SimpleDateFormat fmtMin = new SimpleDateFormat("HH:mm", Locale.US);
        private final SimpleDateFormat fmtFull = new SimpleDateFormat("EEE d MMM yyyy, HH:mm:ss", Locale.US);
        private float left, bottom;    // plot margins (px)

        Chart(Context c) {
            super(c);
            line.setColor(Ui.TEAL);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeJoin(Paint.Join.ROUND);
            grid.setColor(Ui.LINE);
            axis.setColor(Ui.SUB);
            mark.setColor(Ui.TEAL_DARK);
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoom(1f / d.getScaleFactor(), d.getFocusX());
                    return true;
                }
            });
            gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) { return true; }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (pinch.isInProgress()) return true;
                    pan((long) (dx / plotW() * span));
                    return true;
                }

                @Override
                public boolean onSingleTapConfirmed(MotionEvent e) {
                    select(e.getX());
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    fit();
                    return true;
                }
            });
        }

        float plotW() { return Math.max(1, getWidth() - left); }

        long dayStart() { return day.getTimeInMillis(); }

        long upper() {   // how far right you can scroll
            return isToday() ? Math.max(System.currentTimeMillis() + 30000, data.n > 0 ? data.t[data.n - 1] : 0)
                    : dayStart() + DAY;
        }

        void reset() {
            selected = -1;
            follow = false;
            span = LIVE_SPAN;
            start = dayStart();
            invalidate();
        }

        void apply(long s, long sp) {
            long[] w = GraphData.clampWindow(s, sp, MIN_SPAN, DAY, dayStart(), upper());
            start = w[0];
            span = w[1];
            updateText();
            invalidate();
        }

        void pan(long dt) {
            follow = false;
            apply(start + dt, span);
            if (isToday() && start + span >= upper() - 1000) follow = true;   // dragged back to "now"
        }

        /** factor > 1 zooms out, < 1 zooms in; focusX = -1 means the centre (or the selected point). */
        void zoom(float factor, float focusX) {
            long focusT;
            float frac;
            if (focusX < 0) {
                if (selected >= 0 && selected < data.n) {
                    focusT = data.t[selected];
                    frac = (focusT - start) / (float) span;
                } else {
                    frac = follow ? 1f : 0.5f;
                    focusT = start + (long) (span * frac);
                }
            } else {
                frac = Math.max(0, Math.min(1, (focusX - left) / plotW()));
                focusT = start + (long) (span * frac);
            }
            long ns = (long) (span * factor);
            if (follow) apply(upper() - ns, ns);
            else apply(focusT - (long) (ns * frac), ns);
        }

        void fit() {
            follow = false;
            if (data.n >= 2) {
                long pad = Math.max(MIN_SPAN / 2, (data.t[data.n - 1] - data.t[0]) / 40);
                apply(data.t[0] - pad, data.t[data.n - 1] - data.t[0] + 2 * pad);
            } else {
                apply(dayStart(), DAY);
            }
        }

        void goLive() {
            if (!isToday()) { fit(); return; }
            follow = true;
            selected = -1;
            if (span > 6 * 3600 * 1000L) span = LIVE_SPAN;
            apply(upper() - span, span);
        }

        void onNewData() {
            if (follow) apply(upper() - span, span);
            else { updateText(); invalidate(); }
        }

        void select(float x) {
            long tt = start + (long) ((x - left) / plotW() * span);
            selected = data.nearest(tt);
            follow = false;
            updateText();
            invalidate();
        }

        void updateText() {
            int i = selected >= 0 && selected < data.n ? selected : data.n - 1;
            if (i < 0) {
                readW.setText("---");
                readTime.setText(loading ? "Loading log…" : "No readings logged for this day");
            } else {
                readW.setText(String.format(Locale.US, "%.1f W", data.w[i]));
                readTime.setText((selected >= 0 ? "" : "latest  ") + fmtFull.format(new Date(data.t[i])));
            }
            range.setText("Showing " + fmtSec.format(new Date(start)) + " – " + fmtSec.format(new Date(start + span))
                    + "  (" + spanText(span) + ")");
            float[] st = data.stats(start, start + span);
            stats.setText(st == null ? "" : String.format(Locale.US, "Min %.0f W   ·   Avg %.0f W   ·   Max %.0f W",
                    st[0], st[1], st[2]));
        }

        private String spanText(long ms) {
            long m = ms / 60000;
            return m >= 120 ? String.format(Locale.US, "%.1f h", ms / 3600000.0) : m + " min";
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_DOWN && getParent() != null)
                getParent().requestDisallowInterceptTouchEvent(true);   // the page must not scroll while dragging
            pinch.onTouchEvent(e);
            gestures.onTouchEvent(e);
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            int gw = getWidth(), gh = getHeight();
            float ts = Math.max(9, Ui.w / 26f);
            axis.setTextSize(ts);
            left = axis.measureText("0000 W") + ts / 2;
            bottom = ts * 1.6f;
            float ph = gh - bottom, pw = plotW();
            line.setStrokeWidth(Math.max(1.5f, gw / 160f));

            // y axis: round steps up to the visible maximum
            float[] st = data.stats(start, start + span);
            float maxW = st == null ? 100 : Math.max(5, st[2] * 1.15f);
            float step = GraphData.valueStep(maxW, 4);
            float top = (float) Math.ceil(maxW / step) * step;
            for (float v = 0; v <= top + 0.001f; v += step) {
                float y = ph - v / top * ph;
                c.drawLine(left, y, gw, y, grid);
                String l = String.format(Locale.US, "%.0f W", v);
                c.drawText(l, left - axis.measureText(l) - ts / 3, Math.max(ts, y + ts / 3), axis);
            }

            // x axis: round local-time steps, labels HH:mm or HH:mm:ss
            long tstep = GraphData.timeStep(span, Math.max(2, (int) (pw / (axis.measureText("00:00:00") * 1.4f))));
            SimpleDateFormat lf = GraphData.timePattern(tstep).length() > 5 ? fmtSec : fmtMin;
            long tz = TimeZone.getDefault().getOffset(start);
            long first = ((start + tz + tstep - 1) / tstep) * tstep - tz;
            for (long tt = first; tt <= start + span; tt += tstep) {
                float x = left + (tt - start) / (float) span * pw;
                c.drawLine(x, 0, x, ph, grid);
                String l = lf.format(new Date(tt));
                float lx = Math.max(left, Math.min(gw - axis.measureText(l), x - axis.measureText(l) / 2));
                c.drawText(l, lx, gh - ts / 3, axis);
            }
            c.drawLine(left, ph, gw, ph, axis);

            if (data.n == 0) {
                String m = loading ? "Loading…" : "No readings logged for this day";
                c.drawText(m, left + (pw - axis.measureText(m)) / 2, ph / 2, axis);
                return;
            }

            // the line: visible points only, thinned to ~2 per pixel, broken where logging stopped
            int i0 = Math.max(0, data.lowerIndex(start) - 1);
            int i1 = Math.min(data.n, data.lowerIndex(start + span) + 1);
            long gap = Math.max(90 * 1000L, 3000L * State.intervalSec(getContext()));
            path.reset();
            boolean open = false;
            int lastCol = Integer.MIN_VALUE;
            float colMin = 0, colMax = 0;
            long prevT = 0;
            for (int i = i0; i < i1; i++) {
                float x = left + (data.t[i] - start) / (float) span * pw;
                float y = ph - data.w[i] / top * ph;
                boolean broken = open && data.t[i] - prevT > gap;
                int col = (int) x;
                if (!open || broken) {
                    path.moveTo(x, y);
                    open = true;
                    lastCol = col;
                    colMin = colMax = y;
                } else if (col == lastCol) {          // several points in one pixel column
                    colMin = Math.min(colMin, y);
                    colMax = Math.max(colMax, y);
                } else {
                    if (colMax != colMin) {
                        path.lineTo(lastCol, colMin);
                        path.lineTo(lastCol, colMax);
                    }
                    path.lineTo(x, y);
                    lastCol = col;
                    colMin = colMax = y;
                }
                prevT = data.t[i];
            }
            c.save();
            c.clipRect(left, 0, gw, ph + line.getStrokeWidth());
            c.drawPath(path, line);

            // selected point marker
            if (selected >= 0 && selected < data.n && data.t[selected] >= start && data.t[selected] <= start + span) {
                float x = left + (data.t[selected] - start) / (float) span * pw;
                float y = ph - data.w[selected] / top * ph;
                c.drawLine(x, 0, x, ph, mark);
                c.drawCircle(x, y, Math.max(3, Ui.w / 70f), mark);
            }
            c.restore();
        }
    }
}
