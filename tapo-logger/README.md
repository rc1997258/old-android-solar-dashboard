# Tapo Logger: TP-Link Tapo P110 energy logger for old Android phones

A tiny (29 KB) Android app that reads a **Tapo P110** smart plug directly over your Wi-Fi (no cloud) and logs its readings to a CSV file every few seconds. Built for the **Samsung Galaxy Mini GT-S5570I** (CyanogenMod 11 / Android 4.4, 240×320, ARMv6). It also runs on newer phones such as the HTC Desire 816 (Android 6).

- **Device page (Tapo-style):** current power, voltage/current/peak, Today and This Month cards with runtime, plug on/off and Wi-Fi signal.
- **Live Power Graph (v3):** its own screen with a real time and date axis. Drag to scroll, pinch or − / + to zoom (from 1 minute up to a whole day), tap for the exact time and watts, and browse earlier days. While you view today, it follows each new reading live.
- **Energy Usage History (v2):** Today / Past 7 Days / Past 30 Days totals, plus **Day** (hourly), **Month** (daily) and **Year** (monthly) bar charts. Use ‹ › to go back to earlier days, months and years, and tap any bar to read its value. The data comes from the plug's own memory, so it covers the time before you installed this app too.
- **Logging:** runs as a background service with the screen on, off or locked, and writes one CSV file per day. A watchdog checks every ~5 minutes and restarts logging if Android killed it. The notification bar shows the latest watts and today's kWh without opening the app.
- **Style:** in the style of the Tapo app: light grey page, white rounded cards, teal accent. Sizes scale with the screen width, from 240 px (Galaxy Mini) upwards.
- **Privacy:** talks only to the plug on your LAN. Your password is never stored; only the hash the plug needs is saved on the phone.

---

## 1. Prepare the plug (once)

1. In the **Tapo app**, open **Me → Third-Party Services → Third-Party Compatibility** and switch it **ON**. In older Tapo app versions it was under "Tapo Lab", where toggling it has no effect, so update the Tapo app first if that's what you see.
   Since firmware 1.4.0, the P110 only talks to non-TP-Link apps while this switch is on. Otherwise it answers HTTP 403. TP-Link notes that enabling it may reduce device security, and the default is off.
2. Find the plug's IP address: Tapo app → the plug → ⚙ → **Device Info**. Ideally, reserve that IP in your router (DHCP reservation) so it never changes.
3. The phone must be on the **same Wi-Fi network** as the plug.

## 2. Install (or update)

From your Rocky Linux WSL, with the phone connected over ADB:

```bash
adb connect <phone-ip>:5555
adb install TapoLogger.apk
```

To update from an earlier version, use `adb install -r TapoLogger.apk`. Your settings and logs are kept.

You can also copy `TapoLogger.apk` to the phone and open it there, which needs "Unknown sources" allowed in Settings → Security.

## 3. Use

1. The first launch opens **Settings**. Enter:
   - **Plug IP**, e.g. `192.168.1.50`
   - **Tapo account e-mail and password**, the same login as the Tapo app. Both are **case-sensitive**.
   - **Log every N seconds**: default 10, minimum 2
   - **Keep screen on** (default on) and **Start logging when the phone boots** (optional)
2. Tap **Save**. Logging starts automatically.
3. The main screen updates every second. **Start/Stop** controls logging. The status line is green when logging and red with a reason when something is wrong.

If logging was running when the app closed or the phone rebooted, it resumes the next time you open the app. With the boot option ticked, it resumes even without opening it.

## 4. Live Power Graph

Tap **Live Power Graph** on the main screen.

| Action | What it does |
|---|---|
| Drag left / right | Scroll through time |
| Pinch, or **−** / **+** | Zoom out / in, from 1 minute up to 24 hours. The buttons are there because the Galaxy Mini's touchscreen may not support pinch. |
| Tap | Show that point's exact watts, date and time, with a marker |
| Double-tap or **Fit** | Show the whole day's logged readings |
| **Now** | Jump back to the latest reading and follow live (**● LIVE** in the header) |
| ‹ › next to the date | Previous / next day |

