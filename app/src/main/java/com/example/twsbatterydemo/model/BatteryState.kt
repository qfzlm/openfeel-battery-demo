package com.example.twsbatterydemo.model

data class BatteryState(
    val deviceName: String?,
    val macAddress: String?,
    val leftBattery: Int?,
    val rightBattery: Int?,
    val caseBattery: Int?,
    val extraStatus: Int?,
    val rawPayloadHex: String,
    val updatedAt: Long
)
