package com.example.twsbatterydemo.protocol

import com.example.twsbatterydemo.model.BatteryState

interface EarbudProtocolParser {
    fun canParse(serviceDataUuid16: Int, payload: ByteArray): Boolean
    fun parse(
        serviceDataUuid16: Int,
        payload: ByteArray,
        deviceName: String?,
        mac: String?
    ): BatteryState?
}