- The x-axis shows clock times (HH:mm, or HH:mm:ss when zoomed in far). The readout shows the full date and time.
- Below the chart: the visible time range, and the **min / average / max** watts in that range.
- Today opens on the last 30 minutes, following live. Dragging back to the right edge resumes following.
- The graph is drawn from this phone's CSV logs, so it shows the periods when Tapo Logger was logging, with gaps where it wasn't. Long-term totals from the plug's own memory are under **Energy Usage History**.
- It's built for old phones: a full day (8,640 readings at 10 s) loads in the background, and only the visible part is drawn, thinned to about two points per pixel.

## 5. Energy history

Tap **Energy Usage History** on the main screen.

| Tab | Bars | ‹ › moves by | Plug request |
|---|---|---|---|
| Day | 24 hourly bars | one day | `get_energy_data`, interval 60 |
| Month | one bar per day | one month | interval 1440 (a whole quarter per request) |
| Year | 12 monthly bars | one year | interval 43200 |

- Above the chart: the period's **total**, the **average** per hour/day/month, and the highest bar. Tap or drag across the bars to read each one.
- **How far back:** as far as your plug's memory goes. Earlier periods show *"Plug has no history for this request"*.
- **Offline:** finished periods (yesterday, last month, last year…) are saved on the phone after the first view, so they still show when the plug is unreachable. The current period falls back to its last saved copy, with a note saying so.
- The history screen shares the logger's single connection to the plug, so the two never fight over it.

## 6. The log files

Location (shown at the bottom of the main screen):

```
/sdcard/Android/data/com.diy.tapologger/files/tapo_YYYY-MM-DD.csv
```

On some phones the path starts with `/storage/emulated/0/` instead of `/sdcard/`.

Format, one row per reading:

```
time,power_w,voltage_v,current_a,today_wh,month_wh,plug_on,rssi
2026-10-12 17:43:00,87.660,231.40,0.379,1234,45678,ON,-52
```

| Column | Meaning |
|---|---|
| `power_w` | Instantaneous power in watts (from the plug's milliwatt reading) |
| `voltage_v`, `current_a` | Only if your P110 firmware supports `get_emeter_data`; otherwise blank |
| `today_wh`, `month_wh` | The plug's own energy counters in Wh |
| `plug_on`, `rssi` | Relay state and the plug's Wi-Fi signal, refreshed every 10th reading |

Copy all the logs to your laptop:

```bash
adb pull /sdcard/Android/data/com.diy.tapologger/files/ ./tapo_logs/
```

At 10-second intervals a day is about 8,640 rows, roughly 500 KB, so storage isn't a concern.

> **Note:** uninstalling the app deletes this folder, which is Android's rule for app folders. Pull your logs first.

## 7. Troubleshooting

| Status line says | Fix |
|---|---|
| *Plug refused login (HTTP 403)… Third-Party Compatibility* | Turn the switch ON (step 1). If it's already on: switch it off, wait a minute, switch it on, wait a minute. Updating the Tapo app or a factory reset of the plug are the last resorts. |
| *Wrong Tapo e-mail or password* | Re-enter both in Settings, exactly as in the Tapo app (case matters). |
| *Plug not reachable…* | Wrong IP, the plug got a new IP, or the phone is on a different network. Check the IP in the Tapo app. |
| *Plug does not speak KLAP (HTTP 404)* | Very old firmware: update the plug in the Tapo app. |
| *Plug returned error_code …* | Note the number and report it, so the request can be adjusted for your firmware. |
| Voltage/Current show `n/a` | Your firmware doesn't offer them. Power and kWh still log. |

For detailed errors, run: `adb logcat -s TapoLogger`

**Screen off / locked:** logging continues. The app's screens pause while the display is off, which is normal on Android, and catch up when you turn it back on. The Live Graph re-reads the log, so there's no gap. If you want the screen to turn off by itself, untick *Keep screen on in this app* in Settings.

**Long-term logging tips:** keep the phone on its charger. In Developer options, enable **Stay awake** for a display that's always on. On Android 6+ phones, exclude the app from battery optimisation (Settings → Battery → ⋮ → Battery optimisation → All apps → Tapo Logger → Don't optimise).

