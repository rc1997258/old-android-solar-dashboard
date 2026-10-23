import com.diy.tapologger.GraphData;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
public class GraphTest {
  static int fails = 0;
  static void check(String n, boolean ok, String d) { System.out.println((ok ? "PASS " : "FAIL ") + n + "  " + d); if (!ok) fails++; }
  public static void main(String[] a) throws Exception {
    // a day of 10 s logging (8640 rows) in LoggerService's exact format, plus junk rows
    StringBuilder sb = new StringBuilder("time,power_w,voltage_v,current_a,today_wh,month_wh,plug_on,rssi\n");
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    long day = f.parse("2026-10-12 00:00:00").getTime();
    for (int i = 0; i < 8640; i++) {
      double wv = 100 + 50 * Math.sin(i / 300.0);
      sb.append(f.format(new Date(day + i * 10000L))).append(',').append(String.format(Locale.US, "%.3f", wv)).append(",230.10,0.400,123,4567,ON,-52\n");
      if (i == 100) sb.append("2026-10-12 00:16:50,,,,,,ON,-52\n");            // blank power (skipped)
      if (i == 200) sb.append("garbage line\n");
    }
    sb.append(f.format(new Date(day + 5000L))).append(",999,,,,,ON,0\n");      // out-of-order duplicate time (dropped)
    long t0 = System.nanoTime();
    GraphData g = GraphData.parseCsv(new StringReader(sb.toString()));
    long ms = (System.nanoTime() - t0) / 1000000;
    check("parse full day", g.n == 8640, g.n + " points in " + ms + " ms");
    check("first/last time", g.t[0] == day && g.t[g.n - 1] == day + 8639 * 10000L, f.format(new Date(g.t[g.n-1])));
    check("ascending kept", g.w[0] > 99 && g.w[0] < 101, "w0=" + g.w[0]);

    // lookups
    check("lowerIndex", g.lowerIndex(day + 25000) == 3, "" + g.lowerIndex(day + 25000));
    check("nearest", g.nearest(day + 14000) == 1 && g.nearest(day + 16000) == 2, g.nearest(day + 14000) + "," + g.nearest(day + 16000));
    check("nearest past end", g.nearest(day + 99999999L) == g.n - 1, "");
    float[] st = g.stats(day, day + 86400000L);
    check("stats", st != null && st[0] >= 49.9 && st[2] <= 150.1 && Math.abs(st[1] - 100) < 5, String.format("min %.1f avg %.1f max %.1f", st[0], st[1], st[2]));
    check("stats empty window", g.stats(day - 100000, day - 50000) == null, "");

    // live append
    g.add(day + 8640 * 10000L, 77f); g.add(day + 8640 * 10000L, 88f);
    check("live append dedupe", g.n == 8641 && g.w[g.n - 1] == 77f, "n=" + g.n);

    // axis maths: labels for various zoom levels on a 240 px wide screen (~4 labels)
    String[] spans = {"24h", "6h", "1h", "10min", "1min"};
    long[] spanMs = {86400000L, 21600000L, 3600000L, 600000L, 60000L};
    for (int i = 0; i < spans.length; i++) {
      long s = GraphData.timeStep(spanMs[i], 4);
      System.out.println("      span " + spans[i] + " -> tick every " + (s >= 60000 ? s / 60000 + " min" : s / 1000 + " s") + " (" + GraphData.timePattern(s) + "), labels=" + spanMs[i] / s);
      if (spanMs[i] / s > 4) fails++;
    }
    check("value step", GraphData.valueStep(150, 4) == 50 && GraphData.valueStep(1234, 4) == 500 && GraphData.valueStep(7, 3) == 5,
          GraphData.valueStep(150,4) + " " + GraphData.valueStep(1234,4) + " " + GraphData.valueStep(7,3));

    // window clamping (zoom/pan limits)
    long[] w1 = GraphData.clampWindow(day - 5000, 1000, 60000, 86400000L, day, day + 86400000L);
    check("clamp min zoom + left edge", w1[0] == day && w1[1] == 60000, w1[0] - day + " / " + w1[1]);
    long[] w2 = GraphData.clampWindow(day + 86000000L, 3600000L, 60000, 86400000L, day, day + 86400000L);
    check("clamp right edge", w2[0] + w2[1] == day + 86400000L, "");
    long[] w3 = GraphData.clampWindow(day, 999999999L, 60000, 86400000L, day, day + 600000L);
    check("clamp to data range", w3[1] == 600000, "" + w3[1]);
    System.out.println(fails == 0 ? "ALL GRAPH TESTS PASSED" : fails + " FAILED");
  }
}
