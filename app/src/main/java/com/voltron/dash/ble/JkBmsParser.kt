package com.voltron.dash.ble

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JK BMS JK02_32S protocol parser.
 * Matched exactly to the working Raspberry Pi jk_bms_reader.py script.
 * ALL multi-byte values are LITTLE ENDIAN.
 */
object JkBmsParser {

    private const val TAG = "JkBmsParser"

    // BLE UUIDs
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"

    // Frame preamble (response from BMS)
    private val PREAMBLE = byteArrayOf(0x55, 0xAA.toByte(), 0xEB.toByte(), 0x90.toByte())
    private const val FRAME_SIZE = 300

    // JK02_32S offsets — ALL LITTLE ENDIAN (confirmed from working RPi script)
    private const val OFF_CELL_VOLTS = 6     // 32 x uint16 LE, * 0.001V (2 bytes per cell)
    private const val OFF_TOTAL_VOLT = 150   // uint32 LE, * 0.001V
    private const val OFF_POWER = 154        // uint32 LE, * 0.001W
    private const val OFF_CURRENT = 158      // int32 LE, * 0.001A (neg = discharge)
    private const val OFF_TEMP1 = 162        // int16 LE, * 0.1°C
    private const val OFF_TEMP2 = 164        // int16 LE, * 0.1°C
    private const val OFF_MOS_TEMP = 166     // int16 LE, * 0.1°C
    private const val OFF_SOC = 173          // uint8, 0-100%
    private const val OFF_REMAIN_CAP = 174   // uint32 LE, * 0.001Ah
    private const val OFF_NOMINAL_CAP = 178  // uint32 LE, * 0.001Ah
    private const val OFF_CYCLES = 182       // uint32 LE
    private const val OFF_SOH = 190          // uint8, 0-100%

    // Commands (20 bytes, checksum at byte 19 = sum(0..18) & 0xFF)
    val CMD_CELL_INFO: ByteArray = buildCommand(0x96)  // checksum = 0x10
    val CMD_DEVICE_INFO: ByteArray = buildCommand(0x97) // checksum = 0x11

