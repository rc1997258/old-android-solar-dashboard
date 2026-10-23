# Soyosource patcher

Patches **your own copy** of the official Soyosource Android app (`com.soysource`, an E4A app) so it works as a wall dashboard on a 240×320 Galaxy Mini:

- boots straight into the English **AP-mode dashboard** (`192.168.4.1:10000`), skipping login
- **one reading per row**, full width, so "Battery Limit" and "Connected" fit
- **2× taller** layout, with **scrolling** on every screen
- solid (opaque) value boxes in one readable colour, which also fixes the faint text and scroll "ghosting"
- adjustable fonts, row height and spacing

The vendor APK and its decompiled code are **not** included. You pull the APK from your own phone and patch it locally.

## Requirements

Rocky Linux / any Linux (WSL is fine), Java 17, Python 3, apktool **3.0.3** (as `apktool.jar`), Android build-tools 34 (`zipalign`, `apksigner`), and adb.

## Use

```bash
mkdir -p ~/apkwork && cd ~/apkwork
cp /path/to/old-android-solar-dashboard/soyosource-patcher/{patch_soyo.py,build_install.sh} .
adb shell pm path com.soysource                      # then: adb pull <that path> com.soysource-1.apk
java -jar apktool.jar d com.soysource-1.apk -o soyo_src
python3 patch_soyo.py soyo_src --row-height 0.045 --row-pitch 0.045
./build_install.sh                                   # first time: adb uninstall com.soysource
```

| Option | Default | Meaning |
|---|---|---|
| `--font-scale` | 1.0 | Multiply all font sizes |
| `--stretch` | 2.0 | Make the layout N× taller (1 = off); windows become scrollable |
| `--row-height` / `--row-pitch` | 0.062 / height + 0.018 | Row size and spacing (fractions of screen height). Equal values = rows touch. |
| `--value-color` / `--value-opacity` | C06000 / 255 | Value text colour and box opacity (the app's original opacity is 100) |
| `--two-columns` | off | Keep the original 2-per-row layout |
| `--ap-delay` | 800 | ms before switching to the dashboard (raise it if the login screen stays on top) |
| `--no-ap` | off | Don't skip the login / main window |

All patches are idempotent: re-run with new values and rebuild. Keep `my.keystore` safe, or future updates will need an uninstall. Full background, the app's internals, and troubleshooting are in `../docs/HANDOVER.md`; the stick's protocol is in `../docs/soyosource-protocol.md`.

`tools/scale_res.py` is a generic XML-dimension scaler from early experiments. It doesn't help with this app, whose layouts are built in code.
