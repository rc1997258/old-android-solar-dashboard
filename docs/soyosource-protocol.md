# Soyosource GTN Wi-Fi stick: local TCP protocol

Decoded from the official Android app (`com.soysource`, E4A, AP-mode dashboard `窗口13`, mainly the handler `客户1$收到数据([B)V`). Nothing here is official documentation. Confidence is marked for each field.

## Connection

| Item | Value |
|---|---|
| Transport | Plain **TCP**, no encryption, no login |
| Address (AP mode) | The stick's own hotspot: **`192.168.4.1:10000`** |
| Address (client mode) | Unknown. The app's LAN / cloud dashboards (`窗口2`, `窗口11`) also use port 10000, which suggests the stick listens there on a home network too. **Unverified.** |
| Poll period | 3000 ms (`时钟1`, `0xbb8`) |
| Connect timeout used by the app | 5000 ms |
| Clients | Assume **one TCP client at a time** (typical for these ESP-based sticks). Don't run two apps against it simultaneously. |

## Requests (12 bytes)

| Purpose | Bytes (hex) |
|---|---|
| **Spec / model query** (sent once after connecting) | `55 03 00 00 00 00 00 00 00 00 00 FC` |
| **Live data poll** | `55 01 00 00 00 00 00 00 00 00 00 FE` |

The last byte looks like a checksum. For both known frames it equals `0xFF − byte1` (`01→FE`, `03→FC`). It's probably `0xFF − (sum of bytes 1–10) & 0xFF`, but that's unverified beyond these two frames.

**Settings read / write** (battery start voltage, start delay, output power, shutdown voltage) is done by the Settings screen `窗口14` and is **not decoded yet**. It's needed for the battery ramp controller (`battery-ramp-controller.md`).

## Replies (17 bytes)

The app converts each reply to a 34-character hex string and **ignores replies of any other length**. Offsets below are **0-based bytes**. Multi-byte values are big-endian.

### Live data reply (after `55 01 …`)

| Bytes | Field | Decoding in the app | Shown as | Confidence |
|---|---|---|---|---|
| 0–1 | Header | not used | — | — |
| 2–3 | **Status flags** (16 bits) | converted to a 16-character binary string; individual bits pick the texts below | Work Mode, Error, Limiter | High (bit-to-text mapping still to tabulate) |
| 4–5 | **DC (battery/PV) voltage** | value × 0.1 | `26.4V` | **High** |
| 6–7 | **DC current** | value × 0.1 (same factor register reused) | `12.3A` | Medium |
| 8–9 | **AC (grid) voltage** | integer | `231V` | Medium |
| 10 | **AC frequency** | integer, no scaling seen | `50HZ` | Low: verify against a live frame |
| 11–12 | **Real power** | integer | `300W` | Medium |
| 13–14 | **Total energy** | value ÷ 10 | `123.4KWH` | Medium |
| 15 | **Temperature** | integer, plus an adjustment step | `35℃` | Low: offset to verify |
| 16 | Checksum (presumed) | not checked by the app | — | — |

**Status texts** decoded from the flag bits (exact bit positions to be tabulated):
- Work mode: `standby`, `Working`, `Battery C.P` (constant power), `Battery Limit` (limiter), `PV Mode`, `PV Limit Mode`.
- Errors: `DC voltage too low`, `AC voltage too low`, `DC voltage too high`, `AC voltage too high`, `Overheat`.
- Limiter: `Connected` / `Offline`.

### Spec reply (after `55 03 …`)

| Byte | Field | Decoding |
|---|---|---|
| 4 | Model | Its decimal value; if `0`, the app re-sends the query. Otherwise the last two digits × 100 W, e.g. `12` → `1200W`. |
| 5 | Battery voltage class | Decimal `24` / `36` / `48` / `72` / `96`, mapped to PV-range texts, e.g. `PV：26-45V/Battery：24V` |

After a good spec reply, the app switches to polling with `55 01 …` every 3 s.

## Minimal Python reader (sketch)

```python
import socket, time

def frame(cmd):                      # 12-byte request, checksum = 0xFF - cmd (matches both known frames)
    return bytes([0x55, cmd] + [0] * 9 + [0xFF - cmd])

s = socket.create_connection(("192.168.4.1", 10000), timeout=5)
s.sendall(frame(0x03)); print("spec", s.recv(64).hex())
while True:
    s.sendall(frame(0x01))
    r = s.recv(64)
    if len(r) == 17:
        dc_v  = int.from_bytes(r[4:6], "big") * 0.1
        dc_a  = int.from_bytes(r[6:8], "big") * 0.1
        ac_v  = int.from_bytes(r[8:10], "big")
        power = int.from_bytes(r[11:13], "big")
        print(f"DC {dc_v:.1f} V  {dc_a:.1f} A   AC {ac_v} V   {power} W   raw {r.hex()}")
    time.sleep(3)
```

Log a few raw frames next to what the app shows, to pin down the Medium/Low rows above.

## The app's internals (for patching)

See `HANDOVER.md` Section 4 for the window map, the E4A runtime functions, and 窗口13's component layout and event flow.
