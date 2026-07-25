# Fit3Free

Stream live heart rate off a Samsung Galaxy Fit3 (or any BLE band that implements
the standard Heart Rate Profile) to your Android notification shade, to a PC, or
to an AMOLED always-on display.

Samsung gives you no way to read Fit3 heart rate outside Samsung Health. The
watch does, however, expose a bog-standard `0x180D` Heart Rate service that
anything can subscribe to. This app is the small amount of plumbing needed to
make that useful, plus a written record of what the watch actually exposes over
BLE, since there is close to no public documentation on the Fit3's GATT surface.

## What it does

- **Notification-only by default.** Connects to your band as a GATT client,
  subscribes to HR notifications, and puts live BPM in an ongoing notification.
  Nothing else leaves the phone.
- **AMOLED always-on display.** True-black full-screen clock and BPM. Brightness
  follows the ambient light sensor, layout follows device rotation, content
  drifts a few pixels a minute for burn-in protection.
- **Optional BLE broadcast.** The phone re-advertises as a virtual Heart Rate
  Monitor, so a PC, Zwift, or any HR-aware app can connect to the phone as if it
  were a chest strap.
- **Optional WiFi fallback.** POSTs each BPM sample to an HTTP endpoint on your
  LAN. `tools/desktop_widget.py` is a ready-made receiver.

Both optional modes are off unless you turn them on, and toggling them applies
live without dropping the watch connection.

## Install

Grab the APK from [Releases](../../releases) and sideload it. Debug-signed, so
you will get the usual unknown-sources prompt.

First run: grant Bluetooth and notification permissions, pick your band from the
bonded-devices list, press START. The band must already be paired in Android
Bluetooth settings.

The package ID is still `com.hrbridge` from before the project was renamed. It
stays that way deliberately so existing installs upgrade in place rather than
appearing as a second app.

## Build

```bash
gradle assembleDebug     # or ./gradlew assembleDebug once a wrapper is present
```

Needs JDK 17 and the Android SDK with API 34. CI builds on every push via
`.github/workflows/build.yml`.

Note: the repo ships `gradle/wrapper/gradle-wrapper.properties` but no `gradlew`
script or wrapper JAR yet, so you need a system Gradle 8.5+ for now. Running
`gradle wrapper` once and committing the result would fix that — PRs welcome.

## Galaxy Fit3 BLE reference

Captured from firmware 6.1.2 / software 6.3.0 with `tools/gatt_dump.py`, over a
Windows BLE adapter, **with no pairing and no bond**. Advertised name is
`Galaxy Fit3 (XXXX)` where XXXX is the last four hex digits of the MAC.

### Heart Rate — `0000180d`

| Characteristic | UUID | Properties | Notes |
|---|---|---|---|
| HR Measurement | `00002a37` | notify | Works unbonded. ~1 sample/sec while a workout or continuous monitoring is active. |
| Body Sensor Location | `00002a38` | read | Returns `0x00` = "Other", not "Wrist". |
| HR Control Point | `00002a39` | write | Accepts `0x01` = Reset Energy Expended. Writable unbonded. |

Measurement packets arrive with `flags = 0x06`, meaning:

- bit 0 clear → 8-bit BPM in byte 1
- bit 1 set → sensor contact **detected**
- bit 2 set → sensor contact supported
- bit 3 clear → no Energy Expended field, so the control point has nothing to reset
- bit 4 clear → **no RR intervals**, so no HRV work is possible over this service

A typical packet is therefore two bytes, e.g. `0654` = 84 BPM with good contact.

### Device Information — `0000180a`

Mostly the BLE stack vendor's defaults rather than Samsung's real values:

| Characteristic | Value |
|---|---|
| Manufacturer Name | `RivieraWaves SAS` |
| Model Number | `RW-BLE-1.0` |
| Hardware Revision | `1.0.0` |
| Firmware Revision | `6.1.2` |
| Software Revision | `6.3.0` |
| System ID | `123456fffe9abcde` (placeholder) |
| IEEE 11073 Reg. Cert. | `ffeeddccbbaa` (placeholder) |
| PnP ID | `025e0440000003` |
| Serial Number | **bond required** |

### Generic Access / Attribute

`00001800` exposes Device Name, Appearance (`0x0000`), and Central Address
Resolution (`0x01`). `00001801` exposes Service Changed, Client Supported
Features, Server Supported Features.

### Proprietary services

Visible unbonded, but the useful payloads are gated:

| Service | Characteristics | Notes |
|---|---|---|
| `00001a1a` | `797ae4e9…` notify/read (bond required), `63e30bad…` write | Streaming channel. |
| `0000fd69` | three write/indicate/read, all reading `0x00` | Samsung-assigned UUID. Provisioning / find-device territory. |
| `eedd5e73…` | `50f98bfd…` read, `a12be31c…` read, `4ebe81f6…` write | Crypto handshake. Openly returns the literal string `AES_128-CBC-PKCS5Padding` plus a 16-byte value. |
| `1b7e8251…` | `8ac32d3f…` read/write/notify (bond required) | Almost certainly the main Samsung Accessory Protocol data channel. |

### Things that are not there

No Battery Service (`0x180F`) and no Current Time Service (`0x1805`). Watch
battery is not readable over standard BLE; it lives behind the proprietary
channels above.

### Privacy notes

Worth knowing whether or not you use this app:

- **HR is public.** Any device in range can connect and subscribe to `0x2A37`
  with no pairing, no PIN, and no prompt on the watch. That is standard Heart
  Rate Profile behaviour, not a Samsung bug, but the practical result is that
  your BPM and whether you are wearing the watch are readable by strangers.
- **The MAC does not rotate.** The Fit3 advertises a static public Samsung
  address, so it is a stable identifier for tracking across locations. Most
  modern BLE devices cycle random addresses; this one does not.
- **Turning on BLE broadcast in this app creates a second open HR server**, on
  your phone, advertising your phone's name in the clear. Leave it off unless
  you need it.

Reproduce any of this yourself with `tools/gatt_dump.py` and `tools/hr_listen.py`.

## Tools

Python 3.9+, `pip install bleak` (plus `tkinter` for the widget, bundled with
most Python installs).

| Script | Purpose |
|---|---|
| `tools/gatt_dump.py` | Scan, connect, and dump the full GATT tree with readable values. |
| `tools/hr_listen.py` | Subscribe to `0x2A37` and print decoded samples, including contact status and RR intervals when present. |
| `tools/desktop_widget.py` | Frameless always-on-top BPM widget for the desktop, with a built-in HTTP receiver. |

The WiFi fallback and the desktop widget speak plaintext HTTP with no
authentication. Fine on a trusted home LAN, not fine on public WiFi.

## Known gaps

- No `gradlew` wrapper committed (see Build).
- Release builds are unsigned; only debug APKs are published.
- No HRV, since the watch omits RR intervals from its HR packets.
- Reconnect is a flat 3-second retry with no backoff.
- Continuous HR has to be enabled on the watch itself; the app cannot start a
  measurement, only listen to one.

## License

No license. The source is public to read, learn from, and reference. Note that
without a license, default copyright applies and others have no formal grant to
reuse or redistribute the code. Drop in an `UNLICENSE` or `LICENSE` file later if
you want to make reuse explicit.
