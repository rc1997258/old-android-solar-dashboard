package com.diy.tapologger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Device page in the style of the Tapo app: live power, today / month cards, history, logging. */
public class MainActivity extends Activity {

    private final Handler ui = new Handler();
    private TextView name, sub, power, powerUnit, vi, today, todayRun, month, monthRun, status, detail, startStop;

    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
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

        // ---- header: plug name + state, settings button
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        name = Ui.text(this, "Tapo P110", Ui.title(), Ui.TEXT, true);
        sub = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        names.addView(name);
        names.addView(sub);
        head.addView(names, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView settings = Ui.button(this, "Settings", false);
        settings.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.small());
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openSettings(); }
        });
        head.addView(settings);
        col.addView(head, Ui.gap(-1, 0));

        // ---- current power card
        LinearLayout pc = Ui.card(this);
        pc.addView(Ui.text(this, "Current Power", Ui.small(), Ui.SUB, false));
        LinearLayout pr = new LinearLayout(this);
        pr.setGravity(Gravity.BOTTOM);
        power = Ui.text(this, "---", Ui.big(), Ui.TEAL, true);
        powerUnit = Ui.text(this, " W", Ui.title(), Ui.TEAL, true);
        powerUnit.setPadding(0, 0, 0, Ui.w / 40);
        pr.addView(power);
        pr.addView(powerUnit);
        pc.addView(pr);
        vi = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        pc.addView(vi);
        col.addView(pc, Ui.gap(-1, 0));

        // ---- today / this month cards side by side
        LinearLayout two = new LinearLayout(this);
        LinearLayout tc = Ui.card(this), mc = Ui.card(this);
        tc.addView(Ui.text(this, "Today", Ui.small(), Ui.SUB, false));
        today = Ui.text(this, "---", Ui.title(), Ui.TEXT, true);
        tc.addView(today);
        todayRun = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        tc.addView(todayRun);
        mc.addView(Ui.text(this, "This Month", Ui.small(), Ui.SUB, false));
        month = Ui.text(this, "---", Ui.title(), Ui.TEXT, true);
        mc.addView(month);
        monthRun = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        mc.addView(monthRun);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1f);
        left.rightMargin = p / 2;
        two.addView(tc, left);
        two.addView(mc, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(two, Ui.gap(-1, 0));

        // ---- live graph entry
        col.addView(entry("Live Power Graph", "Time & date axis  ·  scroll  ·  zoom", LiveGraphActivity.class), Ui.gap(-1, 0));

        // ---- energy history entry
        LinearLayout hc = Ui.card(this);
        LinearLayout hr = new LinearLayout(this);
        hr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout ht = new LinearLayout(this);
        ht.setOrientation(LinearLayout.VERTICAL);
        ht.addView(Ui.text(this, "Energy Usage History", Ui.body(), Ui.TEXT, true));
        ht.addView(Ui.text(this, "Day  ·  Month  ·  Year", Ui.small(), Ui.SUB, false));
        hr.addView(ht, new LinearLayout.LayoutParams(0, -2, 1f));
        hr.addView(Ui.text(this, "›", Ui.title(), Ui.SUB, true));
        hc.addView(hr);
        hc.setClickable(true);
        hc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, HistoryActivity.class));
            }
        });
        col.addView(hc, Ui.gap(-1, 0));

        // ---- logging card
        LinearLayout lc = Ui.card(this);
        lc.addView(Ui.text(this, "Logging", Ui.small(), Ui.SUB, false));
        status = Ui.text(this, "", Ui.body(), Ui.TEXT, true);
        lc.addView(status);
        detail = Ui.text(this, "", Ui.small(), Ui.SUB, false);
        lc.addView(detail);
        startStop = Ui.button(this, "Start logging", true);
        startStop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (State.running) LoggerService.stop(MainActivity.this);
                else if (!State.configured(MainActivity.this)) openSettings();
                else LoggerService.start(MainActivity.this);
                refresh();
            }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
        bl.topMargin = p / 2;
        lc.addView(startStop, bl);
        col.addView(lc, Ui.gap(-1, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.PAGE);
        scroll.addView(col);
        setContentView(scroll);

        if (!State.configured(this)) openSettings();
        else if (State.prefs(this).getBoolean(State.K_RUNNING, false) && !State.running)
            LoggerService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (State.prefs(this).getBoolean(State.K_SCREEN_ON, true))
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    /** Tappable card that opens another screen. */
    private LinearLayout entry(String title, String subtitle, final Class<?> target) {
        LinearLayout c = Ui.card(this);
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.addView(Ui.text(this, title, Ui.body(), Ui.TEXT, true));
        t.addView(Ui.text(this, subtitle, Ui.small(), Ui.SUB, false));
        r.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        r.addView(Ui.text(this, "›", Ui.title(), Ui.SUB, true));
        c.addView(r);
        c.setClickable(true);
        c.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, target));
            }
        });
        return c;
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    private void refresh() {
        boolean stale = State.lastOk == 0
                || System.currentTimeMillis() - State.lastOk > 3000L * State.intervalSec(this) + 5000;
        name.setText(State.plugName.length() > 0 ? State.plugName : "Tapo P110");
        String s = State.model;
        if (!"?".equals(State.plugOn)) s += "  ·  " + (State.plugOn.equals("ON") ? "On" : "Off");
        if (State.rssi != 0) s += "  ·  Wi-Fi " + State.rssi + " dBm";
        sub.setText(s);

        power.setText(Double.isNaN(State.powerW) ? "---" : String.format(Locale.US, "%.1f", State.powerW));
        power.setTextColor(stale ? Ui.SUB : Ui.TEAL);
        powerUnit.setTextColor(stale ? Ui.SUB : Ui.TEAL);
        String v = "";
        if (!Double.isNaN(State.voltageV)) v += String.format(Locale.US, "%.1f V", State.voltageV);
        if (!Double.isNaN(State.currentA)) v += String.format(Locale.US, "   %.3f A", State.currentA);
        if (!Double.isNaN(State.peakTodayW)) v += String.format(Locale.US, "   Peak %.0f W", State.peakTodayW);
        vi.setText(v);

        today.setText(Ui.kwh(State.todayWh) + " kWh");
        todayRun.setText(State.todayRuntimeMin >= 0 ? Ui.runtime(State.todayRuntimeMin) : "");
        month.setText(Ui.kwh(State.monthWh) + " kWh");
        monthRun.setText(State.monthRuntimeMin >= 0 ? Ui.runtime(State.monthRuntimeMin) : "");

        status.setText(State.status);
        status.setTextColor(State.error ? Ui.RED : (State.running ? Ui.TEAL_DARK : Ui.SUB));
        String d = "";
        if (State.lastOk > 0)
            d += "Updated " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(State.lastOk))
                    + "   ·   " + State.samples + " rows";
        if (State.logFile.length() > 0) d += "\n" + State.logFile;
        detail.setText(d);
        startStop.setText(State.running ? "Stop logging" : "Start logging");
    }

}
