# SYSTEM HANDOVER v3 — OLD-ANDROID SOLAR DASHBOARD

**Handover date:** 23 October 2026
**Supersedes:** v2 (8 Oct 2026, Soyosource dashboard only) and v1 (CM11 flashing phase)

**Project status:**

| Part | Status |
|---|---|
| Soyosource inverter dashboard (patched vendor app, Galaxy Mini) | ✅ Working |
| Tapo Logger (own app: P110 energy logging, history, live graph) | ✅ v2 confirmed on the Galaxy Mini with a real P110; v3 / v3.1 built and tested here, awaiting your confirmation on the phone |
| Battery-voltage power ramp for the inverter | 📝 Designed (`docs/battery-ramp-controller.md`), waiting for `窗口14.smali` |
| ESP32 hub combining BMS, Victron and inverter | 💡 Planned |

**Parts A–C** of this document (Sections 0–14) are the Soyosource dashboard, carried over from v2 and lightly updated. **Parts D–G** (Sections 15–19) are new: Tapo Logger, networking, the second phone, and the roadmap.

---

## 0. HOW TO USE THIS FILE

To continue in a new chat (Claude chat or Claude Code), paste or attach this file, plus the source files the task touches. In Claude Code, simply open the repository. A ready-made resume prompt is in **Section 19**.

Where things live in this repository:

| Path | Purpose |
|---|---|
| `docs/HANDOVER.md` | This document |
| `docs/soyosource-protocol.md` | The inverter Wi-Fi stick's TCP protocol, as decoded from the app |
| `docs/tapo-p110-local-api.md` | The P110's local KLAP protocol and the requests Tapo Logger uses |
| `docs/networking.md` | Hotspots, ADB over Wi-Fi, keeping Wi-Fi awake, laptop with two connections |
| `docs/battery-ramp-controller.md` | Design for ramping inverter power down as the battery empties |
| `docs/history/` | Chronological records of the chat sessions |
| `soyosource-patcher/patch_soyo.py` | The patcher: fonts, stretch, scrolling, one-column layout, value colours, AP-only boot |
| `soyosource-patcher/build_install.sh` | Rebuild → zipalign → sign → adb install → launch |
| `soyosource-patcher/tools/scale_res.py` | Generic XML-dimension scaler. **Not used**: the app is E4A, with layouts built in code. |
| `tapo-logger/` | Tapo Logger app source, build script, tests and simulated plug |

---

## 1. PROJECT SUMMARY

**Goal:** Use an old Samsung Galaxy Mini as a permanent wall-mounted display for the DIY solar powerwall, starting with the Soyosource grid-tie inverter's Wi-Fi stick.

**What was achieved:**

1. Pulled the official Soyosource Android app (`com.soysource`) from the phone and decompiled it with apktool on Rocky Linux WSL.
2. Identified it as an **E4A (易安卓 / "Easy Android")** app. E4A is a Chinese visual-programming tool whose screens are built in code from fractions of the screen size.
3. Wrote `patch_soyo.py`, which edits the decompiled smali code so that the app:
   - skips the login/main screen and **boots straight into the English AP-mode dashboard** (`窗口13`, `192.168.4.1:10000`);
   - shows the 10 readings **one per row, full width** (instead of two cramped columns);
   - makes everything **2× taller** and **scrollable**;
   - draws values **solid (opaque) in brown `#C06000`** (the app drew them at ~40% opacity, causing faint text and scroll ghosting);
   - scales fonts.
4. Rebuilt, signed, and installed over ADB-over-Wi-Fi. Verified live inverter values on the phone.

---

## 2. DEVICE & OS (carried over from v1, still valid)

| Item | Value |
|---|---|
| Phone | Samsung Galaxy Mini / Pop Plus **GT-S5570I**, codename **tassve** |
| SoC | Broadcom **BCM21553**, **ARMv6** (no ARMv7; rules out Xposed and most modern native libs) |
| Screen | **240 × 320 px, 4:3**, ldpi (~120 dpi) |
| OS | **CyanogenMod 11 (Android 4.4.4 KitKat)**, clean build, **no GApps** |
| Recovery | ClockworkMod 6.0.5.3 (permanent) |
| Usable RAM | ~**290 MB** |
| Bluetooth | 3.0 only, so **no BLE**. Cannot talk to the JSB Smart BMS directly. |
| USB | No usable OTG power. Cannot drive a VE.Direct-USB cable directly. |
| Root | CM11 built-in (Developer options → Root access) |
| ADB | **ADB over Wi-Fi**, port 5555. Reconnect with `adb connect <phone-ip>:5555` (on its own hotspot the phone is always `192.168.43.1`). |
| Dalvik notes | `VFY: unable to resolve …` lines in logcat are **harmless**. The app references Android 6–8 APIs (runtime permissions, overlays, status-bar colours) that KitKat lacks, and Dalvik stubs them out. |

### v1 items not touched in this session (still open)

- OS optimisation from v1 Section 4.1: remove `Apollo.apk`, `Calendar.apk`, `Email.apk`; animations off; background process limit 2; zRAM.
- Via Browser / Pydroid dashboard idea: superseded for the inverter by the patched app. Still relevant for a combined Victron/BMS/inverter dashboard (see Section 13).

