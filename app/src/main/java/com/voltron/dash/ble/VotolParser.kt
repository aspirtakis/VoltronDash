package com.voltron.dash.ble

object VotolParser {

    // BLE UUIDs (ESP32 bridge)
    const val SERVICE_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb"
    const val CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb"
    const val DEVICE_NAME = "VOTOL_BLE"

    private const val FRAME_SIZE = 24
    private const val HEADER_BYTE = 0xC0
    private const val TYPE_BYTE = 0x14
    private const val TERMINATOR = 0x0D

    // Battery config (same pack as FarDriver)
    private const val BATTERY_TOTAL_WH = 7992f // 120Ah * 66.6V
    private const val DEFAULT_KM_PER_KWH = 20f

    // SOC voltage table: 18S Li-ion (same as FarDriver)
    private val SOC_TABLE = arrayOf(
        54.0f to 0, 59.4f to 5, 63.0f to 10, 64.8f to 20,
        66.6f to 30, 68.4f to 50, 70.2f to 65, 72.0f to 80,
        73.8f to 90, 75.6f to 100
    )

    private val STATUS_MAP = arrayOf("IDLE", "INIT", "START", "RUN", "STOP", "BRAKE", "WAIT", "FAULT")
    private val GEAR_MAP = arrayOf("L", "M", "H", "S")

    fun estimateSoc(voltage: Float): Int {
        if (voltage <= SOC_TABLE.first().first) return SOC_TABLE.first().second
        if (voltage >= SOC_TABLE.last().first) return SOC_TABLE.last().second
        for (i in 0 until SOC_TABLE.size - 1) {
            val (vLo, socLo) = SOC_TABLE[i]
            val (vHi, socHi) = SOC_TABLE[i + 1]
            if (voltage in vLo..vHi) {
                val ratio = (voltage - vLo) / (vHi - vLo)
                return (socLo + ratio * (socHi - socLo)).toInt()
            }
        }
        return 0
    }

    fun verifyChecksum(data: ByteArray): Boolean {
        if (data.size != FRAME_SIZE) return false
        var xor = 0
        for (i in 0 until 22) {
            xor = xor xor (data[i].toInt() and 0xFF)
        }
        return (xor and 0xFF) == (data[22].toInt() and 0xFF)
    }

    /** Mutable state accumulated across frames. */
    class MutableState {
        var voltage: Float = 0f
        var current: Float = 0f
        var power: Float = 0f
        var rpm: Int = 0
        var speedKmh: Float = 0f
        var controllerTemp: Int = 0
        var motorTemp: Int = 0
        var soc: Int = 0
        var gear: String = "N"
        var status: String = "IDLE"
        var brakeActive: Boolean = false
        var inMotion: Boolean = false

        // Tracking
        var totalKm: Float = 0f
        var sessionKm: Float = 0f
        var sessionStartTime: Long = System.currentTimeMillis()
        var totalHours: Float = 0f
        var kwhUsed: Float = 0f
        var rangeKm: Int = 0
        var smoothedSoc: Float = -1f
        var lastSpeedTime: Long = 0
        var lastEnergyTime: Long = 0

        fun toData(): FarDriverData {
            val sessionSecs = ((System.currentTimeMillis() - sessionStartTime) / 1000).toInt()
            return FarDriverData(
                voltage = voltage, current = current, power = power,
                rpm = rpm, speedKmh = speedKmh, speedRaw = rpm,
                controllerTemp = controllerTemp, motorTemp = motorTemp,
                soc = soc, gear = gear, gearNum = 1,
                brakeActive = brakeActive, inMotion = inMotion,
                faultCodes = if (status == "FAULT") listOf("Controller fault") else emptyList(),
                state = status,
                wheelRatio = 0, wheelRadius = 0, wheelWidth = 0, rateRatio = 1,
                totalKm = totalKm, sessionKm = sessionKm,
                sessionTime = sessionSecs, totalHours = totalHours,
                kwhUsed = kwhUsed, rangeKm = rangeKm
            )
        }
    }

