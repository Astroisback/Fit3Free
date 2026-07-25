"""Enumerate what a BLE device exposes: advertisement data + full GATT tree.

Read-only. Scans for nearby devices, connects to the target, then walks every
service, characteristic and descriptor, reading anything marked readable.

Usage:
    pip install bleak
    python gatt_dump.py                 # scan and auto-pick a likely wearable
    python gatt_dump.py AA:BB:CC:DD:EE:FF

On Windows the watch does not need to be paired with the PC. If it is already
connected to a phone you may need to disconnect it there first.
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

# Substrings used to auto-pick a target when no MAC is supplied.
TARGET_HINTS = ("galaxy fit", "fit3", "fit 3", "gear", "watch", "band", "mi band")

# Common Bluetooth SIG short UUIDs -> friendly names.
KNOWN = {
    "1800": "Generic Access",
    "1801": "Generic Attribute",
    "1802": "Immediate Alert",
    "1803": "Link Loss",
    "1804": "Tx Power",
    "1805": "Current Time",
    "180a": "Device Information",
    "180d": "Heart Rate",
    "180f": "Battery",
    "1811": "Alert Notification",
    "1812": "HID",
    "1816": "Cycling Speed and Cadence",
    "181c": "User Data",
    "181d": "Weight Scale",
    "1826": "Fitness Machine",
    "fe59": "Nordic DFU",
    "2a00": "Device Name",
    "2a01": "Appearance",
    "2a04": "Preferred Conn Params",
    "2a05": "Service Changed",
    "2a19": "Battery Level",
    "2a23": "System ID",
    "2a24": "Model Number",
    "2a25": "Serial Number",
    "2a26": "Firmware Revision",
    "2a27": "Hardware Revision",
    "2a28": "Software Revision",
    "2a29": "Manufacturer Name",
    "2a2b": "Current Time",
    "2a37": "HR Measurement",
    "2a38": "Body Sensor Location",
    "2a39": "HR Control Point",
    "2a50": "PnP ID",
}


def label(uuid: str) -> str:
    """Map a 128-bit UUID back to its SIG name, if it is a known 16-bit alias."""
    return KNOWN.get(uuid.lower()[4:8], "")


async def scan(seconds: float = 15.0):
    print(f"Scanning {seconds:.0f}s ...\n")
    found = {}

    def cb(device, adv):
        found[device.address] = (device, adv)

    scanner = BleakScanner(cb)
    await scanner.start()
    await asyncio.sleep(seconds)
    await scanner.stop()

    print(f"{'ADDRESS':<20} {'RSSI':>5}  NAME")
    print("-" * 70)
    for addr, (dev, adv) in sorted(found.items(), key=lambda x: -(x[1][1].rssi or -999)):
        name = dev.name or adv.local_name or "(no name)"
        print(f"{addr:<20} {adv.rssi:>5}  {name}")
        for u in adv.service_uuids or []:
            tag = label(u)
            print(f"{'':<27}svc {u} {('- ' + tag) if tag else ''}")
        for cid, data in (adv.manufacturer_data or {}).items():
            print(f"{'':<27}mfr 0x{cid:04x}: {data.hex()}")
        for u, data in (adv.service_data or {}).items():
            print(f"{'':<27}sdata {u}: {data.hex()}")
    print()
    return found


def pick(found, wanted=None):
    if wanted:
        for addr, (dev, _) in found.items():
            if addr.lower() == wanted.lower():
                return dev
        return None
    for addr, (dev, adv) in found.items():
        name = (dev.name or adv.local_name or "").lower()
        if any(h in name for h in TARGET_HINTS):
            return dev
    return None


async def dump(device):
    print("=" * 70)
    print(f"Connecting to {device.address}  ({device.name or 'unknown'})")
    print("=" * 70)

    async with BleakClient(device, timeout=30.0) as client:
        print(f"Connected: {client.is_connected}\n")

        for svc in client.services:
            tag = label(str(svc.uuid))
            print(f"SERVICE {svc.uuid}  {tag or svc.description}")
            for ch in svc.characteristics:
                ctag = label(str(ch.uuid))
                props = ",".join(ch.properties)
                print(f"  CHAR  {ch.uuid}  [{props}]  {ctag or ch.description}")

                if "read" in ch.properties:
                    try:
                        val = await client.read_gatt_char(ch)
                        text = ""
                        if val and all(32 <= b < 127 for b in val):
                            text = f'  "{val.decode("utf-8", "replace").strip()}"'
                        print(f"        value: {val.hex()}{text}")
                    except Exception as e:
                        # A protocol error here usually means "bonding required".
                        print(f"        read failed: {type(e).__name__}: {e}")

                for d in ch.descriptors:
                    print(f"        desc {d.uuid} {label(str(d.uuid))}")
            print()


async def main():
    wanted = sys.argv[1] if len(sys.argv) > 1 else None
    found = await scan()
    dev = pick(found, wanted)
    if not dev:
        print("Target not found. Pass a MAC explicitly:")
        print("  python gatt_dump.py AA:BB:CC:DD:EE:FF")
        return
    try:
        await dump(dev)
    except Exception as e:
        print(f"[ERROR] {type(e).__name__}: {e}")


if __name__ == "__main__":
    asyncio.run(main())