---

## 3. WORKSTATION / TOOLCHAIN

| Item | Value |
|---|---|
| Host | Windows laptop, **Rocky Linux under WSL**, working as `root` (`[root@laptop …]`) |
| Project folder | `~/apkwork` (= `/root/apkwork`) |
| Java | `java-17-openjdk-headless` (dnf). Provides `java` and `keytool`. |
| apktool | **3.0.3**. Downloaded as `apktool_3.0.3.jar`, **renamed to `apktool.jar`**. |
| apktool alias | `alias apktool='java -jar /root/apkwork/apktool.jar'` (added to `~/.bashrc`) |
| Android SDK | Command-line tools unzipped to `~/android-sdk`; build-tools `34.0.0` installed via `sdkmanager --sdk_root=$HOME/android-sdk "build-tools;34.0.0"`. Provides `zipalign` and `apksigner`. |
| adb | `android-tools` (dnf, may need EPEL) or any adb on PATH |
| Python | python3 (for `patch_soyo.py`) |
| git | Install with `dnf install -y git` and set `user.name` / `user.email` before committing |
| Locale | Run `export LANG=C.UTF-8` before grepping smali, so the Chinese file and method names display properly |

### Folder layout

```
~/apkwork/
├── apktool.jar                     # apktool 3.0.3 (renamed)
├── com.soysource-1.apk             # ORIGINAL app pulled from the phone (keep safe!)
├── soyo_src/                       # apktool-decoded app (patched in place)
│   ├── AndroidManifest.xml  apktool.yml  assets/  res/  original/  unknown/
│   └── smali/com/
│       ├── soysource/              # the app's own code: 主窗口, 窗口1..14, APLauncher (ours)
│       └── e4a/runtime/            # E4A framework (we patch a few runtime methods)
├── patch_soyo.py                   # the patcher (re-runnable)
├── build_install.sh                # build + sign + install + launch
├── my.keystore                     # YOUR signing key (alias "me"). DO NOT LOSE.
├── soyo_mod_unsigned.apk           # build intermediate
└── soyo_mod.apk                    # final signed, installable APK
```

**About `my.keystore`:** Android only allows an update if it's signed with the same key. Without this file, any future change means uninstalling first, which wipes the app's saved settings.

---

## 4. THE APP: ANATOMY (what we learned)

### 4.1 Package facts

| Item | Value |
|---|---|
| Package | `com.soysource` |
| APK on phone | `/data/app/com.soysource-1.apk` (single APK; KitKat has no splits) |
| minSdk / targetSdk | 17 / 26 |
| Framework | **E4A** (fingerprint: `assets/file_info_android_e4a.db`) |
| Native libs | None (no `lib/` folder), so it runs on ARMv6 |
| `res/layout` | 61 files, all `abc_*` support-library leftovers. Real screens are built in smali. |

### 4.2 Bundled libraries (`smali/com/…`)

| Package | Role |
|---|---|
| `soysource` | The app's own screens and modules (37 files) |
| `e4a/runtime` | E4A framework: components, layout, system helpers |
| `espressif/iot`, `hiflying/smartlink`, `example/smartlinklib` | Wi-Fi provisioning (SmartConfig / SmartLink) |
| `mysql/jdbc` | Direct remote **MySQL** access (cloud mode) |
| `pgyersdk` | Update checker / crash reporter |
| `nostra13/universalimageloader`, `lidroid/xutils`, `lee/pullrefresh` | Images, HTTP, pull-to-refresh |
| E4A add-ons seen | `mqtt通讯` (MQTT), `锋哥_数据库操作` (DB), `WIFI管理`, `高级菜单` (menu), `对话框` (dialogs), `如意QQ对话框`, `EUI应用更新弹窗` (update popup), `n37/客户` (**TCP client**) |

### 4.3 Window map (decoded)

| Window | Purpose | Lang |
|---|---|---|
| `主窗口` (main) | **Login screen** (user/password), language picker (简体中文 / English), update popup, menu | — |
| `窗口1` / `窗口10` | Device list (cloud account) | CN / EN |
| `窗口2` / `窗口11` | Live dashboard, cloud/LAN mode | CN / EN |
| `窗口5` / `窗口12` | Battery settings for cloud dashboard | CN / EN |
| `窗口4` | Wi-Fi provisioning ("One-key config / AP config", 192.168.4.1) | both |
| `窗口8` / **`窗口13`** | **Live dashboard, AP mode, `192.168.4.1:10000`** | CN / **EN** |
| `窗口9` / `窗口14` | Battery settings for AP dashboard (Battery start V, delay, output W, shutdown V) | CN / EN |
| `窗口3` / `6` / `7` | Change password / register / reset password | CN |
| `公用模块` | Shared module (global flags such as `窗口14切换`) |
| `多线程操作1-6` | Background thread helpers |

The main window opens 窗口13 from its menu handler `高级菜单1$菜单被选择` (~line 4737 of `主窗口.smali`), using:
`读取窗口("窗口13")` → if null, `new 窗口13` + `保存窗口` → `切换窗口(window)`.

