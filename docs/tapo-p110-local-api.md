# TP-Link Tapo P110: local API as used by Tapo Logger

Community-reverse-engineered (python-kasa, tapo). Not officially supported by TP-Link, so firmware updates may change it.

## Prerequisite: Third-Party Compatibility

Tapo app → **Me → Third-Party Services → Third-Party Compatibility → ON.**

Since firmware 1.4.x, a P110 with this switch **off** speaks **TPAP** (a SPAKE2+-based login) and rejects KLAP with **HTTP 403**. With it **on**, it speaks **KLAP**, which is what Tapo Logger implements. TP-Link warns that enabling it may reduce device security, and it's off by default. If it seems not to take effect: switch it off, wait a minute, switch it on, wait a minute. It's stored per account, so re-toggle it after re-adding a plug.

## KLAP v2 transport

Everything is HTTP `POST` to `http://<plug-ip>`, with an `application/octet-stream` body.

| Step | Request | Reply / check |
|---|---|---|
| Auth hash | `auth = sha256( sha1(email) + sha1(password) )` (case-sensitive; the TP-Link account login) | — |
| Handshake 1 | `/app/handshake1`, body = 16 random bytes `local` | 48 bytes: `remote`(16) + `sha256(local + remote + auth)`(32). Verify it; a mismatch means a wrong e-mail or password. Save the cookie `TP_SESSIONID` (comes as `TP_SESSIONID=…;TIMEOUT=86400`). |
| Handshake 2 | `/app/handshake2`, body = `sha256(remote + local + auth)`, with the cookie | HTTP 200 |
| Session keys | `key = sha256("lsk"+local+remote+auth)[:16]`; `ivf = sha256("iv"+…)`, `iv = ivf[:12]`, `seq = int32(ivf[28:32])` (signed, big-endian); `sig = sha256("ldk"+…)[:28]` | — |
| Request | `seq += 1`; `ct = AES-128-CBC(key, iv + seq(4 bytes BE), PKCS7(json))`; body = `sha256(sig + seq + ct) + ct`; `POST /app/request?seq=<seq>` with the cookie | Body = 32-byte signature + ciphertext. Decrypt `body[32:]` with the same key and IV. |

The session lasts about 24 h. Tapo Logger re-handshakes after 20 h or on any error. Use **one session per app**: Tapo Logger shares a single synchronized session between the logger and the screens.

Request JSON:

```json
{"method":"get_energy_usage","request_time_milis":1760000000000,"terminal_uuid":"<base64 md5 of a uuid>"}
```

Add `"params":{…}` where needed. Replies are `{"error_code":0,"result":{…}}`; a non-zero `error_code` (e.g. `-1010` for an unsupported method) is passed through.

## Requests used

| Method | Params | Useful fields (units) |
|---|---|---|
| `get_energy_usage` | — | `current_power` (**mW**), `today_energy` / `month_energy` (**Wh**), `today_runtime` / `month_runtime` (min), `local_time` |
| `get_emeter_data` | — | `voltage_mv`, `current_ma`, `power_mw`, `energy_wh`. **Not on every firmware**: if `error_code ≠ 0`, skip it. |
| `get_device_info` | — | `device_on` (bool), `rssi` (dBm), `nickname` (**base64**), `model`, `fw_ver` |
| `get_energy_data` (hourly) | `start_timestamp` = day 00:00, `end_timestamp` = day 23:59:59, `interval: 60` | `data`: Wh per hour (≤ 8 days per call) |
| `get_energy_data` (daily) | `start_timestamp` = `end_timestamp` = 1st day of a **quarter**, `interval: 1440` | `data`: Wh per day of that quarter |
| `get_energy_data` (monthly) | `start_timestamp` = `end_timestamp` = **1 January**, `interval: 43200` | `data`: Wh per month (12 values) |

Timestamps are seconds, local midnight. Periods before the plug's memory return a non-zero `error_code`.

## Network notes

- No internet is needed for local KLAP. The plug's clock may drift without internet, which affects when "today" resets.
- A plug can't change Wi-Fi networks in place. Either reuse the old network's name and password, or reset it and set it up again in the Tapo app (setup needs internet).
- Phone hotspots can't reserve IP addresses, so the plug's IP may change after a hotspot restart.

## Testing without a plug

`tapo-logger/test/mock_p110.py` implements the device side, using python-kasa's own `KlapEncryptionSession` and auth-hash code, including signature checks and `get_energy_data`. `py_client_check.py` validates the mock with python-kasa's real client. `run_tests.sh` runs everything.
