# Old-Android Solar Dashboard

Turning old Android phones into wall displays and loggers for a DIY solar powerwall: a **Soyosource GTN** grid-tie inverter (Wi-Fi stick), a **TP-Link Tapo P110** energy-monitoring plug, a LiFePO4 bank with a **JBD/JSB BMS**, and a **Victron** MPPT.

| Phone | Role |
|---|---|
| Samsung Galaxy Mini **GT-S5570I**: CyanogenMod 11 (Android 4.4), 240×320, ARMv6, 290 MB RAM | Inverter dashboard, Tapo P110 logger |
| HTC **Desire 816** dual sim: stock Android 6, 720×1280, BLE | Planned main dashboard |

## What's in here

| Folder | What it is | Status |
|---|---|---|
| [`soyosource-patcher/`](soyosource-patcher/) | Patches the official Soyosource app (your own copy) to boot into the AP-mode live dashboard: one column, 2× size, scrollable, high-contrast | ✅ Working |
| [`tapo-logger/`](tapo-logger/) | **Tapo Logger**, an Android 4.0+ app for the P110 over the local network. CSV logging, Tapo-style device page, energy history (day / month / year), zoomable live graph. | ✅ v2 confirmed on the Galaxy Mini; v3.1 awaiting confirmation |
| [`docs/`](docs/) | Handover, protocols, networking, design notes, session histories | |

Start with **[`docs/HANDOVER.md`](docs/HANDOVER.md)** for the complete picture.

## Quick start

**Tapo Logger:** build it, or use an APK from Releases:

```bash
pip install python-kasa && tapo-logger/test/run_tests.sh   # 35 tests against a simulated P110
tapo-logger/build.sh                                       # aapt + javac + d8/dx + apksigner, no Gradle
adb install -r tapo-logger/TapoLogger.apk
```

First, in the Tapo app, turn on **Me → Third-Party Services → Third-Party Compatibility**.

**Soyosource patcher:** see [`soyosource-patcher/README.md`](soyosource-patcher/README.md).

## Docs

| Document | Contents |
|---|---|
| [`HANDOVER.md`](docs/HANDOVER.md) | Devices, toolchain, the vendor app's internals, every patch, procedures, troubleshooting, roadmap, resume prompt |
| [`soyosource-protocol.md`](docs/soyosource-protocol.md) | The stick's TCP protocol (requests, 17-byte replies, field decoding with confidence levels) |
| [`tapo-p110-local-api.md`](docs/tapo-p110-local-api.md) | KLAP v2 handshake and encryption, requests and units, the Third-Party Compatibility switch |
| [`networking.md`](docs/networking.md) | Hotspots, ADB over Wi-Fi, keeping Wi-Fi awake, laptop with Bluetooth internet |
| [`battery-ramp-controller.md`](docs/battery-ramp-controller.md) | Design: ramp inverter power down as the LiFePO4 pack empties |
| [`history/`](docs/history/) | Chronological records of the work sessions |

## Roadmap

1. Battery-voltage power ramp for the inverter (designed; needs the Settings-screen protocol)
2. Soyosource app: auto-connect, auto-reconnect, ignore Back
3. ESP32 hub (ESPHome) reading BMS, Victron and inverter, with both phones as displays
4. Tapo Logger: automatic plug discovery

## Disclaimers

- **Not affiliated with** Soyosource, TP-Link / Tapo, Victron, or Samsung / HTC. All trademarks belong to their owners.
- **No vendor code or APKs are included.** The patcher modifies a copy of the app you own, for personal interoperability. Don't redistribute patched vendor APKs.
- The Soyosource and Tapo protocols are reverse-engineered and may change with firmware updates. Tapo's local access requires Third-Party Compatibility, which TP-Link notes may reduce device security.
- **Battery and mains equipment can be dangerous.** Software here is a convenience layer: keep the inverter's own shutdown voltage and the BMS protections configured as the real safety limits.

## Licence

[MIT](LICENSE) for the code and docs in this repository. The test suite uses [python-kasa](https://github.com/python-kasa/python-kasa) as an external dependency (it isn't bundled).
