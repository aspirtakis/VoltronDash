package com.voltron.dash.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

object FarDriverParser {

    // BLE UUIDs
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000ffec-0000-1000-8000-00805f9b34fb"
    const val WRITE_CHAR_UUID = "0000ffe3-0000-1000-8000-00805f9b34fb"
    const val DEVICE_NAME = "CONTROLDM602"

    // CRC constants
    private const val CRC_INIT = 0x7F3C
    private const val CRC_POLY = 0xA001

    // Battery config — set from Settings
    var batteryCells = 18
    var batteryAh = 120f
    val batteryTotalWh: Float get() = batteryAh * batteryCells * 3.7f
    private const val DEFAULT_KM_PER_KWH = 20f
    var wheelDiameterInch = 12.0f
    var diffRatio = 1.0f  // differential/gear ratio (motor RPM / wheel RPM)

    // SOC per-cell voltage table (Li-ion NMC)
    private val SOC_CELL_TABLE = arrayOf(
        3.00f to 0, 3.30f to 5, 3.50f to 10, 3.60f to 20,
        3.70f to 30, 3.80f to 50, 3.90f to 65, 4.00f to 80,
        4.10f to 90, 4.20f to 100
    )

    // Register address lookup table
    private val FLASH_READ_ADDR = intArrayOf(
        0xE2, 0xE8, 0xEE, 0x00, 0x06, 0x0C, 0x12,
        0xE2, 0xE8, 0xEE, 0x18, 0x1E, 0x24, 0x2A,
        0xE2, 0xE8, 0xEE, 0x30, 0x5D, 0x63, 0x69,
        0xE2, 0xE8, 0xEE, 0x7C, 0x82, 0x88, 0x8E,
        0xE2, 0xE8, 0xEE, 0x94, 0x9A, 0xA0, 0xA6,
        0xE2, 0xE8, 0xEE, 0xAC, 0xB2, 0xB8, 0xBE,
        0xE2, 0xE8, 0xEE, 0xC4, 0xCA, 0xD0,
        0xE2, 0xE8, 0xEE, 0xD6, 0xDC, 0xF4, 0xFA
    )

