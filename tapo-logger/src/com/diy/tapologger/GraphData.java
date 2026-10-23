package com.diy.tapologger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Power readings with timestamps for the live graph, loaded from a day's CSV log
 * and extended live. Also the maths for the time axis. Pure java.* (desktop-testable).
 */
public class GraphData {

    public long[] t = new long[1024];     // epoch ms, ascending
    public float[] w = new float[1024];   // watts
    public int n;

    public void add(long time, float watts) {
        if (n > 0 && time <= t[n - 1]) return;          // keep strictly ascending, drop duplicates
        if (n == t.length) {
            long[] nt = new long[n * 2];
            float[] nw = new float[n * 2];
            System.arraycopy(t, 0, nt, 0, n);
            System.arraycopy(w, 0, nw, 0, n);
            t = nt;
            w = nw;
        }
        t[n] = time;
        w[n] = watts;
        n++;
    }

    /** Load "time,power_w,..." rows written by LoggerService. Bad or blank rows are skipped. */
    public static GraphData parseCsv(Reader in) throws IOException {
        // Fast path for old phones: the date part is parsed once per day (it rarely changes
        // within a file) and HH:mm:ss is added by hand, instead of SimpleDateFormat per row.
        GraphData g = new GraphData();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String lastDate = null;
        long base = 0;
        BufferedReader r = new BufferedReader(in, 16384);
        String line;
        while ((line = r.readLine()) != null) {
            if (line.length() < 21 || line.charAt(19) != ',' || line.charAt(10) != ' ') continue;
            int c2 = line.indexOf(',', 20);
            String ps = c2 < 0 ? line.substring(20) : line.substring(20, c2);
            if (ps.length() == 0) continue;
            try {
                String date = line.substring(0, 10);
                if (!date.equals(lastDate)) {
                    base = f.parse(date).getTime();
                    lastDate = date;
                }
                int hh = (line.charAt(11) - '0') * 10 + (line.charAt(12) - '0');
                int mm = (line.charAt(14) - '0') * 10 + (line.charAt(15) - '0');
                int ss = (line.charAt(17) - '0') * 10 + (line.charAt(18) - '0');
                if (hh < 0 || hh > 23 || mm < 0 || mm > 59 || ss < 0 || ss > 59) continue;
                g.add(base + (hh * 3600L + mm * 60L + ss) * 1000L, Float.parseFloat(ps));
            } catch (ParseException e) {
                // skip
            } catch (NumberFormatException e) {
                // skip
            }
        }
        r.close();
        return g;
    }

    /** Index of the first point with t >= time (n if none). */
    public int lowerIndex(long time) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (t[mid] < time) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Index of the point closest in time, or -1 if empty. */
    public int nearest(long time) {
        if (n == 0) return -1;
        int i = lowerIndex(time);
        if (i >= n) return n - 1;
        if (i > 0 && time - t[i - 1] < t[i] - time) return i - 1;
        return i;
    }

    /** {min, avg, max} of the points inside [a, b], or null if none. */
    public float[] stats(long a, long b) {
        int i0 = lowerIndex(a), i1 = lowerIndex(b + 1);
        if (i1 <= i0) return null;
        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        double sum = 0;
        for (int i = i0; i < i1; i++) {
            mn = Math.min(mn, w[i]);
            mx = Math.max(mx, w[i]);
            sum += w[i];
        }
        return new float[] { mn, (float) (sum / (i1 - i0)), mx };
    }

    // ------------------------------------------------------------------ axis maths

    private static final long[] STEPS = { 5000L, 10000L, 15000L, 30000L, 60000L, 120000L, 300000L,
            600000L, 900000L, 1800000L, 3600000L, 7200000L, 10800000L, 21600000L, 43200000L };

    /** A "round" time step so that at most maxTicks labels fit in the visible span. */
    public static long timeStep(long spanMs, int maxTicks) {
        for (long s : STEPS) if (spanMs / s <= maxTicks) return s;
        return STEPS[STEPS.length - 1];
    }

    /** Label pattern for a step: seconds only when zoomed in that far. */
    public static String timePattern(long stepMs) {
        return stepMs < 60000 ? "HH:mm:ss" : "HH:mm";
    }

    /** A "round" value step (1, 2, 5 x 10^k) giving about `ticks` gridlines up to max. */
    public static float valueStep(float max, int ticks) {
        if (max <= 0) return 1;
        double raw = max / ticks;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double f = raw / mag;
        double nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10;
        return (float) (nice * mag);
    }

    /** Keep a [start, start+span] window within limits: span in [minSpan, maxSpan], inside [lo, hi]. */
    public static long[] clampWindow(long start, long span, long minSpan, long maxSpan, long lo, long hi) {
        span = Math.max(minSpan, Math.min(maxSpan, span));
        if (span > hi - lo) span = Math.max(minSpan, hi - lo);
        if (start < lo) start = lo;
        if (start + span > hi) start = hi - span;
        return new long[] { start, span };
    }
}