    /**
     * Parse a 24-byte Votol BLE frame. Returns true if valid telemetry.
     */
    fun parseFrame(data: ByteArray, state: MutableState): Boolean {
        if (data.size != FRAME_SIZE) return false
        if ((data[0].toInt() and 0xFF) != HEADER_BYTE) return false
        if ((data[1].toInt() and 0xFF) != TYPE_BYTE) return false
        if ((data[23].toInt() and 0xFF) != TERMINATOR) return false
        if (!verifyChecksum(data)) return false

        // Voltage: bytes 5-6, big-endian, / 10.0
        val rawVoltage = ((data[5].toInt() and 0xFF) shl 8) or (data[6].toInt() and 0xFF)
        state.voltage = rawVoltage / 10f

        // Current: bytes 7-8, big-endian, signed, / 10.0
        val rawCurrent = ((data[7].toInt() and 0xFF) shl 8) or (data[8].toInt() and 0xFF)
        val signedCurrent = if (rawCurrent > 32767) rawCurrent - 65536 else rawCurrent
        state.current = signedCurrent / 10f

        state.power = Math.round(state.voltage * state.current * 10f) / 10f

        // RPM: bytes 14-15, big-endian
        state.rpm = ((data[14].toInt() and 0xFF) shl 8) or (data[15].toInt() and 0xFF)

        // Speed from RPM (using FarDriverParser wheel settings)
        val diameterM = FarDriverParser.wheelDiameterInch * 0.0254
        val wheelRpm = state.rpm / FarDriverParser.diffRatio
        val rawSpeed = wheelRpm * Math.PI * diameterM * 60.0 / 1000.0
        state.speedKmh = (rawSpeed * 10).toInt() / 10f

        // Temperatures
        state.controllerTemp = (data[16].toInt() and 0xFF) - 50
        state.motorTemp = (data[17].toInt() and 0xFF) - 50

        // Gear: byte 20, bits 0-1
        val gearIdx = (data[20].toInt() and 0xFF) and 0x03
        state.gear = GEAR_MAP.getOrElse(gearIdx) { "L" }

        // Status: byte 21
        val statusIdx = (data[21].toInt() and 0xFF)
        state.status = STATUS_MAP.getOrElse(statusIdx) { "IDLE" }
        state.brakeActive = state.status == "BRAKE"
        state.inMotion = state.status == "RUN"

        // SOC from voltage
        val rawSoc = estimateSoc(state.voltage)
        if (state.smoothedSoc < 0) {
            state.smoothedSoc = rawSoc.toFloat()
        } else {
            state.smoothedSoc += 0.03f * (rawSoc - state.smoothedSoc)
        }
        state.soc = state.smoothedSoc.toInt().coerceIn(0, 100)

        // Distance tracking
        val now = System.currentTimeMillis()
        if (state.lastSpeedTime > 0 && state.speedKmh > 0.5f) {
            val dtHours = (now - state.lastSpeedTime) / 3600000f
            val distKm = state.speedKmh * dtHours
            state.sessionKm += distKm
            state.totalKm += distKm
            state.totalHours += dtHours
        }
        state.lastSpeedTime = now

        // Energy tracking (only when moving)
        if (state.power > 0 && state.speedKmh > 0.5f) {
            if (state.lastEnergyTime > 0) {
                val dtHours = (now - state.lastEnergyTime) / 3600000f
                state.kwhUsed += state.power * dtHours / 1000f
            }
        }
        state.lastEnergyTime = now

        // Range estimate
        val remainingKwh = state.soc / 100f * BATTERY_TOTAL_WH / 1000f
        val kmPerKwh = if (state.kwhUsed > 0.05f && state.totalKm > 0.5f) {
            val measured = state.totalKm / state.kwhUsed
            if (measured in 2f..100f) measured else DEFAULT_KM_PER_KWH
        } else {
            DEFAULT_KM_PER_KWH
        }
        state.rangeKm = (remainingKwh * kmPerKwh).toInt().coerceIn(0, 999)

        return true
    }
}