    fun computeCrc(data: ByteArray, length: Int): Int {
        var crc = CRC_INIT
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (bit in 0 until 8) {
                crc = if (crc and 1 != 0) {
                    (crc ushr 1) xor CRC_POLY
                } else {
                    crc ushr 1
                }
            }
        }
        return crc and 0xFFFF
    }

    fun verifyCrc(data: ByteArray): Boolean {
        if (data.size != 16) return false
        val crc = computeCrc(data, 14)
        return crc == ((data[15].toInt() and 0xFF) shl 8 or (data[14].toInt() and 0xFF))
    }

    fun estimateSoc(voltage: Float): Int {
        val s = batteryCells
        val vMin = SOC_CELL_TABLE.first().first * s
        val vMax = SOC_CELL_TABLE.last().first * s
        if (voltage <= vMin) return SOC_CELL_TABLE.first().second
        if (voltage >= vMax) return SOC_CELL_TABLE.last().second
        for (i in 0 until SOC_CELL_TABLE.size - 1) {
            val vLo = SOC_CELL_TABLE[i].first * s
            val socLo = SOC_CELL_TABLE[i].second
            val vHi = SOC_CELL_TABLE[i + 1].first * s
            val socHi = SOC_CELL_TABLE[i + 1].second
            if (voltage in vLo..vHi) {
                val ratio = (voltage - vLo) / (vHi - vLo)
                return (socLo + ratio * (socHi - socLo)).toInt()
            }
        }
        return 0
    }

    fun calcSpeedKmh(state: MutableState): Float {
        // wheel_rpm = motor_rpm / diff_ratio
        // speed = wheel_rpm × π × diameter(m) × 60 / 1000
        val diameterM = wheelDiameterInch * 0.0254
        val wheelRpm = state.rpm / diffRatio
        val raw = wheelRpm * Math.PI * diameterM * 60.0 / 1000.0
        return (raw * 10).toInt() / 10f
    }

    /** Mutable state accumulated across frames. */
    class MutableState {
        var voltage: Float = 0f
        var current: Float = 0f
        var power: Float = 0f
        var rpm: Int = 0
        var speedKmh: Float = 0f
        var speedRaw: Int = 0
        var controllerTemp: Int = 0
        var motorTemp: Int = 0
        var soc: Int = 0
        var gear: String = "N"
        var gearNum: Int = 1
        var brakeActive: Boolean = false
        var inMotion: Boolean = false
        var faultCodes: MutableList<String> = mutableListOf()
        var state: String = "IDLE"
        var wheelRatio: Int = 0
        var wheelRadius: Int = 0
        var wheelWidth: Int = 0
        var rateRatio: Int = 1
        var polePairs: Int = 20

        // Tracking
        var totalKm: Float = 0f
        var sessionKm: Float = 0f
        var sessionStartTime: Long = System.currentTimeMillis()
        var totalHours: Float = 0f
        var kwhUsed: Float = 0f
        var rangeKm: Int = 0
        var smoothedSoc: Float = -1f  // EMA-smoothed SOC, -1 = uninitialized
        var lastSpeedTime: Long = 0
        var lastEnergyTime: Long = 0
        var whPerKmSamples: MutableList<Float> = mutableListOf()

        fun toData(): FarDriverData {
            val sessionSecs = ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()
            return FarDriverData(
                voltage = voltage, current = current, power = power,
                rpm = rpm, speedKmh = speedKmh, speedRaw = speedRaw,
                controllerTemp = controllerTemp, motorTemp = motorTemp,
                soc = soc, gear = gear, gearNum = gearNum,
                brakeActive = brakeActive, inMotion = inMotion,
                faultCodes = faultCodes.toList(), state = state,
                wheelRatio = wheelRatio, wheelRadius = wheelRadius,
                wheelWidth = wheelWidth, rateRatio = rateRatio,
                totalKm = totalKm, sessionKm = sessionKm,
                sessionTime = sessionSecs, totalHours = totalHours,
                kwhUsed = kwhUsed, rangeKm = rangeKm
            )
        }
    }

    /**
     * Parse a 16-byte BLE frame. Returns true if telemetry data changed.
     */
    fun parseFrame(data: ByteArray, state: MutableState): Boolean {
        if (data.size != 16 || data[0] != 0xAA.toByte()) return false
        if (!verifyCrc(data)) return false

        val regId = (data[1].toInt() and 0x3F)
        if (regId >= FLASH_READ_ADDR.size) return false

        val addr = FLASH_READ_ADDR[regId]
        val payload = data.sliceArray(2..13)

        return parseRegister(addr, payload, state)
    }

    private fun readInt16LE(payload: ByteArray, offset: Int): Int {
        val buf = ByteBuffer.wrap(payload, offset, 2).order(ByteOrder.LITTLE_ENDIAN)
        return buf.short.toInt()
    }

    private fun readUInt16LE(payload: ByteArray, offset: Int): Int {
        return (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun parseRegister(addr: Int, payload: ByteArray, state: MutableState): Boolean {
        when (addr) {
            0xE2 -> {
                val b0 = payload[0].toInt() and 0xFF
                val dnr = b0 and 0x03
                state.gear = when (dnr) { 1 -> "F"; 2 -> "R"; else -> "N" }
                state.gearNum = ((b0 shr 2) and 0x03) + 1
                state.inMotion = (b0 and 0x20) != 0

                val b2 = payload[2].toInt() and 0xFF
                val b3 = payload[3].toInt() and 0xFF
                val faults = mutableListOf<String>()
                if (b2 and 0x01 != 0) faults.add("Hall sensor")
                if (b2 and 0x02 != 0) faults.add("Throttle")
                if (b2 and 0x04 != 0) faults.add("Overcurrent")
                if (b2 and 0x10 != 0) faults.add("Voltage")
                if (b2 and 0x40 != 0) faults.add("Motor overtemp")
                if (b2 and 0x80 != 0) faults.add("Ctrl overtemp")
                if (b3 and 0x04 != 0) faults.add("Phase short")
                state.brakeActive = (b3 and 0x80) != 0
                state.faultCodes = faults

                val rawSpeed = readInt16LE(payload, 6)
                state.speedRaw = rawSpeed
                state.rpm = Math.abs(rawSpeed * 4 / state.polePairs)
                state.speedKmh = calcSpeedKmh(state)

                // Update tracking
                updateTracking(state)

                state.state = when {
                    faults.isNotEmpty() -> "FAULT"
                    state.inMotion -> "RUN"
                    state.brakeActive -> "BRAKE"
                    else -> "IDLE"
                }
                return true
            }
            0xE8 -> {
                val deciVolts = readInt16LE(payload, 0)
                state.voltage = deciVolts / 10f
                val lineCurrent = readInt16LE(payload, 4)
                state.current = lineCurrent / 4f
                state.power = Math.round(state.voltage * state.current * 10f) / 10f
                val rawSoc = estimateSoc(state.voltage)
                // EMA smoothing for battery display (α=0.03) — prevents throttle-induced fluctuation
                if (state.smoothedSoc < 0) {
                    state.smoothedSoc = rawSoc.toFloat()
                } else {
                    state.smoothedSoc += 0.03f * (rawSoc - state.smoothedSoc)
                }
                state.soc = state.smoothedSoc.toInt().coerceIn(0, 100)

                // Track energy usage — only when moving (ignore idle drain at traffic lights)
                val now = System.currentTimeMillis()
                if (state.power > 0 && state.speedKmh > 0.5f) {
                    if (state.lastEnergyTime > 0) {
                        val dtHours = (now - state.lastEnergyTime) / 3600000f
                        state.kwhUsed += state.power * dtHours / 1000f
                    }
                }
                state.lastEnergyTime = now

                // Range estimate
                val remainingKwh = state.soc / 100f * batteryTotalWh / 1000f
                val kmPerKwh = if (state.kwhUsed > 0.05f && state.totalKm > 0.5f) {
                    val measured = state.totalKm / state.kwhUsed
                    if (measured in 2f..100f) measured else DEFAULT_KM_PER_KWH
                } else {
                    DEFAULT_KM_PER_KWH  // use default until enough riding data
                }
                state.rangeKm = (remainingKwh * kmPerKwh).toInt().coerceIn(0, 999)

                return true
            }
            0xD6 -> {
                state.controllerTemp = readInt16LE(payload, 10)
                return true
            }
            0xF4 -> {
                state.motorTemp = readInt16LE(payload, 0)
                return true
            }
            0x12 -> {
                val polePairs = payload[4].toInt() and 0xFF
                if (polePairs > 0) state.polePairs = polePairs
                return false
            }
            0xD0 -> {
                state.wheelRatio = payload[4].toInt() and 0xFF
                state.wheelRadius = payload[5].toInt() and 0xFF
                state.wheelWidth = payload[7].toInt() and 0xFF
                state.rateRatio = readUInt16LE(payload, 8)
                if (state.rateRatio == 0) state.rateRatio = 1
                return false
            }
            0xCA -> {
                return false
            }
        }
        return false
    }

    private fun updateTracking(state: MutableState) {
        val now = System.currentTimeMillis()
        if (state.lastSpeedTime > 0 && state.speedKmh > 0.5f) {
            val dtHours = (now - state.lastSpeedTime) / 3600000f
            val distKm = state.speedKmh * dtHours
            state.sessionKm += distKm
            state.totalKm += distKm
            state.totalHours += dtHours
        }
        state.lastSpeedTime = now
    }

    fun buildWriteFrame(addr: Int, dataLo: Int, dataHi: Int): ByteArray {
        val frame = ByteArray(8)
        frame[0] = 0x55
        frame[1] = (addr and 0xFF).toByte()
        frame[2] = (dataLo and 0xFF).toByte()
        frame[3] = (dataHi and 0xFF).toByte()
        val crc = computeCrc(frame, 6)
        frame[6] = (crc and 0xFF).toByte()
        frame[7] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }
}
