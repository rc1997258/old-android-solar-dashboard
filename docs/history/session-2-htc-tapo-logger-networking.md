# Session 2: second phone, Tapo Logger, networking, battery ramp design

Continuation of session 1, in the same chat. Your prompts are **verbatim**; Claude's replies are **condensed** to decisions and commands. Full details are in `docs/HANDOVER.md` and the files referenced.

---

## 1. A second phone

> I just found out, I have another android phone, that is HTC desire 816 dual sim

**Claude:** Two variants share the name: the 816 dual sim (Snapdragon 400, 1.5 GB) and the 816G (MediaTek, 1 GB). Either one removes the Mini's limits: Bluetooth 4.0 (BLE for the BMS), reported USB OTG (maybe the Victron VE.Direct cable), and a 720×1280 16:9 screen (the original Soyosource app should fit). Suggested two roles: main display or hub.

> from Setting the phone name is - "HTC Desire 816 Dual sim", running currently stock android OS, with version 6.0

**Claude:** On stock Android 6, ADB over Wi-Fi needs `adb tcpip 5555` once over USB (from Windows Platform-Tools; WSL can't see USB easily). Then: install the original `com.soysource-1.apk` as a test; test BLE with the BMS app (Location must be on); test OTG with a flash drive. Tips: screen pinning plus Stay awake; check batteries for swelling under 24/7 charge.

> I do not want to throw away the samsung GT-S5570I, can you please tell me the best setup to use both of these mobiles for my projects?

**Claude:** Both phones as **displays**, with an **ESP32 hub (ESPHome)** doing the hardware talking: `syssi/esphome-jbd-bms` (BLE), `syssi/esphome-soyosource-gtn-virtual-meter` (GTN Wi-Fi version via its TTL-WIFI port; back up the stick firmware before reflashing), and a VE.Direct UART component. The HTC is the main dashboard and the Mini a small secondary display. Phones make poor hubs: vendor apps lock away BLE data, and Android kills background work. Until then, the Mini stays the inverter display and the HTC tests BLE.

## 2. Tapo Logger v1

> I have a TP link Tapo P110 smart wifi energy meter. Create a android app for Samsung GT-S5570I to connect to this meter, and log the information

**Claude:** Checked the current P110 situation: since firmware 1.4, local access needs **Third-Party Compatibility ON** (otherwise TPAP, with a 403 for KLAP clients). Implemented **KLAP v2** in pure Java (`Klap.java`), checked against python-kasa's implementation. Built a **simulated P110** from python-kasa's own crypto, validated it with python-kasa's real client, and passed all protocol tests (login, multiple requests, unsupported method, re-handshake, wrong password, 403, offline). The auth hash matched python-kasa byte for byte. Built the app without Gradle (aapt, javac, dx, zipalign, apksigner): a foreground logging service, daily CSV, live screen, settings storing only the login hash, start on boot. Delivered `TapoLogger.apk` (29 KB), its README, and the source.

## 3. Getting the plug and phone on one network

> I currently have Tapo P110 connected to my other phones wifi hotspot, how do I tell this to connect directly to my Samsung GT-S5570I phone?

**Claude:** Make the Mini a hotspot with the **same name, password and WPA2-PSK** as the old one (the plug moves without a reset). Turn on Third-Party Compatibility first, while the plug still has internet. Find the plug with `cat /proc/net/arp`. Caveat: while it's a hotspot, the Mini can't reach the Soyosource stick.

> but I do not want to loose the connection to soyosource too!, tell me a alternate way to keep both these devices connected? soyosource and TP P110

**Claude:** (1) Split jobs: the HTC logs the plug, the Mini stays on the stick. (2) Everything on the Mini's hotspot, with the stick joined in client mode (untested whether it still answers on port 10000). Recommended (1).

> Make the Samsung phone to turn on wifi receiver and hotspot at the same time!

**Claude:** Not reliably possible: Android 4.4 has no STA+AP support (it arrived in Android 11 on capable hardware), driver support is unknown, and it would force both onto one channel. Alternatives: the HTC as the shared hotspot with the Mini as a client, or split jobs. Offered read-only driver checks.

> The CM11 in samsung phone is unable to turn wifi hotspot on, what can I do?

**Claude:** The plans above don't need the Mini's hotspot. For diagnosis: quick checks, then capture `logcat` on the phone itself (ADB drops when the hotspot starts), and a table of likely causes. Take a CWM backup before changing system files.

> Yes, I had airplane mode turned on!, now hotspot is turned on! But I am not able to see the list of connected devices!, it is showing none

**Claude:** The list may not work on this ROM. Test with the HTC and `cat /proc/net/arp`. For the plug: turn off the old hotspot, make the name and password match exactly, power-cycle the plug, wait 1–2 min. `su -c cat /data/misc/dhcp/dnsmasq.leases` shows names.

> yes!, I can see 3 devices output with the 0x2 flag

**Claude:** Match the MAC addresses (the plug's in Tapo app → Device Info; the HTC's in Settings). The third is probably the other phone, which auto-joined. Enter the plug's IP in Tapo Logger.

> Awesome!, The app is working great! Now, I want my original wifi hotspot to remain the same name and password, so that all other devices get internet access. What to do next?

**Claude:** Switch the Mini's hotspot off, turn the original back on, and join the Mini to it as a client. Update the plug's new IP in Tapo Logger. If the plug is unreachable, that hotspot isolates clients, and a SIM with data in the Mini would be the fallback. Logging then depends on the other phone staying home.

> I want the plug to connect to a different wifi name now, what to do?

**Claude:** Plugs can't switch Wi-Fi in place (TP-Link FAQ). Either match the old name and password, or reset (Remove Device, or hold the button ~5 s), set it up again on a network **with internet**, re-toggle Third-Party Compatibility, and update the IP. A reset loses the schedules, name and possibly the Tapo app history; the CSV logs on the phone are safe.

## 4. Tapo Logger v2: history and Tapo-style look

> Got it! Improve the app to also show the historical data similar to tapo app, like previous months, and years data, make the colour /theme of the app like the tapo android app

**Claude:** Checked `get_energy_data` in the `tapo` library: hourly (interval 60, ≤ 8 days), daily (1440, a quarter starting on its first day), monthly (43200, a year starting 1 Jan). Wrote `History.java` (pure Java) with a cache of finished periods and offline fallback. Extended the mock and passed all history tests (including February and quarter offsets, future periods hidden, cache, no-data error, 7/30-day totals). New screens: a Tapo-style device page and **Energy Usage History** (Today / 7 / 30 days, Day / Month / Year bars, ‹ ›, tap-to-read). Same signing key, so it updates in place.

## 5. Laptop, hotspot and ADB

> Can I connect my laptop to Samsung wifi hotspot, while still keeping bluetooth tethring active?

> No, I meant internet access to my laptop, currently my laptop is getting internet over bluetooth from another phone, and wifi is turned off. I want to connect to Samsung's wifi hotspot, will my laptop still get internet, and I can access samsungs adb over wifi?

**Claude:** Yes. 192.168.43.x goes over Wi-Fi and the rest over Bluetooth. If Windows prefers Wi-Fi for internet, set interface metrics (Bluetooth 10, Wi-Fi 100). ADB: `adb connect 192.168.43.1:5555`.

> Awesome!, Bluetooth internet was active in my laptop, I had to do nothing!

## 6. Tapo Logger v3: Live Power Graph

> This is great!, The new app is really really good!!, I am able to see all the details! One more change request, the current live graph is too basic, please improve it with the below requirements - 1) It should be in other tab/menu. 2) When I click on the live graph tab, then that should show along with time and date and it should be scrollalbe and zoomable