### 4.4 Key E4A runtime facts

- **Layout:** every component is placed with `左边` (left), `顶边` (top), `宽度` (width), and `高度` (height), each computed in `$define` as **fraction × `取屏幕宽度()`** or **fraction × (`取屏幕高度_不含导航栏()` − `取状态栏高度()`)**.
- **Fonts:** `字体大小(F)V` takes fixed sizes (14.0, 12.0, 11.0…) that don't scale with the screen. On a 4:3 screen, the boxes squash vertically but the text doesn't, which caused the original "weird text".
- **Scrolling:** `窗口Impl.可否滚动(Z)V` wraps the window in an Android `ScrollView`. It's built in; we just switch it on.
- **Window switching:** `应用操作.切换窗口(窗口)`; registry via `读取窗口(String)` / `保存窗口(String, 窗口Impl)`.
- **Events:** methods named `组件$事件`, e.g. `客户1$连接完毕(Z)V`, `窗口13$创建完毕()V`, `对话框1$输入框被单击(ZString)V`.
- **Component interfaces:** `n3/标签` = label, `n2/编辑框` = edit box, `n1/按钮` = button, `n4/图片框` = picture box, `n12/时钟` = timer, `n37/客户` = TCP client, `n29/高级列表框` = advanced list.
- **Useful setters:** `字体颜色(I)` font colour, `背景颜色(I)` background colour, `透明度(I)` opacity 0–255, `对齐方式(I)` alignment, `背景图片2(I)` background drawable, `可视(Z)` visible, `标题(String)` text.
- **Other runtime methods:** `弹出提示(String)` toast, `读写设置类.保存设置` saves a setting, `转换操作.十六进制到字节集` converts hex to bytes.

### 4.5 窗口13 (AP dashboard) in detail

**Original layout** (fractions; W = screen width, H = usable height):

| Row top | Left title | Left value | Right title | Right value |
|---|---|---|---|---|
| 0.138 | `标签16` DC Voltage: | `编辑框1` | `标签8` DC Current: | `编辑框6` |
| 0.233 | `标签4` AC Voltage: | `编辑框2` | `标签9` AC Frequency: | `编辑框7` |
| 0.327 | `标签5` Real Power: | `编辑框3` | `标签10` Temp: | `编辑框8` |
| 0.422 | `标签6` Total: | `编辑框4` | `标签17` Limiter: | `编辑框9` |
| 0.525 | `标签7` Work Mode: | `编辑框5` | `标签18` Status: | `编辑框10` |

- Left titles: L 0.038, W 0.231. Left values: L ≈0.263, W 0.266.
- Right titles: L 0.556, W 0.209. Right values: L 0.756, **W 0.203 (only ~49 px; why "Battery Limiter" was cut off)**.
- All rows are 0.062 tall.
- `标签1` is the title bar "SOYOSOURCE" (top). `图片框1` is the back arrow (`fht.png`, top-left). Clicking it switches to the main window.
- `标签3` is the white panel behind the readings (top 0.099, height 0.62).
- **Error row:** `标签11` "Error:" + `编辑框11` at top 0.629 (value box W 0.7).
- `标签14` is the panel behind the buttons (top 0.754, height 0.141). `按钮1` **Reboot** and `按钮3` **Settings** sit at top 0.791, L 0.125 / 0.625, W 0.281, H 0.077.
- `标签2` is at top 0.932 with width 0 (invisible).
- **Value boxes 编辑框1–10:** opacity **100/255**, background drawable `0x7f02004e`, individual colours:

  | Box | Colour |
  |---|---|
  | 编辑框1 | `#2BECD9` (cyan) |
  | 编辑框2 | `#F477C9` |
  | **编辑框3 (Real Power)** | **`#C06000` (brown)** |
  | 编辑框4 | `#81A9EB` |
  | 编辑框5 | `#0088FF` |
  | 编辑框6 | `#FCD6A3` |
  | 编辑框7 | `#FEB0A0` |
  | 编辑框8 | `#4351FC` |
  | 编辑框9 | `#B76FDD` |
  | 编辑框10 | `#80E000` |

  The Error box `编辑框11` is opacity 255. Nothing in the event handlers changes colours or opacity at runtime.

**Event flow:**

1. On entry, the address field `地址` is empty, so it defaults to `"192.168.4.1:10000"`. An input dialog ("Please connect to the WIFI AP and enter the IP address and port number") shows it prefilled.
2. `对话框1$输入框被单击(ok, text)`:
   - **OK** (Chinese button 确定): split on `:`, then `客户1.连接服务器(ip, port, 5000 ms)`.
   - **Cancel**: switch to the main window.
3. `客户1$连接完毕(success)`:
   - **true**: toast "Device connected successfully", send **`55 03 00 00 00 00 00 00 00 00 00 FC`** (model/spec query), set `规格` and `连接` = true, and save the setting `连接地址`.
   - **false**: dialog "Error / The device connection failed… / Reconnect / Cancel".
