# Networking: phones, hotspots, plug, inverter stick, laptop

## Current setup

```
Laptop ── Bluetooth ──► other phone ──► internet
   └───── Wi-Fi ──────► Galaxy Mini hotspot (192.168.43.1) ──► ADB 192.168.43.1:5555
                              └──► Tapo P110 (192.168.43.x) ──► Tapo Logger on the Mini
```

## Rules learned the hard way

| Rule | Why |
|---|---|
| The Galaxy Mini is **either** a hotspot **or** a Wi-Fi client, never both | One radio, and Android 4.4 has no simultaneous STA+AP. Hacking the Broadcom driver for it isn't worth it (forced same channel, unstable). |
| While the Mini is a hotspot, it **can't reach the Soyosource stick's own hotspot** | Same reason. Options: let the HTC run the plug; make another device the shared hotspot; or join the stick to the hotspot in client mode (untested: the stick must still answer on port 10000). |
| **Airplane mode blocks the hotspot** | That was the cause of "CM11 can't turn the hotspot on". |
| Don't run **two networks with the same name** | Devices jump between them unpredictably. |
| The plug **can't switch Wi-Fi in place** | Reuse the old name, password and WPA2-PSK ("same-name trick"), or reset and set it up again (needs internet during setup). |
| Phone hotspots **can't reserve IPs** | After a restart the plug may get a new 192.168.43.x address. |

## Finding devices on the Mini's hotspot

The Settings list of connected devices may stay empty on this ROM. Use the Terminal app on the Mini instead:

```
cat /proc/net/arp                                # Flags 0x2 = connected; match the MAC from Tapo app → Device Info
su -c cat /data/misc/dhcp/dnsmasq.leases         # also shows device names, e.g. P110
```

## ADB

| Situation | Command |
|---|---|
| Laptop on the Mini's hotspot | `adb connect 192.168.43.1:5555` |
| Mini on a normal network | `adb connect <mini-ip>:5555` (CM11 Developer options → ADB over network) |
| HTC (stock Android 6), once per reboot | Over USB from Windows: `adb tcpip 5555`, then `adb connect <htc-ip>:5555` |

When the Mini turns its hotspot on, its Wi-Fi client connection drops, and any ADB session over it drops too. To read logs from that moment, write them to `/sdcard` with the on-phone Terminal and pull them later.

## Laptop with Bluetooth internet plus the Mini's hotspot

This works with no changes (Windows sends only 192.168.43.x traffic over Wi-Fi). If internet ever stops while you're on the hotspot, run this in PowerShell as admin (use the interface names from `Get-NetIPInterface`):

```powershell
Set-NetIPInterface -InterfaceAlias "Bluetooth Network Connection" -InterfaceMetric 10
Set-NetIPInterface -InterfaceAlias "Wi-Fi" -InterfaceMetric 100
# undo:
Set-NetIPInterface -InterfaceAlias "Wi-Fi" -AutomaticMetric Enabled
Set-NetIPInterface -InterfaceAlias "Bluetooth Network Connection" -AutomaticMetric Enabled
```

## Keeping the Mini's Wi-Fi / hotspot up with the screen off

```bash
adb shell settings put global wifi_sleep_policy 2           # never sleep Wi-Fi (0 = default)
adb shell settings put global stay_on_while_plugged_in 3    # stay awake on AC/USB (0 = off)
adb shell su -c 'echo hotspot > /sys/power/wake_lock'       # optional, root, until reboot
adb shell su -c 'echo hotspot > /sys/power/wake_unlock'     # release
```

While Tapo Logger is logging, it holds its own wake lock and Wi-Fi lock.
