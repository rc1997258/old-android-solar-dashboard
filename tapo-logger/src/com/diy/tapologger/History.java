package com.diy.tapologger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Energy history stored on the P110 itself, read with the local "get_energy_data" request:
 *   hourly  : interval 60,    start = day 00:00, end = day 23:59:59 (max 8 days per call)
 *   daily   : interval 1440,  start = end = first day of a quarter  -> one value per day
 *   monthly : interval 43200, start = end = 1 January               -> one value per month
 * Reply: {"error_code":0,"result":{"data":[Wh,...],"start_timestamp":..,"interval":..,..}}
 *
 * Past periods never change, so they are cached and not re-fetched. Current periods are
 * always fetched, falling back to the cache when the plug can't be reached.
 *
 * Pure java.* (no Android classes) so it can be tested on a desktop JVM.
 */
public class History {

    /** Where raw replies are cached (a folder of small files on Android, a map in tests). */
    public interface Cache {
        String get(String key);
        void put(String key, String json);
    }

    /** One chart: a value (Wh) per bar, with labels. */
    public static class Series {
        public String title;          // e.g. "12 Oct 2026", "October 2026", "2026"
        public String[] axis;         // short x-axis labels
        public String[] tip;          // long label for the tapped bar
        public long[] wh;             // value per bar; -1 = no data (future)
        public boolean fromCache;     // true if the plug couldn't be reached
        public String unitWord;       // "hour", "day", "month"

        public long total() {
            long t = 0;
            for (long v : wh) if (v > 0) t += v;
            return t;
        }

        public int count() {
            int n = 0;
            for (long v : wh) if (v >= 0) n++;
            return n;
        }

        public int peakIndex() {
            int p = -1;
            for (int i = 0; i < wh.length; i++) if (wh[i] > 0 && (p < 0 || wh[i] > wh[p])) p = i;
            return p;
        }
    }

    private static final String[] MON = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
    private static final String[] MONTH = { "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December" };

    private final Klap klap;
    private final Cache cache;

    public History(Klap klap, Cache cache) {
        this.klap = klap;
        this.cache = cache;
    }

    // ------------------------------------------------------------------ periods

    /** 24 hourly bars for one day. */
    public Series day(Calendar date) throws IOException {
        Calendar d = startOfDay(date);
        long start = d.getTimeInMillis() / 1000;
        long end = start + 86399;
        String key = String.format(Locale.US, "h_%tF", d);
        Fetched f = fetch(key, "{\"start_timestamp\":" + start + ",\"end_timestamp\":" + end
                + ",\"interval\":60}", isPast(end));
        Series s = new Series();
        s.title = String.format(Locale.US, "%d %s %d", d.get(Calendar.DAY_OF_MONTH),
                MON[d.get(Calendar.MONTH)], d.get(Calendar.YEAR));
        s.unitWord = "hour";
        s.fromCache = f.cached;
        s.wh = new long[24];
        s.axis = new String[24];
        s.tip = new String[24];
        long nowHour = (System.currentTimeMillis() / 1000 - start) / 3600;   // bars after now = future
        for (int h = 0; h < 24; h++) {
            s.wh[h] = h < f.data.length ? f.data[h] : 0;
            if (h > nowHour) s.wh[h] = -1;
            s.axis[h] = h % 6 == 0 ? String.valueOf(h) : "";
            s.tip[h] = String.format(Locale.US, "%02d:00 - %02d:00", h, (h + 1) % 24);
        }
        return s;
    }

    /** One bar per day of the month (taken from the quarter's daily data). */
    public Series month(int year, int month0) throws IOException {
        long[] q = quarterDaily(year, month0 / 3 * 3);
        Calendar first = cal(year, month0, 1);
        Calendar qStart = cal(year, month0 / 3 * 3, 1);
        int offset = daysBetween(qStart, first);
        int days = first.getActualMaximum(Calendar.DAY_OF_MONTH);
        Series s = new Series();
        s.title = MONTH[month0] + " " + year;
        s.unitWord = "day";
        s.fromCache = lastCached;
        s.wh = new long[days];
        s.axis = new String[days];
        s.tip = new String[days];
        Calendar today = startOfDay(Calendar.getInstance());
        for (int d = 0; d < days; d++) {
            int i = offset + d;
            s.wh[d] = i < q.length ? q[i] : 0;
            Calendar c = cal(year, month0, d + 1);
            if (c.after(today)) s.wh[d] = -1;
            s.axis[d] = (d + 1) % 5 == 0 || d == 0 ? String.valueOf(d + 1) : "";
            s.tip[d] = String.format(Locale.US, "%d %s", d + 1, MON[month0]);
        }
        return s;
    }

