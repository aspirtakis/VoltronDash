package com.voltron.dash.ble

data class FarDriverData(
    val voltage: Float = 0f,
    val current: Float = 0f,
    val power: Float = 0f,
    val rpm: Int = 0,
    val speedKmh: Float = 0f,
    val speedRaw: Int = 0,
    val controllerTemp: Int = 0,
    val motorTemp: Int = 0,
    val soc: Int = 0,
    val gear: String = "N",
    val gearNum: Int = 1,
    val brakeActive: Boolean = false,
    val inMotion: Boolean = false,
    val faultCodes: List<String> = emptyList(),
    val state: String = "IDLE",
    val wheelRatio: Int = 0,
    val wheelRadius: Int = 0,
    val wheelWidth: Int = 0,
    val rateRatio: Int = 1,
    val totalKm: Float = 0f,
    val sessionKm: Float = 0f,
    val sessionTime: Int = 0,
    val totalHours: Float = 0f,
    val kwhUsed: Float = 0f,
    val rangeKm: Int = 0
)