## 8. Build it yourself

No Android Studio or Gradle needed. Use the same toolchain as the Soyosource project:

```bash
~/android-sdk/cmdline-tools/bin/sdkmanager --sdk_root=$HOME/android-sdk "platforms;android-23"   # once
cd TapoLogger && ./build.sh
```

`build.sh` signs with `~/apkwork/my.keystore` if it exists, otherwise with a generated `debug.keystore`. The supplied `TapoLogger.apk` is debug-signed. If you later install your own build signed with a different key, run `adb uninstall com.diy.tapologger` first (pull your logs before you do).

### Source layout

| File | Role |
|---|---|
| `Klap.java` | Tapo local protocol (KLAP v2): login handshake, AES encryption, request signing. Pure Java. |
| `LoggerService.java` | Background polling, CSV writing, wake/Wi-Fi locks |
| `MainActivity.java` | Device page: live power, Today/Month cards, logging |
| `LiveGraphActivity.java` | Scrollable, zoomable time-axis graph with live follow |
| `GraphData.java` | CSV loading, time-axis ticks, zoom limits, lookups. Pure Java. |
| `HistoryActivity.java` | Day/Month/Year bar charts, summaries, tap-to-read |
| `History.java` | Plug history requests, date maths, caching. Pure Java. |
| `Ui.java` | Tapo-style colours, rounded cards, buttons |
| `SettingsActivity.java` | IP, login (hashed), interval, options |
| `State.java` | Settings plus live values shared by the service and screens |
| `BootReceiver.java` | Optional start on boot |
| `test/mock_p110.py`, `test/KlapTest.java`, `test/HistoryTest.java`, `test/GraphTest.java` | Simulated P110 (built on python-kasa's own crypto, including `get_energy_data`), plus protocol and history tests |

### Run the tests

```bash
pip install python-kasa          # used by the simulated plug
tapo-logger/test/run_tests.sh    # 35 tests: protocol, history, graph
```

GitHub Actions runs the same tests on every push and builds a CI-signed APK (see `.github/workflows/ci.yml`).

### How it was tested

There was no real plug available while building this. Instead:

1. A simulated P110 was written that uses **python-kasa's own KLAP crypto classes** and checks request signatures the way the plug does.
2. That simulator was verified with **python-kasa's real client**.
3. `History.java` passed tests for hourly/daily/monthly bars, month positions inside quarters (including February), hidden future periods, cache use when the plug is offline, a clear error when the plug has no data, and past-7/30-day totals across quarter boundaries.
4. `GraphData.java` passed tests for loading a full day of logs (with blank, broken and out-of-order rows), point lookups, min/avg/max, live appends, time-axis steps from 24 h down to 1 minute, and zoom/scroll limits.
5. `Klap.java` passed all tests against it: login, several requests per session, unsupported methods, re-login after a reset, wrong password, 403 when Third-Party Compatibility is off, and plug offline. Its credential hash matches python-kasa's byte for byte.

The Android screens and service were compiled against the Android SDK and packaged, but **not run on a phone yet**. Your first launch is the real-device test, so please report any status message or crash (`adb logcat -s TapoLogger AndroidRuntime`).

### Protocol notes

- Plugs on recent firmware with Third-Party Compatibility **off** speak **TPAP** (a SPAKE2+-based login) instead of KLAP. This app doesn't implement TPAP, so the switch must stay **on**.
- Requests used: `get_energy_usage` (power in mW, today/month Wh and runtime), `get_emeter_data` (mV/mA/mW, when supported), `get_device_info` (on/off, RSSI, base64 nickname), `get_energy_data` (history, Wh per hour/day/month).
- The protocol is reverse-engineered by the open-source community (python-kasa, tapo). TP-Link doesn't officially support third-party local access, so a future firmware update could change it.
