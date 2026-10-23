package com.diy.tapologger;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Polls the P110 every N seconds and appends one CSV row per reading to
 * <external files dir>/tapo_YYYY-MM-DD.csv. Runs as a foreground service with
 * a partial wake lock + Wi-Fi lock so logging continues with the screen off.
 */
public class LoggerService extends Service implements Runnable {

    private static final String TAG = "TapoLogger";
    private static final String HEADER = "time,power_w,voltage_v,current_a,today_wh,month_wh,plug_on,rssi\n";

    private volatile boolean stop;
    private Thread thread;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;

    private Klap klap;
    private Boolean emeterSupported;   // null = not tried yet
    private int pollNo;
    private Notification.Builder note;
    private String peakDay = "";

    public static void start(Context c) {
        State.prefs(c).edit().putBoolean(State.K_RUNNING, true).commit();
        c.startService(new Intent(c, LoggerService.class));
        watchdog(c, true);
    }

    public static void stop(Context c) {
        State.prefs(c).edit().putBoolean(State.K_RUNNING, false).commit();
        watchdog(c, false);
        c.stopService(new Intent(c, LoggerService.class));
    }

    /**
     * Safety net for low-memory phones: every ~5 minutes (even in deep sleep) Android wakes
     * BootReceiver, which restarts logging if it was killed while it should be running.
     */
    static void watchdog(Context c, boolean on) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, BootReceiver.class).setAction(BootReceiver.WATCHDOG);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (on) am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES / 3,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES / 3, pi);
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @SuppressWarnings("deprecation")
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (thread == null) {
            System.setProperty("http.keepAlive", "false");   // the plug's tiny HTTP server dislikes reuse
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapoLogger");
            wake.acquire();
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "TapoLogger");
            wifi.acquire();

            PendingIntent pi = PendingIntent.getActivity(this, 0,
                    new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
            note = new Notification.Builder(this)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle("Tapo Logger")
                    .setContentText("Starting…")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true);
            startForeground(1, note.getNotification());

            stop = false;
            State.running = true;
            State.samples = 0;
            thread = new Thread(this, "tapo-poll");
            thread.start();
        }
        return START_STICKY;   // Android restarts the service if it gets killed
    }

    @Override
    public void onDestroy() {
        stop = true;
        if (thread != null) thread.interrupt();
        thread = null;
        if (wake != null && wake.isHeld()) wake.release();
        if (wifi != null && wifi.isHeld()) wifi.release();
        stopForeground(true);
        State.running = false;
        State.error = false;
        State.status = "Stopped";
        super.onDestroy();
    }

    // ------------------------------------------------------------------ loop

    @Override
    public void run() {
        while (!stop) {
            long t0 = System.currentTimeMillis();
            pollOnce();
            long wait = State.intervalSec(this) * 1000L - (System.currentTimeMillis() - t0);
            try {
                if (wait > 0) Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void pollOnce() {
        try {
            if (!State.configured(this)) {
                setError("Not configured: open Settings");
                return;
            }
            Klap k = State.klap(this);                 // shared session; new one if settings changed
            if (k != klap) {
                klap = k;
                emeterSupported = null;
            }
            pollNo++;

            // 1. energy usage: today/month Wh and power in mW
            JSONObject eu = result(klap.call("get_energy_usage", null));
            double powerW = eu.has("current_power") ? eu.getDouble("current_power") / 1000.0 : Double.NaN;
            double todayWh = eu.optDouble("today_energy", Double.NaN);
            double monthWh = eu.optDouble("month_energy", Double.NaN);
            State.todayRuntimeMin = eu.optInt("today_runtime", -1);
            State.monthRuntimeMin = eu.optInt("month_runtime", -1);

            // 2. voltage/current, if this firmware supports get_emeter_data
            double volts = Double.NaN, amps = Double.NaN;
            if (emeterSupported == null || emeterSupported) {
                JSONObject em = new JSONObject(klap.call("get_emeter_data", null));
                if (em.optInt("error_code", -1) == 0) {
                    JSONObject r = em.getJSONObject("result");
                    if (r.has("voltage_mv")) volts = r.getDouble("voltage_mv") / 1000.0;
                    if (r.has("current_ma")) amps = r.getDouble("current_ma") / 1000.0;
                    if (r.has("power_mw")) powerW = r.getDouble("power_mw") / 1000.0;
                    emeterSupported = Boolean.TRUE;
                } else {
                    emeterSupported = Boolean.FALSE;
                }
            }

            // 3. plug state every 10th poll (and the first)
            if (pollNo % 10 == 1) {
                JSONObject di = result(klap.call("get_device_info", null));
                State.plugOn = di.optBoolean("device_on", false) ? "ON" : "OFF";
                State.rssi = di.optInt("rssi", 0);
                State.model = di.optString("model", "P110");
                String nick = di.optString("nickname", "");
                try {
                    State.plugName = new String(Base64.decode(nick, Base64.DEFAULT), "UTF-8");
                } catch (Exception e) {
                    State.plugName = nick;
                }
            }

            // 4. update screen state
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!day.equals(peakDay)) {
                peakDay = day;
                State.peakTodayW = Double.NaN;
            }
            if (!Double.isNaN(powerW) && (Double.isNaN(State.peakTodayW) || powerW > State.peakTodayW))
                State.peakTodayW = powerW;
            State.powerW = powerW;
            State.voltageV = volts;
            State.currentA = amps;
            State.todayWh = todayWh;
            State.monthWh = monthWh;
            if (!Double.isNaN(powerW)) State.addHistory((float) powerW);

            // 5. CSV row
            writeRow(day, powerW, volts, amps, todayWh, monthWh);
            State.samples++;
            State.lastOk = System.currentTimeMillis();
            State.error = false;
            State.status = "Logging every " + State.intervalSec(this) + " s";
            notifyStatus(String.format(Locale.US, "%.1f W", powerW),
                    String.format(Locale.US, "Today %.2f kWh  ·  %s", todayWh / 1000.0,
                            new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())));
        } catch (Exception e) {
            Log.w(TAG, "poll failed", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException
                    || e instanceof java.net.NoRouteToHostException)
                msg = "Plug not reachable: check its IP and that this phone is on the same Wi-Fi";
            setError(msg);
            notifyStatus("Tapo Logger: problem", msg);
            if (klap != null) klap.reset();
        }
    }

    /** Live values in the notification bar, so you can check without opening the app. */
    private void notifyStatus(String title, String text) {
        try {
            if (note == null) return;
            note.setContentTitle(title).setContentText(text);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(1, note.getNotification());
        } catch (Exception ignored) {
        }
    }

    private static JSONObject result(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        int code = o.optInt("error_code", -1);
        if (code != 0) throw new Exception("Plug returned error_code " + code);
        return o.getJSONObject("result");
    }

    /** Folder holding the daily CSV logs (shared with the live graph screen). */
    public static File logDir(Context c) {
        File dir = c.getExternalFilesDir(null);
        return dir != null ? dir : c.getFilesDir();     // no SD card / storage unmounted
    }

    private void writeRow(String day, double p, double v, double a, double today, double month) throws Exception {
        File f = new File(logDir(this), "tapo_" + day + ".csv");
        boolean isNew = !f.exists();
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        StringBuilder sb = new StringBuilder();
        if (isNew) sb.append(HEADER);
        sb.append(time).append(',').append(num(p, 3)).append(',').append(num(v, 2)).append(',')
                .append(num(a, 3)).append(',').append(num(today, 0)).append(',').append(num(month, 0))
                .append(',').append(State.plugOn).append(',').append(State.rssi).append('\n');
        FileOutputStream os = new FileOutputStream(f, true);
        try {
            os.write(sb.toString().getBytes("UTF-8"));
        } finally {
            os.close();
        }
        State.logFile = f.getAbsolutePath();
    }

    private static String num(double d, int decimals) {
        return Double.isNaN(d) ? "" : String.format(Locale.US, "%." + decimals + "f", d);
    }

    private static void setError(String msg) {
        State.error = true;
        State.status = msg;
    }
}
