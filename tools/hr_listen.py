"""Subscribe to a BLE Heart Rate Measurement characteristic and print samples.

Works against a watch/band directly, or against a phone running HR Bridge with
the BLE broadcast toggle on. Demonstrates that the standard Heart Rate Profile
needs no bonding: on a Galaxy Fit3 this connects and streams with no pairing
prompt at all.

Usage:
    pip install bleak
    python hr_listen.py AA:BB:CC:DD:EE:FF [seconds]
    python hr_listen.py                          # auto-discover by HR service
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

HR_SERVICE = "0000180d-0000-1000-8000-00805f9b34fb"
HR_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"


def parse_hr(data: bytes):
    """Decode a Heart Rate Measurement packet (Bluetooth SIG HRS 1.0)."""
    flags = data[0]
    idx = 1

    if flags & 0x01:  # bit 0: 16-bit BPM instead of 8-bit
        bpm = int.from_bytes(data[idx:idx + 2], "little")
        idx += 2
    else:
        bpm = data[idx]
        idx += 1

    contact_supported = bool(flags & 0x04)
    contact = bool(flags & 0x02) if contact_supported else None

    energy = None
    if flags & 0x08:  # bit 3: Energy Expended field present
        energy = int.from_bytes(data[idx:idx + 2], "little")
        idx += 2

    rr = []
    if flags & 0x10:  # bit 4: RR-Interval values present
        while idx + 1 < len(data):
            rr.append(int.from_bytes(data[idx:idx + 2], "little") / 1024.0)
            idx += 2

    return {"bpm": bpm, "contact": contact, "energy": energy, "rr": rr, "flags": flags}


async def find_target():
    print("Scanning for a device advertising the Heart Rate service ...")
    return await BleakScanner.find_device_by_filter(
        lambda d, ad: HR_SERVICE in [str(u).lower() for u in (ad.service_uuids or [])],
        timeout=15.0,
    )


async def main():
    mac = sys.argv[1] if len(sys.argv) > 1 else None
    duration = float(sys.argv[2]) if len(sys.argv) > 2 else 60.0

    target = mac or await find_target()
    if not target:
        print("Nothing found advertising 0x180D. Pass a MAC explicitly.")
        return

    count = 0
    async with BleakClient(target, timeout=25.0) as client:
        print(f"Connected (unbonded): {client.is_connected}")

        def cb(_, data: bytearray):
            nonlocal count
            count += 1
            r = parse_hr(bytes(data))
            bits = [f"BPM={r['bpm']}"]
            if r["contact"] is not None:
                bits.append(f"contact={'yes' if r['contact'] else 'no'}")
            if r["energy"] is not None:
                bits.append(f"energy={r['energy']}kJ")
            if r["rr"]:
                bits.append("rr=" + ",".join(f"{v:.3f}s" for v in r["rr"]))
            print(f"  raw={bytes(data).hex()}  flags=0x{r['flags']:02x}  " + "  ".join(bits))

        try:
            await client.start_notify(HR_MEASUREMENT, cb)
            print(f"Subscribed to 0x2A37. Listening {duration:.0f}s ...\n")
            await asyncio.sleep(duration)
            await client.stop_notify(HR_MEASUREMENT)
        except Exception as e:
            print(f"Subscribe failed: {type(e).__name__}: {e}")

    print(f"\nSamples received: {count}")


if __name__ == "__main__":
    asyncio.run(main())
