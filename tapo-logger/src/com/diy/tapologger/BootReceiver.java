package com.diy.tapologger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * - After a reboot: starts logging if "Start logging when the phone boots" is ticked.
 * - Watchdog alarm (every ~5 min): restarts logging if Android killed it while it should run.
 */
public class BootReceiver extends BroadcastReceiver {

    static final String WATCHDOG = "com.diy.tapologger.WATCHDOG";

    @Override
    public void onReceive(Context c, Intent i) {
        if (!State.configured(c)) return;
        if (WATCHDOG.equals(i.getAction())) {
            if (State.prefs(c).getBoolean(State.K_RUNNING, false) && !State.running)
                c.startService(new Intent(c, LoggerService.class));
        } else if (State.prefs(c).getBoolean(State.K_BOOT, false)) {
            LoggerService.start(c);
        }
    }
}
