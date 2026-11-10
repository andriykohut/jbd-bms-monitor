# /// script
# dependencies = ["bleak"]
# ///
"""Read live status from the first battery with a JBD BMS heard over BLE.

The battery's BMS speaks the JBD ("Xiaoxiang") protocol: requests go to
characteristic FF02, replies arrive as notifications on FF01.

    uv run battery.py
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

SERVICE = "0000ff00-0000-1000-8000-00805f9b34fb"
RX = "0000ff01-0000-1000-8000-00805f9b34fb"
TX = "0000ff02-0000-1000-8000-00805f9b34fb"
PROTECTIONS = [
    "cell overvoltage", "cell undervoltage", "pack overvoltage", "pack undervoltage",
    "charge overtemp", "charge undertemp", "discharge overtemp", "discharge undertemp",
    "charge overcurrent", "discharge overcurrent", "short circuit", "frontend IC error",
    "MOSFET software lock",
]


def payload(frame: bytes, cmd: int) -> bytes:
    """Return the data bytes of a JBD reply `DD cmd status len data.. chk chk 77`."""
    n = frame[3]
    assert frame[0] == 0xDD and frame[1] == cmd and frame[2] == 0 and frame[6 + n] == 0x77, frame.hex()
    assert int.from_bytes(frame[4 + n:6 + n]) == -sum(frame[2:4 + n]) & 0xFFFF, f"bad checksum: {frame.hex()}"
    return frame[4:4 + n]


def decode(basic: bytes, cells: bytes) -> dict:
    u16 = lambda b, i: int.from_bytes(b[i:i + 2])
    ntc = basic[22]
    flags = u16(basic, 16)
    mv = [u16(cells, i) for i in range(0, len(cells), 2)]
    voltage = u16(basic, 0) / 100
    # The battery now and then answers with a well-formed block of nonsense, such as 2.56 V
    # across 32 cells at 2%; real readings stay within a few percent of their cells' sum.
    assert abs(sum(mv) / 1000 - voltage) <= voltage * 0.1, f"pack voltage {voltage} V doesn't match cells {mv}"
    return {
        "voltage_v": voltage,
        "current_a": int.from_bytes(basic[2:4], signed=True) / 100,  # + charging, - discharging
        "soc_pct": basic[19],
        "remaining_ah": u16(basic, 4) / 100,
        "capacity_ah": u16(basic, 6) / 100,
        "cycles": u16(basic, 8),
        "charge_fet": bool(basic[20] & 1),
        "discharge_fet": bool(basic[20] & 2),
        "temps_c": [(u16(basic, 23 + 2 * i) - 2731) / 10 for i in range(ntc)],
        "cells_mv": mv,
        "cell_delta_mv": max(mv) - min(mv),
        "protections": [name for bit, name in enumerate(PROTECTIONS) if flags >> bit & 1],
    }


async def read() -> dict:
    buf = bytearray()
    dev = await BleakScanner.find_device_by_filter(lambda _, ad: SERVICE in ad.service_uuids, timeout=40)
    if dev is None:
        sys.exit("no JBD BMS found; move closer, the signal is weak")
    async with BleakClient(dev, timeout=30) as c:
        await c.start_notify(RX, lambda _, data: buf.extend(data))

        async def query(cmd: int) -> bytes:
            buf.clear()
            chk = -cmd & 0xFFFF
            await c.write_gatt_char(TX, bytes([0xDD, 0xA5, cmd, 0, chk >> 8, chk & 0xFF, 0x77]), response=False)
            for _ in range(50):
                await asyncio.sleep(0.1)
                if len(buf) >= 7 and len(buf) >= buf[3] + 7:
                    return payload(bytes(buf), cmd)
            raise TimeoutError(f"no reply to command {cmd:#04x}, got {buf.hex()}")

        return decode(await query(0x03), await query(0x04))


if __name__ == "__main__":
    for k, v in asyncio.run(read()).items():
        print(f"{k:15} {v}")