    private fun buildCommand(cmd: Int): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0xAA.toByte()
        frame[1] = 0x55
        frame[2] = 0x90.toByte()
        frame[3] = 0xEB.toByte()
        frame[4] = cmd.toByte()
        var crc = 0
        for (i in 0 until 19) {
            crc = (crc + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        frame[19] = crc.toByte()
        return frame
    }

    /** Mutable state accumulated from BMS frames. */
    class MutableState {
        var cellVoltages: FloatArray = FloatArray(0)
        var cellCount: Int = 0
        var totalVoltage: Float = 0f
        var current: Float = 0f
        var power: Float = 0f
        var soc: Int = 0
        var temp1: Float = 0f
        var temp2: Float = 0f
        var mosfetTemp: Float = 0f
        var remainingAh: Float = 0f
        var nominalAh: Float = 0f
        var cycles: Int = 0
        var soh: Int = 100

        // Fragment reassembly
        var buffer: ByteArray = ByteArray(0)
        var collecting: Boolean = false

        fun toData(): JkBmsData {
            val cells = cellVoltages.toList()
            val validCells = cells.filter { it > 0.5f }
            val min = validCells.minOrNull() ?: 0f
            val max = validCells.maxOrNull() ?: 0f
            val delta = max - min
            return JkBmsData(
                cellVoltages = cells,
                cellCount = cellCount,
                totalVoltage = totalVoltage,
                current = current,
                power = power,
                soc = soc,
                temp1 = temp1,
                temp2 = temp2,
                mosfetTemp = mosfetTemp,
                remainingAh = remainingAh,
                nominalAh = nominalAh,
                cycles = cycles,
                soh = soh,
                cellDelta = delta,
                minCell = min,
                maxCell = max,
                connected = true
            )
        }
    }

    /**
     * Feed incoming BLE notification bytes into the reassembly buffer.
     * Returns true if a complete frame was parsed and state was updated.
     */
    fun feedData(data: ByteArray, state: MutableState): Boolean {
        // Check for preamble at start of chunk
        if (data.size >= 4 && data[0] == PREAMBLE[0] && data[1] == PREAMBLE[1]
            && data[2] == PREAMBLE[2] && data[3] == PREAMBLE[3]) {
            state.buffer = data.copyOf()
            state.collecting = true
        } else if (state.collecting) {
            val newBuf = ByteArray(state.buffer.size + data.size)
            System.arraycopy(state.buffer, 0, newBuf, 0, state.buffer.size)
            System.arraycopy(data, 0, newBuf, state.buffer.size, data.size)
            state.buffer = newBuf
        }

        // Discard if too large (corrupted)
        if (state.buffer.size > 400) {
            state.collecting = false
            state.buffer = ByteArray(0)
            return false
        }

        // Full frame received
        if (state.collecting && state.buffer.size >= FRAME_SIZE) {
            val frame = state.buffer.copyOf(FRAME_SIZE)
            state.collecting = false
            state.buffer = ByteArray(0)

            val frameType = frame[4].toInt() and 0xFF
            if (frameType == 0x02) {
                // Verify CRC: sum(bytes 0..298) & 0xFF == byte 299
                val crc = frame.take(299).sumOf { it.toInt() and 0xFF } and 0xFF
                val expected = frame[299].toInt() and 0xFF
                if (crc != expected) {
                    Log.w(TAG, "CRC mismatch: calc=$crc expected=$expected")
                    return false
                }
                return parseCellInfo(frame, state)
            }
            return false
        }

        return false
    }

    private fun parseCellInfo(frame: ByteArray, state: MutableState): Boolean {
        // Cell voltages: 32 x uint16 LE at offset 6, 2 bytes each
        val cells = mutableListOf<Float>()
        for (i in 0 until 32) {
            val off = OFF_CELL_VOLTS + i * 2
            val raw = u16(frame, off)
            if (raw == 0) break
            val volts = raw * 0.001f
            if (volts < 0.5f) break
            cells.add(volts)
        }

        if (cells.isEmpty()) {
            Log.w(TAG, "No valid cells found")
            return false
        }

        state.cellCount = cells.size
        state.cellVoltages = cells.toFloatArray()

        // Pack voltage (uint32 LE at 150)
        state.totalVoltage = u32(frame, OFF_TOTAL_VOLT) * 0.001f

        // Power (uint32 LE at 154)
        state.power = u32(frame, OFF_POWER) * 0.001f

        // Current (int32 LE at 158, negative = discharge)
        state.current = i32(frame, OFF_CURRENT) * 0.001f

        // Temperatures (int16 LE, * 0.1°C)
        state.temp1 = i16(frame, OFF_TEMP1) * 0.1f
        state.temp2 = i16(frame, OFF_TEMP2) * 0.1f
        state.mosfetTemp = i16(frame, OFF_MOS_TEMP) * 0.1f

        // SOC
        state.soc = frame[OFF_SOC].toInt() and 0xFF

        // Capacity
        state.remainingAh = u32(frame, OFF_REMAIN_CAP) * 0.001f
        state.nominalAh = u32(frame, OFF_NOMINAL_CAP) * 0.001f

        // Cycles
        state.cycles = u32(frame, OFF_CYCLES).toInt()

        // SOH
        state.soh = frame[OFF_SOH].toInt() and 0xFF

        Log.i(TAG, "BMS: ${state.cellCount}S V=${state.totalVoltage}V I=${state.current}A SOC=${state.soc}% " +
                "T1=${state.temp1} T2=${state.temp2} Cap=${state.remainingAh}/${state.nominalAh}Ah")
        return true
    }

    // Little-endian readers (matching RPi struct.unpack_from("<H"/"<h"/"<I"/"<i"))
    private fun u16(data: ByteArray, off: Int): Int {
        return (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)
    }

    private fun i16(data: ByteArray, off: Int): Int {
        val v = u16(data, off)
        return if (v >= 0x8000) v - 0x10000 else v
    }

    private fun u32(data: ByteArray, off: Int): Long {
        return (data[off].toLong() and 0xFF) or
                ((data[off + 1].toLong() and 0xFF) shl 8) or
                ((data[off + 2].toLong() and 0xFF) shl 16) or
                ((data[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun i32(data: ByteArray, off: Int): Int {
        return (data[off].toInt() and 0xFF) or
                ((data[off + 1].toInt() and 0xFF) shl 8) or
                ((data[off + 2].toInt() and 0xFF) shl 16) or
                ((data[off + 3].toInt() and 0xFF) shl 24)
    }
}
