#!/usr/bin/env python3
"""
Quick BMS raw data dump — run on Mac or RPi to capture raw frame hex.
Requires: pip3 install bleak
Usage: python3 bms_dump.py
"""
import asyncio
import sys
from bleak import BleakClient

BMS_MAC = "C8:47:80:4B:70:72"
SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

# Command: request cell info (0x96) with checksum
CMD_CELL_INFO = bytes([
    0xAA, 0x55, 0x90, 0xEB, 0x96,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x10  # checksum = sum(0:19) & 0xFF
])

buffer = bytearray()
frame_count = 0

def notification_handler(sender, data):
    global buffer, frame_count

    # Check for preamble
    if len(data) >= 4 and data[0] == 0x55 and data[1] == 0xAA and data[2] == 0xEB and data[3] == 0x90:
        if len(buffer) > 100:
            # Dump previous frame
            frame_count += 1
            print(f"\n=== FRAME {frame_count} ({len(buffer)} bytes) ===")
            for i in range(0, len(buffer), 16):
                hex_str = ' '.join(f'{b:02X}' for b in buffer[i:i+16])
                print(f"  [{i:3d}] {hex_str}")

            # Parse key values
            if len(buffer) >= 200:
                parse_frame(buffer)

            if frame_count >= 3:
                print("\n3 frames captured. Exiting.")
                sys.exit(0)

        buffer = bytearray(data)
    else:
        buffer.extend(data)

def parse_frame(f):
    """Try multiple known offset schemes and print what we find."""
    print("\n--- Parsing attempt ---")
    print(f"  Frame type byte[4] = 0x{f[4]:02X}")

    # Try to find cell voltages (should be ~3.9V = ~3900 mV)
    # Try 2-byte BE cells starting at offset 6
    print("\n  Cell voltages (2-byte BE from offset 6):")
    for i in range(24):
        off = 6 + i * 2
        if off + 1 < len(f):
            v = (f[off] << 8) | f[off+1]
            if 1000 < v < 5000:
                print(f"    Cell {i+1}: {v} mV ({v/1000:.3f}V)  [offset {off}]")
            elif v > 0:
                print(f"    Cell {i+1}: {v} (raw)  [offset {off}]")

    # Try 2-byte LE cells starting at offset 6
    print("\n  Cell voltages (2-byte LE from offset 6):")
    for i in range(24):
        off = 6 + i * 2
        if off + 1 < len(f):
            v = f[off] | (f[off+1] << 8)
            if 1000 < v < 5000:
                print(f"    Cell {i+1}: {v} mV ({v/1000:.3f}V)  [offset {off}]")

    # Try 3-byte cells (2 voltage + 1 resistance) starting at offset 6
    print("\n  Cell voltages (3-byte, 2B-BE + 1B res, from offset 6):")
    for i in range(24):
        off = 6 + i * 3
        if off + 1 < len(f):
            v = (f[off] << 8) | f[off+1]
            if 1000 < v < 5000:
                print(f"    Cell {i+1}: {v} mV ({v/1000:.3f}V)  [offset {off}]")

    # Scan for voltage-like values (65000-75000 = 65.0-75.0V pack)
    print("\n  Scanning for pack voltage (4-byte, 65000-75000 range):")
    for off in range(50, min(200, len(f) - 3)):
        # Try BE
        v_be = (f[off] << 24) | (f[off+1] << 16) | (f[off+2] << 8) | f[off+3]
        if 50000 < v_be < 100000:
            print(f"    BE offset {off}: {v_be} ({v_be/1000:.1f}V)")
        # Try LE
        v_le = f[off] | (f[off+1] << 8) | (f[off+2] << 16) | (f[off+3] << 24)
        if 50000 < v_le < 100000:
            print(f"    LE offset {off}: {v_le} ({v_le/1000:.1f}V)")

    # Scan for SOC (single byte 0-100)
    print("\n  Bytes with value 50-100 (possible SOC):")
    for off in range(100, min(200, len(f))):
        if 50 <= f[off] <= 100:
            print(f"    offset {off}: {f[off]}")

async def main():
    print(f"Connecting to BMS at {BMS_MAC}...")
    async with BleakClient(BMS_MAC) as client:
        print(f"Connected! MTU: {client.mtu_size if hasattr(client, 'mtu_size') else 'unknown'}")

        # List services
        for svc in client.services:
            print(f"  Service: {svc.uuid}")
            for char in svc.characteristics:
                props = ', '.join(char.properties)
                print(f"    Char: {char.uuid} [{props}]")

        # Subscribe to notifications
        await client.start_notify(CHAR_UUID, notification_handler)
        print("Subscribed to notifications")

        # Send cell info command
        await asyncio.sleep(0.5)
        await client.write_gatt_char(CHAR_UUID, CMD_CELL_INFO, response=False)
        print("Sent CMD_CELL_INFO")

        # Wait for data
        await asyncio.sleep(30)

asyncio.run(main())
