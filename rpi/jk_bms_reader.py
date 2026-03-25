#!/usr/bin/env python3
"""
JK BMS BLE Reader for Raspberry Pi
Model: JK-BD4A24S10P (JK02_32S protocol)
MAC:   C8:47:80:4B:70:72

Reads cell voltages, current, temperatures, SOC via BLE.

Install:
    pip3 install bleak

Run:
    python3 jk_bms_reader.py
"""

import asyncio
import struct
import sys
from bleak import BleakClient, BleakScanner

# ── Config ──────────────────────────────────────────────────────
BMS_MAC = "C8:47:80:4B:70:72"
SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

# Commands (20 bytes each)
CMD_DEVICE_INFO = bytes([
    0xAA, 0x55, 0x90, 0xEB,
    0x97, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x11  # CRC
])

CMD_CELL_INFO = bytes([
    0xAA, 0x55, 0x90, 0xEB,
    0x96, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x10  # CRC
])

FRAME_SIZE = 300
PREAMBLE = bytes([0x55, 0xAA, 0xEB, 0x90])

# JK02_32S offsets (for BD4A / hardware v11+)
OFF_CELL_VOLTS = 6        # 32 x uint16 LE, * 0.001 V
OFF_AVG_VOLT = 70         # uint16 LE, * 0.001 V
OFF_DELTA_VOLT = 72       # uint16 LE, * 0.001 V
OFF_MAX_CELL = 74         # uint8, 1-based
OFF_MIN_CELL = 75         # uint8, 1-based
OFF_CELL_RES = 76         # 32 x uint16 LE, * 0.001 ohm
OFF_TOTAL_VOLT = 150      # uint32 LE, * 0.001 V
OFF_POWER = 154           # uint32 LE, * 0.001 W
OFF_CURRENT = 158         # int32 LE, * 0.001 A (neg = discharge)
OFF_TEMP1 = 162           # int16 LE, * 0.1 C
OFF_TEMP2 = 164           # int16 LE, * 0.1 C
OFF_MOS_TEMP = 166        # int16 LE, * 0.1 C
OFF_ERRORS = 168          # uint16 LE bitmask
OFF_CHARGE_ST = 170       # uint8
OFF_SOC = 173             # uint8, 0-100%
OFF_REMAIN_CAP = 174      # uint32 LE, * 0.001 Ah
OFF_NOMINAL_CAP = 178     # uint32 LE, * 0.001 Ah
OFF_CYCLES = 182          # uint32 LE
OFF_SOH = 190             # uint8, 0-100%

ERROR_NAMES = {
    0: "Charge over-temp",
    1: "Charge under-temp",
    3: "Cell undervoltage",
    4: "Pack undervoltage",
    5: "Charge overcurrent",
    9: "Discharge overcurrent",
    11: "Cell overvoltage",
    14: "Coprocessor comm error",
}


# ── Helpers ─────────────────────────────────────────────────────
def u16(data, off):
    return struct.unpack_from("<H", data, off)[0]

def i16(data, off):
    return struct.unpack_from("<h", data, off)[0]

def u32(data, off):
    return struct.unpack_from("<I", data, off)[0]

def i32(data, off):
    return struct.unpack_from("<i", data, off)[0]

def parse_temp(data, off):
    raw = i16(data, off)
    temp = raw * 0.1
    if temp > 200 or temp < -40:
        return None
    return temp

def verify_crc(data):
    return (sum(data[:299]) & 0xFF) == data[299]


# ── Frame parser ────────────────────────────────────────────────
def parse_cell_info(data):
    """Parse a 300-byte cell info frame (type 0x02)."""
    if not verify_crc(data):
        print("  [!] CRC mismatch, skipping frame")
        return None

    result = {}

    # Cell voltages (only count cells with voltage > 0)
    cells = []
    for i in range(32):
        v = u16(data, OFF_CELL_VOLTS + i * 2) * 0.001
        if v > 0.5:  # valid cell
            cells.append(v)
    result["cell_count"] = len(cells)
    result["cell_voltages"] = cells

    # Aggregate
    result["avg_voltage"] = u16(data, OFF_AVG_VOLT) * 0.001
    result["delta_voltage"] = u16(data, OFF_DELTA_VOLT) * 0.001
    result["max_cell"] = data[OFF_MAX_CELL]
    result["min_cell"] = data[OFF_MIN_CELL]

    # Pack totals
    result["total_voltage"] = u32(data, OFF_TOTAL_VOLT) * 0.001
    result["power"] = u32(data, OFF_POWER) * 0.001
    result["current"] = i32(data, OFF_CURRENT) * 0.001  # neg = discharge

    # Temperatures
    result["temp1"] = parse_temp(data, OFF_TEMP1)
    result["temp2"] = parse_temp(data, OFF_TEMP2)
    result["mos_temp"] = parse_temp(data, OFF_MOS_TEMP)

    # State
    result["soc"] = data[OFF_SOC]
    result["remaining_ah"] = u32(data, OFF_REMAIN_CAP) * 0.001
    result["nominal_ah"] = u32(data, OFF_NOMINAL_CAP) * 0.001
    result["cycles"] = u32(data, OFF_CYCLES)
    result["soh"] = data[OFF_SOH]

    # Errors
    err_mask = u16(data, OFF_ERRORS)
    errors = []
    for bit, name in ERROR_NAMES.items():
        if err_mask & (1 << bit):
            errors.append(name)
    result["errors"] = errors

    # Charge/discharge status
    status = data[OFF_CHARGE_ST]
    result["charging"] = bool(status & 0x01)
    result["discharging"] = bool(status & 0x02)

    return result