**Claude:** A new **Live Power Graph** screen built from the phone's CSV logs: time axis (HH:mm or HH:mm:ss), full date in the readout, drag to scroll, pinch and − / + to zoom (1 min to 24 h; buttons in case the Mini lacks multi-touch), tap to read, Fit, Now (live follow), day navigation, and min/avg/max of the visible range. `GraphData.java` (pure Java) passed its tests. CSV parsing sped up about 3.5× for the old CPU.

## 7. Screen off

> adb command to tell my phone to keep its hotspot on even when screen in turned off

**Claude:** `settings put global wifi_sleep_policy 2`, `stay_on_while_plugged_in 3`, and optionally a root wake lock via `/sys/power/wake_lock`.

> Also, I want the app to continue to work when I turn off the screen or lock the phone

**Claude:** Logging already ran in a foreground service with wake and Wi-Fi locks. **v3.1** adds: the Live Graph re-reads the log after screen-off (no gaps, keeps zoom, handles midnight), a **watchdog** alarm every ~5 min that restarts logging if it was killed, and a **live notification** with watts and today's kWh.

## 8. Battery power ramp (design)

> About the soyosource App, can you further improve it to do the following? - 1) Currently the inverter start and stop voltage has only absolute values to it. I think it is coded into the wifi stick like that. So, I want to implement an algorithm within the App itself, so that, when the live battery or input voltage is decreasing to around 26 volts, the inverter's max power output should also be ramped down, to extract as much as energy as possible from the lifepo4 battery bank, without the BMS shutting it down.

**Claude:** Decoded the reply layout: DC voltage is bytes 4–5 × 0.1 V, every 3 s. Proposed a 1-minute averaged voltage curve (≥ 26.4 V full; ramp to 40% by 25.6 V; to a minimum by 25.0 V), asymmetric hysteresis, 50 W steps, and write rate limits (the settings are probably saved to permanent memory), with the inverter's own shutdown voltage as the hard floor above the BMS cut-off. Long term: Limiter mode over RS485 with the BMS charge %. **Waiting for:** `窗口14.smali`, cell count and BMS cut-off, current settings, inverter model. Design: `docs/battery-ramp-controller.md`.

## 9. This repository

> Give me all these details everything!, to create a new github repo!

**Claude:** Assembled this repository: README, MIT licence, `.gitignore` (no APKs, keystores, or vendor files), the master handover v3, protocol, network and design docs, both session histories (with the laptop name and private IP removed), the Soyosource patcher, the Tapo Logger source with a test runner (35 tests passing), and a GitHub Actions workflow that runs the tests and builds the APK.
