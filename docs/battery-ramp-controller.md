# Design: battery-voltage power ramp for the Soyosource inverter

**Status:** design only, not implemented.

**Goal:** as a 24 V LiFePO4 bank nears empty, reduce the inverter's maximum output power step by step, instead of running at full power until a hard cut-off. This extracts more energy without the BMS disconnecting the pack.

## Why it's needed

The inverter only offers fixed thresholds (battery start voltage, start delay, output power, shutdown voltage), stored by the stick. Under heavy load a nearly empty pack sags quickly, so it hits the shutdown voltage, or worse, the BMS cut-off, while there's still usable energy at lower current.

## Inputs and outputs

| | Source |
|---|---|
| **Battery voltage** | Live data reply bytes 4–5 × 0.1 V, every 3 s (`soyosource-protocol.md`). Already decoded. |
| **Output power (actual)** | Reply bytes 11–12 (W) |
| **Max output power (setting)** | Written by the app's Settings screen `窗口14` ("Save settings"). **Command not decoded yet**: needs `窗口14.smali`. |

## Algorithm (starting values, all tunable)

1. **Filter:** average the voltage over the last ~60 s (20 readings), to ignore short dips.
2. **Target power from a curve** (24 V / 8S example):

   | Averaged voltage | Target max output |
   |---|---|
   | ≥ 26.4 V | Normal maximum (e.g. 900 W) |
   | 26.4 → 25.6 V | Linear ramp down to ~40% |
   | 25.6 → 25.0 V | Linear ramp down to a minimum (e.g. 100 W) |
   | < ~24.8 V | Leave it to the inverter's own shutdown voltage |

3. **Asymmetric hysteresis:** power goes **down** promptly, but comes back **up** only when the averaged voltage is ≥ 0.3 V above the level where it was reduced *and* has stayed there for ≥ 5 min. This stops oscillation, because a pack's voltage rises as soon as the load drops.
4. **Quantise:** round targets to **50 W** steps, and only act when the target differs from the current setting by at least one step.
5. **Rate limits (memory wear):** the stick probably saves settings to permanent memory. Allow at most **1 change per 5 min** (decreases may be sooner when the voltage is falling fast), and a **daily cap** (e.g. 60 writes). Show a write counter.
6. **Fail-safe direction:** if the app, phone or link dies, the inverter keeps its last setting, and its own shutdown voltage still protects the pack. **Set that shutdown voltage above the BMS cut-off.** It's the hard floor; the ramp only smooths the approach to it.

## LiFePO4 notes

- About 26 V on 8S is roughly 3.25 V per cell. The curve is flat over most of the charge and steep near empty, so voltage alone is a coarse charge gauge. The averaging and hysteresis compensate for that.
- Possible improvement: estimate the resting voltage as `V + I × R_internal` to correct for sag, with R_internal fitted from logged data.
- Best long-term input: **the BMS's charge %** (via an ESP32 hub reading the JBD BMS over BLE).

## Implementation plan

1. Get `窗口14.smali`, decode the settings read/write frames, and document them in `soyosource-protocol.md`.
2. Write `PowerRamp.java` as pure Java (filter, curve, hysteresis, rate limits), with unit tests driven by recorded or synthetic voltage traces, including oscillation and sag scenarios.
3. Compile it to smali and inject it into the patched app, with a hook at the end of `窗口13`'s `客户1$收到数据`. It sends write frames over the **existing** TCP connection (`客户1`), because the stick likely accepts only one client.
4. Settings in a small text file on the phone (thresholds, max, min, step, intervals, enable switch), plus a status line showing the target, the last write, and the write count.
5. Add it to `patch_soyo.py` as an opt-in option (`--power-ramp`).

## Alternative: Limiter mode over RS485

The inverter's **Limiter mode** (shown as "Battery Limit") accepts **live power commands continuously over RS485**, designed for frequent updates without writing to permanent memory. That's how `esphome-soyosource-gtn-virtual-meter` controls it. An ESP32 hub using the BMS's charge % is the robust long-term version of this feature.

## Information still needed

- [ ] `窗口14.smali` (Settings screen)
- [ ] Cell count (8S?) and the BMS low-voltage cut-off
- [ ] Current inverter settings: start voltage, start delay, output power, shutdown voltage
- [ ] Inverter model (e.g. GTN-1000 / GTN-1200) and its battery-mode maximum