    /** Twelve monthly bars for one year. */
    public Series year(int year) throws IOException {
        Calendar jan = cal(year, 0, 1);
        long ts = jan.getTimeInMillis() / 1000;
        boolean past = isPast(cal(year + 1, 0, 1).getTimeInMillis() / 1000 - 1);
        Fetched f = fetch("m_" + year, "{\"start_timestamp\":" + ts + ",\"end_timestamp\":" + ts
                + ",\"interval\":43200}", past);
        Series s = new Series();
        s.title = String.valueOf(year);
        s.unitWord = "month";
        s.fromCache = f.cached;
        s.wh = new long[12];
        s.axis = new String[12];
        s.tip = new String[12];
        Calendar now = Calendar.getInstance();
        for (int m = 0; m < 12; m++) {
            s.wh[m] = m < f.data.length ? f.data[m] : 0;
            if (year > now.get(Calendar.YEAR) || (year == now.get(Calendar.YEAR) && m > now.get(Calendar.MONTH)))
                s.wh[m] = -1;
            s.axis[m] = MON[m].substring(0, 1);
            s.tip[m] = MONTH[m] + " " + year;
        }
        return s;
    }

    /** Sum of the last n days including today, in Wh (uses daily quarter data). */
    public long lastDays(int n) throws IOException {
        Calendar day = startOfDay(Calendar.getInstance());
        long sum = 0;
        long[] q = null;
        int qYear = -1, qMonth = -1;
        for (int i = 0; i < n; i++) {
            int y = day.get(Calendar.YEAR), qm = day.get(Calendar.MONTH) / 3 * 3;
            if (y != qYear || qm != qMonth) {
                q = quarterDaily(y, qm);
                qYear = y;
                qMonth = qm;
            }
            int idx = daysBetween(cal(y, qm, 1), day);
            if (idx < q.length) sum += Math.max(0, q[idx]);
            day.add(Calendar.DAY_OF_MONTH, -1);
        }
        return sum;
    }

    // ------------------------------------------------------------------ fetching

    private boolean lastCached;

    private long[] quarterDaily(int year, int qMonth0) throws IOException {
        Calendar qs = cal(year, qMonth0, 1);
        long ts = qs.getTimeInMillis() / 1000;
        Calendar next = cal(year, qMonth0, 1);
        next.add(Calendar.MONTH, 3);
        Fetched f = fetch(String.format(Locale.US, "d_%d_q%d", year, qMonth0 / 3 + 1),
                "{\"start_timestamp\":" + ts + ",\"end_timestamp\":" + ts + ",\"interval\":1440}",
                isPast(next.getTimeInMillis() / 1000 - 1));
        lastCached = f.cached;
        return f.data;
    }

    private static class Fetched {
        long[] data;
        boolean cached;
    }

    private Fetched fetch(String key, String params, boolean immutable) throws IOException {
        Fetched f = new Fetched();
        String cachedJson = cache != null ? cache.get(key) : null;
        if (immutable && cachedJson != null) {          // finished period: never changes
            f.data = parse(cachedJson);
            f.cached = false;
            return f;
        }
        try {
            String json = klap.call("get_energy_data", params);
            f.data = parse(json);                       // throws on error_code != 0
            if (cache != null) cache.put(key, json);
            return f;
        } catch (IOException e) {
            if (cachedJson == null) throw e;
            f.data = parse(cachedJson);
            f.cached = true;
            return f;
        }
    }

    private static final Pattern ERR = Pattern.compile("\"error_code\"\\s*:\\s*(-?\\d+)");
    private static final Pattern DATA = Pattern.compile("\"data\"\\s*:\\s*\\[([^\\]]*)\\]");

    /** Extract the "data" array (Wh values) from a reply. */
    static long[] parse(String json) throws IOException {
        Matcher e = ERR.matcher(json);
        if (e.find() && !e.group(1).equals("0"))
            throw new Klap.KlapException("Plug has no history for this request (error_code " + e.group(1) + ")");
        Matcher m = DATA.matcher(json);
        if (!m.find()) throw new Klap.KlapException("Unexpected history reply");
        String body = m.group(1).trim();
        if (body.length() == 0) return new long[0];
        String[] parts = body.split(",");
        List<Long> out = new ArrayList<Long>();
        for (String p : parts) out.add(Long.parseLong(p.trim()));
        long[] r = new long[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    // ------------------------------------------------------------------ dates

    static Calendar cal(int year, int month0, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, day, 0, 0, 0);
        return c;
    }

    static Calendar startOfDay(Calendar src) {
        return cal(src.get(Calendar.YEAR), src.get(Calendar.MONTH), src.get(Calendar.DAY_OF_MONTH));
    }

    static int daysBetween(Calendar from, Calendar to) {
        // count calendar days (robust to DST): step through dates
        Calendar c = startOfDay(from);
        Calendar t = startOfDay(to);
        int n = 0;
        while (c.before(t)) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            n++;
        }
        return n;
    }

    private static boolean isPast(long endTsSeconds) {
        return endTsSeconds < System.currentTimeMillis() / 1000 - 3600;   // ended over an hour ago
    }
}
