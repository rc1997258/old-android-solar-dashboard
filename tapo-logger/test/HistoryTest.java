import com.diy.tapologger.*;
import java.util.*;
public class HistoryTest {
  static int fails = 0;
  static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }
  public static void main(String[] a) throws Exception {
    final Map<String,String> store = new HashMap<String,String>();
    History.Cache cache = new History.Cache() { public String get(String k) { return store.get(k); } public void put(String k, String v) { store.put(k, v); } };
    Klap k = new Klap("127.0.0.1:18080", Klap.authHash("Me@Example.com", "S3cret!"));
    History h = new History(k, cache);

    // Day: past day -> 24 bars, Wh = 10 x hour, total = 10*sum(0..23) = 2760
    Calendar d = Calendar.getInstance(); d.set(2026, Calendar.MARCH, 15);
    History.Series s = h.day(d);
    check("day bars", s.wh.length == 24 && s.wh[5] == 50 && s.wh[23] == 230, s.title + " total=" + s.total());
    check("day total", s.total() == 2760, "" + s.total());
    check("day peak", s.peakIndex() == 23, s.tip[s.peakIndex()]);

    // Month: March 2026 is days 59..89 of Q1 (Jan 31 + Feb 28 = 59) -> 1st March = 1059
    History.Series m = h.month(2026, Calendar.MARCH);
    check("month length", m.wh.length == 31, m.title);
    check("month offset", m.wh[0] == 1059 && m.wh[30] == 1089, m.wh[0] + ".." + m.wh[30]);
    History.Series feb = h.month(2026, Calendar.FEBRUARY);
    check("february", feb.wh.length == 28 && feb.wh[0] == 1031, feb.wh.length + " days, first=" + feb.wh[0]);
    History.Series aug = h.month(2026, Calendar.AUGUST);   // Q3: Jul(31) -> 1st Aug = 1031
    check("q3 month", aug.wh[0] == 1031, "" + aug.wh[0]);

    // Year: 12 months, Jan = 50000
    History.Series y = h.year(2025);
    check("year", y.wh.length == 12 && y.wh[0] == 50000 && y.wh[11] == 61000 && y.total() == 666000, y.title + " total=" + y.total());

    // Current year: months after this one are "future" (-1)
    int cy = Calendar.getInstance().get(Calendar.YEAR), cm = Calendar.getInstance().get(Calendar.MONTH);
    History.Series yc = h.year(cy);
    check("future months hidden", cm == 11 || yc.wh[11] == -1, "count=" + yc.count());

    // Cache: a finished period is served from cache without asking the plug
    int before = store.size();
    History.Series y2 = new History(new Klap("127.0.0.1:18099", Klap.authHash("x","y")), cache).year(2025);
    check("past period from cache (plug offline)", y2.total() == 666000 && !y2.fromCache, "served without network");
    // Current period, plug offline -> falls back to cache, flagged
    History.Series yc2 = new History(new Klap("127.0.0.1:18099", Klap.authHash("x","y")), cache).year(cy);
    check("current period offline fallback", yc2.fromCache, "fromCache=" + yc2.fromCache);

    // No data far back -> clear error
    try { h.year(2020); check("no history error", false, "no exception"); }
    catch (Klap.KlapException e) { check("no history error", e.getMessage().contains("no history"), e.getMessage()); }

    // Past 7 / 30 days sum works across quarter boundaries
    long p7 = h.lastDays(7), p30 = h.lastDays(30);
    check("last 7 / 30 days", p7 > 7000 && p30 > p7, "7d=" + p7 + " Wh, 30d=" + p30 + " Wh");
    System.out.println(fails == 0 ? "ALL HISTORY TESTS PASSED" : fails + " FAILED");
  }
}
