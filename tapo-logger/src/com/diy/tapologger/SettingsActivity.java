package com.diy.tapologger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Plug IP, Tapo account, poll interval and options.
 * The password is never saved: only sha256(sha1(email)+sha1(password)) is kept,
 * which is all the KLAP login needs.
 */
public class SettingsActivity extends Activity {

    private int w;
    private EditText host, email, password, interval;
    private CheckBox screenOn, boot;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        w = getResources().getDisplayMetrics().widthPixels;
        Ui.init(this);
        int pad = w / 30;
        SharedPreferences p = State.prefs(this);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.PAGE);
        col.setPadding(pad, pad, pad, pad);

        col.addView(label("Settings", w / 15f, true));

        col.addView(label("Plug IP address (see Tapo app > plug > Device Info)", w / 22f, false));
        host = field(p.getString(State.K_HOST, ""), "192.168.1.50", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(host);

        col.addView(label("Tapo account e-mail (case-sensitive)", w / 22f, false));
        email = field(p.getString(State.K_EMAIL, ""), "you@example.com",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        col.addView(email);

        boolean hasHash = p.getString(State.K_HASH, "").length() == 64;
        col.addView(label("Tapo account password" + (hasHash ? " (leave empty to keep)" : ""), w / 22f, false));
        password = field("", hasHash ? "unchanged" : "password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        col.addView(password);

        col.addView(label("Log every N seconds (min 2)", w / 22f, false));
        interval = field(String.valueOf(State.intervalSec(this)), "10", InputType.TYPE_CLASS_NUMBER);
        col.addView(interval);

        screenOn = check("Keep screen on in this app", p.getBoolean(State.K_SCREEN_ON, true));
        col.addView(screenOn);
        boot = check("Start logging when the phone boots", p.getBoolean(State.K_BOOT, false));
        col.addView(boot);

        TextView save = Ui.button(this, "Save", true);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { save(); }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
        sl.topMargin = w / 30;
        sl.bottomMargin = w / 30;
        col.addView(save, sl);

        col.addView(label("Before first use: in the Tapo app open Me > Third-Party Services and turn ON "
                + "Third-Party Compatibility. Without it the plug refuses local apps (HTTP 403).\n\n"
                + "Only a hash of your login is stored on this phone, never the password.", w / 24f, false));

        ScrollView s = new ScrollView(this);
        s.setBackgroundColor(Ui.PAGE);
        s.addView(col);
        setContentView(s);
    }

    private void save() {
        SharedPreferences p = State.prefs(this);
        String h = host.getText().toString().trim();
        String e = email.getText().toString().trim();
        String pw = password.getText().toString();
        if (h.length() == 0) { toast("Enter the plug's IP address"); return; }
        if (e.length() == 0) { toast("Enter your Tapo e-mail"); return; }

        String hash = p.getString(State.K_HASH, "");
        boolean emailChanged = !e.equals(p.getString(State.K_EMAIL, ""));
        if (pw.length() > 0) hash = Klap.hex(Klap.authHash(e, pw));
        else if (hash.length() != 64 || emailChanged) { toast("Enter your Tapo password"); return; }

        int sec = 10;
        try { sec = Math.max(2, Integer.parseInt(interval.getText().toString().trim())); } catch (Exception ignored) {}

        p.edit().putString(State.K_HOST, h).putString(State.K_EMAIL, e).putString(State.K_HASH, hash)
                .putInt(State.K_INTERVAL, sec).putBoolean(State.K_SCREEN_ON, screenOn.isChecked())
                .putBoolean(State.K_BOOT, boot.isChecked()).commit();

        // the service picks up a changed IP/login on its next poll
        if (!State.running) LoggerService.start(this);
        toast("Saved");
        finish();
    }

    // ------------------------------------------------------------------ widgets

    private TextView label(String s, float px, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(bold ? Ui.TEXT : Ui.SUB);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, w / 40, 0, 0);
        return t;
    }

    private EditText field(String value, String hint, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, w / 17f);
        return e;
    }

    private CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setChecked(on);
        c.setTextColor(Ui.TEXT);
        c.setTextSize(TypedValue.COMPLEX_UNIT_PX, w / 20f);
        return c;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