def print_bms_data(d):
    """Pretty print BMS data to terminal."""
    print("\n" + "=" * 60)
    print(f"  JK BMS  |  SOC: {d['soc']}%  |  SOH: {d['soh']}%")
    print("=" * 60)

    # Cell voltages in columns
    print(f"\n  Cell Voltages ({d['cell_count']} cells):")
    for i, v in enumerate(d["cell_voltages"]):
        marker = ""
        if i + 1 == d["max_cell"]:
            marker = " MAX"
        elif i + 1 == d["min_cell"]:
            marker = " min"
        end = "\n" if (i + 1) % 4 == 0 else ""
        print(f"    C{i+1:02d}: {v:.3f}V{marker:4s}", end=end)
    if d["cell_count"] % 4 != 0:
        print()

    print(f"\n  Avg: {d['avg_voltage']:.3f}V  |  Delta: {d['delta_voltage']:.3f}V")

    # Pack
    print(f"\n  Pack Voltage : {d['total_voltage']:.2f} V")
    print(f"  Current      : {d['current']:+.2f} A  ({'charging' if d['current'] > 0 else 'discharging'})")
    print(f"  Power        : {d['power']:.1f} W")
    print(f"  Capacity     : {d['remaining_ah']:.1f} / {d['nominal_ah']:.1f} Ah")
    print(f"  Cycles       : {d['cycles']}")

    # Temps
    temps = []
    if d["temp1"] is not None:
        temps.append(f"T1: {d['temp1']:.1f}C")
    if d["temp2"] is not None:
        temps.append(f"T2: {d['temp2']:.1f}C")
    if d["mos_temp"] is not None:
        temps.append(f"MOS: {d['mos_temp']:.1f}C")
    if temps:
        print(f"  Temps        : {' | '.join(temps)}")

    # Status
    status_parts = []
    if d["charging"]:
        status_parts.append("CHARGING")
    if d["discharging"]:
        status_parts.append("DISCHARGING")
    if d["errors"]:
        status_parts.append(f"ERRORS: {', '.join(d['errors'])}")
    if status_parts:
        print(f"  Status       : {' | '.join(status_parts)}")

    print("=" * 60)


# ── BLE connection ──────────────────────────────────────────────
class BMSConnection:
    def __init__(self):
        self.frame_buffer = bytearray()
        self.frames_received = 0

    def on_notify(self, sender, data: bytearray):
        """Handle BLE notification — reassemble frames."""
        # New frame starts with preamble
        if data[:4] == PREAMBLE:
            self.frame_buffer = bytearray(data)
        else:
            self.frame_buffer.extend(data)

        # Discard if too large
        if len(self.frame_buffer) > 400:
            self.frame_buffer = bytearray()
            return

        # Full frame received
        if len(self.frame_buffer) >= FRAME_SIZE:
            frame = bytes(self.frame_buffer[:FRAME_SIZE])
            self.frame_buffer = bytearray()

            frame_type = frame[4]
            if frame_type == 0x02:  # Cell info
                result = parse_cell_info(frame)
                if result:
                    self.frames_received += 1
                    print_bms_data(result)
            elif frame_type == 0x03:  # Device info
                print(f"  Device info frame received (type 0x03)")
                if verify_crc(frame):
                    # Extract device name
                    try:
                        name = frame[38:54].split(b'\x00')[0].decode('ascii', errors='replace')
                        hw = frame[14:22].split(b'\x00')[0].decode('ascii', errors='replace')
                        sw = frame[22:30].split(b'\x00')[0].decode('ascii', errors='replace')
                        print(f"  Device: {name}  HW: {hw}  SW: {sw}")
                    except Exception:
                        pass


async def main():
    print(f"Scanning for JK BMS ({BMS_MAC})...")

    # Try to find the device first
    device = await BleakScanner.find_device_by_address(BMS_MAC, timeout=15.0)
    if not device:
        print(f"[!] Could not find BMS with MAC {BMS_MAC}")
        print("    Make sure the BMS is powered on and in range.")
        print("\n    Listing nearby BLE devices...")
        devices = await BleakScanner.discover(timeout=10.0)
        for d in devices:
            print(f"      {d.address}  {d.name or '(unknown)'}")
        sys.exit(1)

    print(f"Found: {device.name or device.address}")

    bms = BMSConnection()

    async with BleakClient(device, timeout=20.0) as client:
        print(f"Connected! MTU: {client.mtu_size}")

        # Subscribe to notifications
        await client.start_notify(CHAR_UUID, bms.on_notify)
        print("Notifications enabled.")

        # Send device info command first
        print("Requesting device info...")
        await client.write_gatt_char(CHAR_UUID, CMD_DEVICE_INFO, response=False)
        await asyncio.sleep(2)

        # Send cell info command — BMS will stream continuously after this
        print("Requesting cell data (will stream continuously)...")
        await client.write_gatt_char(CHAR_UUID, CMD_CELL_INFO, response=False)

        # Keep running — press Ctrl+C to stop
        try:
            print("\nStreaming BMS data (Ctrl+C to stop)...\n")
            while True:
                await asyncio.sleep(1)
        except KeyboardInterrupt:
            print("\nStopping...")

        await client.stop_notify(CHAR_UUID)

    print("Disconnected.")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nBye.")
    except Exception as e:
        print(f"\n[!] Error: {e}")
        print("    Tips:")
        print("    - Make sure bluetooth is on: sudo systemctl start bluetooth")
        print("    - Check adapter: hciconfig")
        print("    - Try running with sudo if permission denied")
        sys.exit(1)