4. `对话框1$信息框2被单击(i)`: **Reconnect** (i = 0) shows the IP input again. **Cancel** switches to the main window.
5. `客户1$连接断开`: toast "Device offline" only. **No auto-reconnect.**
6. `时钟1` (timer): poll period `0xbb8` = **3000 ms**. When returning from Settings (`公用模块.窗口14切换`), it sends **`55 01 00 00 00 00 00 00 00 00 00 FE`** (the data poll request).
7. `客户1$收到数据([B)V` (smali lines ~5837–6884) parses replies into the boxes. **Now decoded:** see `docs/soyosource-protocol.md`.
8. `窗口13$按下某键` (key handler): Back switches to the main (login) window.
9. `按钮3$被单击` (Settings) switches to `窗口14`. `按钮1` = Reboot.

**Protocol summary (for future hub scripts):** plain **TCP to `192.168.4.1:10000`** (stick's own hotspot, AP mode). 12-byte frames starting `0x55`, with what looks like a one-byte checksum at the end (`01…FE`, `03…FC`; byte2 + last = 0xFF). Poll every ~3 s.

> **AP-mode caveat:** In AP mode the phone joins the **stick's hotspot**, so it isn't on the home LAN at the same time. Fine for an inverter-only display. A combined Victron/BMS/inverter dashboard needs a hub that talks to the stick (see Section 13).

---
## 5. PROBLEMS SOLVED (root-cause log)

| # | Symptom | Root cause | Fix |
|---|---|---|---|
| 1 | Text "weird", menus not expanding | Layout is fractions of screen size, but fonts are fixed. The 4:3 240×320 screen squashes box heights to ~60% of the design proportions. | Font scale patch (later superseded by stretch + scroll) |
| 2 | `Unable to access jarfile apktool.jar` | Jar downloaded as `apktool_3.0.3.jar` | `mv apktool_3.0.3.jar apktool.jar` |
| 3 | Generic XML scaling wouldn't help | Not a normal XML-layout app; it's **E4A** (layouts in smali) | Patched E4A runtime + 窗口13 code instead |
| 4 | Booting into 窗口13 connected, then showed the login screen | Switching inside `主窗口$创建完毕` happened too early. E4A then displayed the main window on top, and the dialogs/toasts floated over it. | **v3:** skip main startup (`return-void`) and switch **800 ms later** via `Handler.postDelayed` + new class `APLauncher` |
| 5 | Boxes too small | Short 4:3 screen | **Stretch** "screen height" ×2 + make windows **scrollable** |
| 6 | "Work Mode" / "Limiter" values cut off | Right-column value boxes only 0.203 W (~49 px) | **One-column layout:** 10 full-width rows, value W 0.54 (~130 px) |
| 7 | Faint values, ghost "mirror" text when scrolling | Value boxes drawn at **opacity 100/255** inside a ScrollView | Opacity 255 + single text colour `#C06000` |
| 8 | Too much empty space inside rows | Row height 0.062 (of the stretched height) | `--row-height` / `--row-pitch` options |

---

## 6. THE PATCHES (`patch_soyo.py`)

All patches are **idempotent**: re-running replaces the previous version, using marker comments in the smali. Paths are relative to `soyo_src/smali/com/`.

| Patch | File(s) | Marker | What it does |
|---|---|---|---|
| Font scale | `e4a/runtime/components/impl/android/n3/标签Impl.smali`, `n1/按钮Impl.smali`, `n2/编辑框Impl.smali` → `字体大小(F)V` | `# FONTSCALE` | Inserts `const v0, <float>` + `mul-float/2addr p1, v0` at the method start. v0 is free there, because it's overwritten by `getView()` on the next line. |
| Stretch | `e4a/runtime/系统相关类.smali` → `取屏幕高度_不含导航栏()I` | `# STRETCH` | Before `return v0`: `mul-int/lit16 v0, v0, <pct>` + `div-int/lit16 v0, v0, 0x64`. Every box's height and top grow by the factor. |
| Scroll | `e4a/runtime/应用操作.smali` → `切换窗口(窗口)V` | `# SCROLLPATCH` | First instruction calls `APLauncher.makeScrollable(window)`, which calls `窗口Impl.可否滚动(true)` inside try/catch (cannot crash). Applies to every window, including 窗口14 Settings. |
| AP-only boot v3 | `soysource/主窗口.smali` | `# APONLY v3` | At the start of `主窗口$创建完毕`: `invoke-virtual {p0} 延迟打开AP()V` then `return-void` (the rest of the main startup is skipped: language, update check, permissions). Adds `延迟打开AP()V` (Handler on main looper, `postDelayed(APLauncher, delay)`) and `打开AP窗口()V` (copy of the app's own open-窗口13 code). |
| Launcher class | **new** `soysource/APLauncher.smali` | — | `Runnable` holding the 主窗口; `run()` → `打开AP窗口()`. Static helpers: `makeScrollable(窗口)`, `fw(F)I` (fraction × screen width → px), `fh(F)I` (fraction × (stretched height − status bar) → px, same formula as the app). |
| One column + value style | `soysource/窗口13.smali` | `# ONECOL` | Before the final `return-void` of `$define` (after all components' `创建完毕`): `invoke-virtual/range {p0 .. p0} 单列布局()V`. The **/range** form is required because `$define` has `.locals 20`, so p0 is v20, beyond the 4-bit register limit. `单列布局()V` re-positions the 10 rows, moves the Error row / button panel / buttons below, extends the white panel, then sets value boxes `透明度` and `字体颜色`. |

### One-column layout geometry (fractions of W / usable H)

- **Rows, in reading order:** DC Voltage, DC Current, AC Voltage, AC Frequency, Real Power, Temp, Total, Limiter, Work Mode, Status. That's left then right, row by row from the original.
- **Row i:** top = 0.138 + i × pitch. Title L 0.038 / W 0.38. Value L 0.42 / W 0.54. Both are `row_height` tall.
- **Error row** (`标签11`, `编辑框11`): top = 0.138 + 10 × pitch + 0.012. Only moved; it keeps the original height of 0.062.
- **White panel** `标签3`: height = errTop + 0.062 + 0.03 − 0.099.
- **Button panel** `标签14`: top = errTop + 0.062 + 0.06. **Buttons** `按钮1` / `按钮3`: top = panel + 0.037. Only moved; size and style are unchanged.

### Command-line options

| Option | Default | Meaning |
|---|---|---|
| `src` | `soyo_src` | apktool output folder |
| `--font-scale` | 1.0 | Multiplier on all label/button/edit-box font sizes |
| `--stretch` | 2.0 | Screen-height multiplier (1–5). 1 means off. |
| `--row-height` | 0.062 | Height of each reading row (0.02–0.2) |
| `--row-pitch` | row height + 0.018 | Top-to-top spacing. **Set it equal to `--row-height` for rows that touch (spreadsheet look).** |
| `--value-color` | `C06000` | Value text colour, RRGGBB |
| `--value-opacity` | 255 | Value box opacity, 0–255 (the app's original was 100) |
| `--two-columns` | off | Restore the original 2-per-row layout (removes ONECOL exactly) |
| `--ap-delay` | 800 | ms before switching to 窗口13. Use 1500 if the login screen "wins". |
| `--no-ap` | off | Don't patch the boot into AP mode |

### Pixel reference on the Galaxy Mini (stretch 2, usable H ≈ 583 px)

| Row height / pitch | Row | Scroll length to the end of the buttons |
|---|---|---|
| 0.062 / 0.080 | 36 px | ~690 px |
| 0.062 / 0.062 | 36 px | ~585 px |
| **0.045 / 0.045** | **26 px** | **~490 px** |
| 0.040 / 0.040 | 23 px | ~460 px (below this, text may clip) |

---

## 7. FINAL CONFIGURATION

Last settings discussed, used for the "all solved" build:

```bash
cd ~/apkwork
python3 patch_soyo.py soyo_src --row-height 0.045 --row-pitch 0.045
./build_install.sh
```

The other values are defaults: `--stretch 2 --font-scale 1.0 --value-color C06000 --value-opacity 255 --ap-delay 800`.

> ✏️ **Record your exact final command here** if it differed (for example, an added `--ap-delay 1500`, or row height 0.062):
> `python3 patch_soyo.py soyo_src ______________________________`

**Validation done in the session:** every patch was tested against the real `窗口13.smali` (applies, re-runs without duplication, and `--two-columns` restores the original byte-for-byte, ignoring blank lines). All patched files assembled with the **same smali assembler bundled in apktool 3.0.3**, and a negative-control broken file was correctly rejected.

---

## 8. DAILY OPERATION

1. Phone Wi-Fi: connect to the **Soyosource stick's hotspot** (AP mode).
2. Launch the app. The login screen flashes briefly, then the dashboard slides in.
3. The IP dialog appears prefilled with `192.168.4.1:10000`. Press the Chinese **OK (确定)** button.
4. You'll see the toast "Device connected successfully" and live values refreshing about every 3 s.
5. **Avoid:** the **Back key** and the top-left back arrow. Both go to the login screen; relaunch the app to recover.
6. If the stick drops: "Device offline" appears and the app doesn't reconnect. Relaunch it (auto-reconnect is a planned improvement).

---

## 9. REBUILD PROCEDURES

### 9.1 Normal change (tweak options)

```bash
cd ~/apkwork
python3 patch_soyo.py soyo_src [options]
./build_install.sh            # asks for the keystore password
```

### 9.2 From scratch (new laptop / lost `soyo_src`)

```bash
sudo dnf install -y java-17-openjdk-headless python3 git unzip wget android-tools
mkdir -p ~/apkwork && cd ~/apkwork
# apktool 3.0.3 jar → apktool.jar
wget -O apktool.jar https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar
# Android build-tools (zipalign, apksigner)
unzip commandlinetools-linux-*_latest.zip -d ~/android-sdk
~/android-sdk/cmdline-tools/bin/sdkmanager --sdk_root=$HOME/android-sdk "build-tools;34.0.0"

# Get the ORIGINAL apk (from backup, or from a phone that still has the original installed)
#   adb shell pm path com.soysource ; adb pull /data/app/com.soysource-1.apk
java -jar apktool.jar d com.soysource-1.apk -o soyo_src
python3 patch_soyo.py soyo_src --row-height 0.045 --row-pitch 0.045
./build_install.sh
```

**Important:** always decode from the **original** APK, not from `soyo_mod.apk`. The patcher handles both, but the original is the clean baseline. Copy `my.keystore` back in before building, or updates will need an uninstall.

### 9.3 First install of a re-signed build

```bash
adb connect <phone-ip>:5555
adb uninstall com.soysource      # only when the signature changes (first time, or keystore lost)
./build_install.sh
```

---

## 10. TROUBLESHOOTING

| Symptom | Check / fix |
|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signature differs. Run `adb uninstall com.soysource` once. You'll lose the app's saved settings. |
| App crashes on launch | `adb logcat -d \| grep -E 'AndroidRuntime\|VFY\|soysource' \| tail -60`. Ignore `VFY: unable to resolve` lines for Android 6+ APIs; look for `VerifyError`, `FATAL EXCEPTION`, or anything mentioning `APLauncher`, `单列布局`, or `窗口13`. |
| Login screen stays on top | Increase the delay: `--ap-delay 1500` |
| Boxes overlap / gaps | Make `--row-pitch` ≥ `--row-height`. Use 0.061 vs 0.062 to hide 1-px seams. |
| Text clipped in rows | Raise `--row-height` by 0.005, or lower `--font-scale` |
| Still faint ghosting | Opacity is already 255. The next step is disabling hardware acceleration (`android:hardwareAccelerated="false"` on `<application>` in `AndroidManifest.xml`). Not yet implemented. |
| `adb: device offline / no devices` | `adb connect <ip>:5555`. Check that the phone and laptop are on the same network. When the phone is on the stick's hotspot, ADB over the home LAN won't reach it. |
| Chinese names show as `?????` | `export LANG=C.UTF-8` |
| `git commit` fails | Install git, then `git config --global user.name/user.email` |
| apktool build errors | Paste the error. Don't use `--use-aapt1`; apktool 3.x may not have it. |
| Patcher `ERROR: … not found` | Wrong folder, or a different app version. Re-decode from the original APK. |

---

## 11. BACKUP CHECKLIST

```bash
# git snapshot of the patched source
cd ~/apkwork/soyo_src && git init -q 2>/dev/null; git add -A && git commit -m "final working dashboard"
# full archive to the Windows desktop
cd ~ && tar czf apkwork_backup.tar.gz apkwork
cp apkwork_backup.tar.gz /mnt/c/Users/$(cmd.exe /c 'echo %USERNAME%' 2>/dev/null | tr -d '\r')/Desktop/
```

**Must-keep files:** `my.keystore` (+ its password!), `com.soysource-1.apk` (original), `soyo_mod.apk`, `patch_soyo.py`, `build_install.sh`, this handover.

---

## 12. KNOWN LIMITATIONS

- The IP dialog needs a manual OK press on every launch.
- No auto-reconnect after "Device offline".
- Back key / back arrow leads to the login screen.
- Screen may sleep (no keep-awake yet). Use CM11 Developer options → "Stay awake" while charging, which suits a wall mount.
- The main window's startup is skipped, so the language setting is never initialised. Some built-in dialog buttons (OK/Cancel) appear in **Chinese**. This is cosmetic.
- Patches are only verified for this app version (`com.soysource-1.apk`, targetSdk 26).
- The `--two-columns` path doesn't apply the colour/opacity fix (it's part of the one-column method).

---

## 13. NEXT STEPS / FUTURE PROJECTS

**A. Finish the wall display (smali patches, same approach):**

1. **Auto-connect:** on 窗口13 entry, call `客户1.连接服务器("192.168.4.1", 10000, 5000)` directly instead of showing the input dialog. This happens in the `窗口13$切换完毕` handler, ~line 7267 (around "line 54" where `地址` defaults).
2. **Auto-reconnect:** in `客户1$连接断开` and in the failure branch of `客户1$连接完毕`, schedule a reconnect after a few seconds, for example with a Handler and Runnable as in `APLauncher`.
3. **Disable Back:** make `窗口13$按下某键` consume the key (set the `BooleanReferenceParameter` to true and skip `切换窗口`). Optionally hide `图片框1` (the back arrow).
4. **Keep the screen on:** add the `FLAG_KEEP_SCREEN_ON` window flag via `mainActivity.getWindow().addFlags(0x80)`.
5. **Disable hardware acceleration** if ghosting ever returns.
6. **English dialog buttons:** call the app's own `英文更新()` language initialiser before switching, or patch the dialog strings.
7. **Bigger value text only:** a per-component font size in `单列布局` (call `字体大小(F)` on 编辑框1–10).

**B. Inverter protocol reader.** The reply layout is now decoded (`docs/soyosource-protocol.md`). Next, confirm the unverified scale factors against live frames, then write a **Python reader**:

```python
import socket, time
s = socket.create_connection(("192.168.4.1", 10000), timeout=5)
s.sendall(bytes.fromhex("5503000000000000000000FC"))   # model/spec query (on connect)
while True:
    s.sendall(bytes.fromhex("5501000000000000000000FE")) # data poll
    print(s.recv(256).hex())                              # decode once offsets are known
    time.sleep(3)
```

**C. Combined powerwall dashboard (original v1 architecture):** hub (ESP32 / Raspberry Pi / Home Assistant) reads the Victron MPPT (VE.Direct), the JSB BMS (BLE), and the Soyosource stick (TCP above, if the stick can be reached; it may need STA mode on the home Wi-Fi). The hub serves a tiny 240×320 HTML/JSON page, and the Galaxy Mini shows it in Via Browser. Use the same design rules learned here: one column, solid high-contrast text, and no transparency.

**D. v1 OS optimisation** (still pending): debloat apps, animations off, process limit, zRAM.

---

## 14. (moved)

The resume prompt is now in Section 19, covering the whole repository.

---

## 15. PART D — TAPO LOGGER (own app for the TP-Link Tapo P110)

### 15.1 What it is

A small (≈45 KB) Android app written from scratch in this project. It reads a **Tapo P110** smart plug directly over Wi-Fi, with no cloud, and logs every reading. It runs on the Galaxy Mini (Android 4.4, 240×320, ARMv6) and on newer phones.

| Version | Added |
|---|---|
| 1.0 | KLAP login, background logging to daily CSV, live screen, settings (only a hash of the login is stored) |
| 2.0 | **Energy Usage History** from the plug's own memory: Today / Past 7 / Past 30 days, Day / Month / Year bar charts, ‹ › navigation, tap-to-read, offline cache. **Tapo-style look** (grey page, white rounded cards, teal accent). |
| 3.0 | **Live Power Graph** on its own screen: time and date axis, drag to scroll, pinch or − / + to zoom (1 min to 24 h), tap for exact values, day navigation, live follow |
| 3.1 | Keeps working with the screen off or locked: Live Graph catches up from the log, a 5-minute watchdog restarts logging if Android kills it, and a live status notification |

Package `com.diy.tapologger`, minSdk 14, targetSdk 19. Full user guide: `tapo-logger/README.md`.

### 15.2 Architecture

| File | Role |
|---|---|
| `Klap.java` | KLAP v2 protocol (handshake, AES-CBC, request signing). Pure Java. |
| `LoggerService.java` | Foreground service: polls every N s (default 10), writes `tapo_YYYY-MM-DD.csv`, holds wake + Wi-Fi locks, watchdog alarm, live notification |
| `State.java` | Settings plus live values, and the **single shared KLAP session** used by the whole app |
| `History.java` | `get_energy_data` requests, date maths, cache of finished periods. Pure Java. |
| `GraphData.java` | Fast CSV loading, time-axis steps, zoom limits, lookups. Pure Java. |
| `MainActivity`, `HistoryActivity`, `LiveGraphActivity`, `SettingsActivity` | Screens, built in code and sized from the screen width |
| `Ui.java` | Colours, rounded cards, buttons |
| `BootReceiver.java` | Start on boot (optional) plus the watchdog |

**Build:** `tapo-logger/build.sh` uses aapt, javac, d8 or dx, zipalign, and apksigner. No Gradle or Android Studio. It works with the same `~/android-sdk` as the patcher (`sdkmanager "platforms;android-23"` once), or with Ubuntu's Android packages (which the CI uses).

**Signing:** the APKs delivered in chat are signed with a debug key that is **not** in this repo. A build from this repo uses `~/apkwork/my.keystore` if present, or generates `debug.keystore`. Switching keys means `adb uninstall com.diy.tapologger` once, which deletes the logs, so pull them first.

### 15.3 Testing

`tapo-logger/test/run_tests.sh` starts a **simulated P110** (`mock_p110.py`). It's built on python-kasa's own KLAP crypto, checks request signatures like the real plug, serves `get_energy_data`, and is itself checked with python-kasa's real client. It then runs **35 tests** across protocol, history and graph logic. CI (`.github/workflows/ci.yml`) runs them on every push and builds the APK.

**Verified on real hardware:** login, logging, history, and the Tapo-style screens on the Galaxy Mini with your P110. **Still to confirm:** pinch-zoom on the Mini's touchscreen, the Year tab reaching older data, and gap-free graphs after screen-off (v3.1).

### 15.4 Plug requirements and logs

- Tapo app → **Me → Third-Party Services → Third-Party Compatibility = ON**. Otherwise firmware 1.4+ answers HTTP 403 (it speaks TPAP, which isn't implemented).
- The phone must be on the same network as the plug. The plug can't switch Wi-Fi in place: either match the old name and password, or reset it and set it up again (setup needs internet).
- Logs: `/sdcard/Android/data/com.diy.tapologger/files/tapo_YYYY-MM-DD.csv`, with columns `time,power_w,voltage_v,current_a,today_wh,month_wh,plug_on,rssi`. Copy them with `adb pull`.

Details: `docs/tapo-p110-local-api.md`.

---

## 16. PART E — NETWORKING (as set up)

```
Laptop ── Bluetooth ──► other phone ──► internet
   └───── Wi-Fi ──────► Galaxy Mini hotspot (192.168.43.1) ──► ADB 192.168.43.1:5555
                              └──► Tapo P110 (192.168.43.x) ──► Tapo Logger
```

- **The Galaxy Mini's hotspot** works in CM11. Its earlier failure was caused by **Airplane mode** being on. The "connected devices" list may stay empty; use `cat /proc/net/arp` (flag `0x2` = connected) or `su -c cat /data/misc/dhcp/dnsmasq.leases`.
- **Same-name trick:** a hotspot with the *same name, password, and WPA2-PSK* as the plug's old network makes the plug move without a reset. Don't run two networks with the same name at once.
- **One radio:** the Mini can't be a hotspot and a Wi-Fi client at the same time (no STA+AP on KitKat). While it's a hotspot, it **can't reach the Soyosource stick**, unless the stick also joins the hotspot (untested: the stick must still answer on port 10000 in client mode).
- **Laptop with two connections:** Bluetooth internet plus the Mini's hotspot worked with no changes. If Windows ever sends internet traffic to the hotspot, set Bluetooth's interface metric to 10 and Wi-Fi's to 100.
- **Keep it awake:**
  ```bash
  adb shell settings put global wifi_sleep_policy 2
  adb shell settings put global stay_on_while_plugged_in 3
  adb shell su -c 'echo hotspot > /sys/power/wake_lock'   # optional, until reboot
  ```

Details: `docs/networking.md`.

---

## 17. PART F — SECOND PHONE: HTC DESIRE 816 DUAL SIM

| Item | Value |
|---|---|
| SoC / RAM | Snapdragon 400 quad-core, 1.5 GB |
| Screen | 5.5" 720×1280 (16:9) |
| OS | **Stock Android 6.0** (Sense) |
| Radios | **Bluetooth 4.0 (BLE)**; USB OTG reported (to verify) |

- The **original Soyosource app should display fine** on it (16:9). For AP-only boot without the small-screen fixes: `patch_soyo.py --stretch 1 --font-scale 1.0 --two-columns`.
- ADB over Wi-Fi on stock Android 6: enable USB debugging, run `adb tcpip 5555` over USB once (from Windows), then `adb connect`. Repeat after each reboot.
- BLE scanning on Android 6 needs **Location** switched on.
- Wall use: Screen pinning plus Stay awake. Check both phones' batteries periodically for swelling when they're on 24/7 charge.

**Agreed plan:** both phones are **displays**, and an **ESP32 hub (ESPHome)** does the hardware talking:

```
 [JBD/JSB BMS] --BLE-------->+----------------------+---Wi-Fi---> HTC: main dashboard
 [Victron MPPT] --VE.Direct-->|  ESP32 hub (ESPHome) |
 [Soyosource]  --TTL/Wi-Fi--->|  JSON + small pages  |---Wi-Fi---> Galaxy Mini: small display
                             +----------------------+
```

Existing components: `syssi/esphome-jbd-bms` (UART or BLE) and `syssi/esphome-soyosource-gtn-virtual-meter` (supports the GTN Wi-Fi version via its TTL-WIFI port). Back up the stick firmware before reflashing anything. Victron VE.Direct is a plain UART.

---

## 18. PART G — ROADMAP

| # | Item | Status / next action |
|---|---|---|
| 1 | **Battery power ramp** (reduce inverter max output as the LiFePO4 pack nears empty) | Designed in `docs/battery-ramp-controller.md`. **Need:** `窗口14.smali`, cell count and BMS cut-off, current inverter settings, inverter model. |
| 2 | Soyosource app: auto-connect, auto-reconnect, ignore Back, keep screen on | Smali patches, as in Section 13 |
| 3 | Confirm unverified protocol scale factors (current, frequency, temperature) | Log raw frames and compare with the app's display |
| 4 | Stick in client mode on a shared hotspot (so the Mini reaches stick and plug) | Experiment. Know how to reset the stick to AP mode first. |
| 5 | Tapo Logger: automatic plug discovery (IP changes on phone hotspots) | Offered, not built |
| 6 | ESP32 hub + combined dashboard for both phones | Hardware purchase |
| 7 | v1 OS optimisation on the Mini (debloat, animations off, zRAM) | Not started |

---

## 19. RESUME PROMPT (copy-paste into a new chat)

```
I'm continuing my old-Android solar dashboard project. Attached: docs/HANDOVER.md
(full context) plus the files the task needs (or: the repo is open in Claude Code).

Devices: Samsung Galaxy Mini GT-S5570I (CM11 / Android 4.4, 240x320, ARMv6, 290 MB RAM)
and HTC Desire 816 dual sim (stock Android 6, 720x1280, BLE).
Projects: (1) soyosource-patcher: patches the E4A Soyosource app (com.soysource) to boot into
the AP dashboard 窗口13 (192.168.4.1:10000), one column, 2x, scrollable, solid values.
(2) tapo-logger v3.1: own app for the Tapo P110 (KLAP), CSV logging, history, live graph.
Toolchain: Rocky Linux WSL, ~/apkwork, apktool 3.0.3, build-tools 34.0.0, ADB over Wi-Fi
(Mini hotspot: 192.168.43.1:5555), signing key ~/apkwork/my.keystore (alias "me").

Next I want to: <goal, e.g. "build the battery ramp controller from docs/battery-ramp-controller.md">.
Keep patches idempotent and marker-based, test pure-Java logic against the simulated devices,
and validate smali with apktool's assembler before handing over. Ask me for any files you need.
```
