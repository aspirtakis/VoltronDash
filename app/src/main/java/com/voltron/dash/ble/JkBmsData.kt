package com.voltron.dash.ble

data class JkBmsData(
    val cellVoltages: List<Float> = emptyList(),
    val cellCount: Int = 0,
    val totalVoltage: Float = 0f,
    val current: Float = 0f,
    val power: Float = 0f,
    val soc: Int = 0,
    val temp1: Float = 0f,
    val temp2: Float = 0f,
    val mosfetTemp: Float = 0f,
    val remainingAh: Float = 0f,
    val nominalAh: Float = 0f,
    val cycles: Int = 0,
    val soh: Int = 100,
    val cellDelta: Float = 0f,
    val minCell: Float = 0f,
    val maxCell: Float = 0f,
    val connected: Boolean = false
)
