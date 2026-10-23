package com.diy.tapologger;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings (SharedPreferences) + live readings shared between the service and the screen. */
public final class State {

    private State() {}

    // ---------------------------------------------------------------- settings
    public static final String PREFS = "tapo";
    public static final String K_HOST = "host";
    public static final String K_EMAIL = "email";
    public static final String K_HASH = "authHash";       // only the hash is stored, never the password
    public static final String K_INTERVAL = "interval";
    public static final String K_SCREEN_ON = "screenOn";
    public static final String K_BOOT = "boot";
    public static final String K_RUNNING = "running";     // remembered so the app can resume logging

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean configured(Context c) {
        SharedPreferences p = prefs(c);
        return p.getString(K_HOST, "").length() > 0 && p.getString(K_HASH, "").length() == 64;
    }

    public static int intervalSec(Context c) {
        return Math.max(2, prefs(c).getInt(K_INTERVAL, 10));
    }

    // ---------------------------------------------------------------- live data
    public static volatile boolean running;
    public static volatile String status = "Stopped";
    public static volatile boolean error;
    public static volatile long lastOk;           // ms of last good reading
    public static volatile long samples;          // rows written this run
    public static volatile double powerW = Double.NaN;
    public static volatile double voltageV = Double.NaN;
    public static volatile double currentA = Double.NaN;
    public static volatile double todayWh = Double.NaN;
    public static volatile double monthWh = Double.NaN;
    public static volatile double peakTodayW = Double.NaN;
    public static volatile String plugOn = "?";
    public static volatile String plugName = "";
    public static volatile int rssi;
    public static volatile String logFile = "";
    public static volatile int todayRuntimeMin = -1;
    public static volatile int monthRuntimeMin = -1;
    public static volatile String model = "P110";

    // ---------------------------------------------------------------- shared plug connection
    // One KLAP session for the whole app (service + history screen): the plug is happiest
    // with a single client session, and Klap.call() is synchronized so calls never overlap.
    private static Klap klap;
    private static String klapKey = "";

    public static synchronized Klap klap(Context c) {
        SharedPreferences p = prefs(c);
        String host = p.getString(K_HOST, ""), hash = p.getString(K_HASH, "");
        if (klap == null || !klapKey.equals(host + hash)) {
            klap = new Klap(host, Klap.unhex(hash));
            klapKey = host + hash;
        }
        return klap;
    }

    /** Cache of plug history replies, stored as small files in the app's private folder. */
    public static History.Cache historyCache(final Context c) {
        final java.io.File dir = new java.io.File(c.getFilesDir(), "history");
        dir.mkdirs();
        return new History.Cache() {
            public String get(String key) {
                java.io.File f = new java.io.File(dir, key + ".json");
                if (!f.exists()) return null;
                try {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    byte[] b = new byte[(int) f.length()];
                    int n = 0;
                    while (n < b.length) {
                        int r = in.read(b, n, b.length - n);
                        if (r < 0) break;
                        n += r;
                    }
                    in.close();
                    return new String(b, 0, n, "UTF-8");
                } catch (Exception e) {
                    return null;
                }
            }

            public void put(String key, String json) {
                try {
                    java.io.FileOutputStream os = new java.io.FileOutputStream(new java.io.File(dir, key + ".json"));
                    os.write(json.getBytes("UTF-8"));
                    os.close();
                } catch (Exception ignored) {
                }
            }
        };
    }

    /** Recent power readings for the graph (ring buffer). */
    public static final int HIST = 120;
    private static final float[] hist = new float[HIST];
    private static int histCount, histPos;

    public static synchronized void addHistory(float w) {
        hist[histPos] = w;
        histPos = (histPos + 1) % HIST;
        if (histCount < HIST) histCount++;
    }

    /** Oldest-to-newest copy of the history. */
    public static synchronized float[] history() {
        float[] out = new float[histCount];
        int start = (histPos - histCount + HIST) % HIST;
        for (int i = 0; i < histCount; i++) out[i] = hist[(start + i) % HIST];
        return out;
    }
}
