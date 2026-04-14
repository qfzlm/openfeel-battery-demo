package com.example.twsbatterydemo.protocol

import com.example.twsbatterydemo.model.BatteryState
import com.example.twsbatterydemo.util.AppLogger
import com.example.twsbatterydemo.util.toHexString

class XiaomiFastConnectParser : EarbudProtocolParser {

    companion object {
        // 来源: 逆向线索，16-bit Service UUID = 0xFD2D
        const val SERVICE_UUID_16 = 0xFD2D

        // 来源: 逆向线索，payload[2] 是 type，要求 == 1
        private const val OFFSET_TYPE = 0x02
        private const val EXPECTED_TYPE = 0x01

        // 来源: 逆向线索，需要访问到 payload[0x11]，长度必须 >= 0x12
        private const val MIN_PAYLOAD_LENGTH = 0x12

        // 来源: 逆向线索，原始读取顺序 right / left / case
        private const val OFFSET_RIGHT_BATTERY = 0x0E
        private const val OFFSET_LEFT_BATTERY = 0x0F
        private const val OFFSET_CASE_BATTERY = 0x10

        // 来源: 逆向线索，payload[0x11] 暂命名 extraStatus
        private const val OFFSET_EXTRA_STATUS = 0x11
    }

    override fun canParse(serviceDataUuid16: Int, payload: ByteArray): Boolean {
        return serviceDataUuid16 == SERVICE_UUID_16
    }

    override fun parse(
        serviceDataUuid16: Int,
        payload: ByteArray,
        deviceName: String?,
        mac: String?
    ): BatteryState? {
        if (serviceDataUuid16 != SERVICE_UUID_16) return null
        if (payload.size < MIN_PAYLOAD_LENGTH) return null

        val type = payload[OFFSET_TYPE].toInt() and 0xFF
        if (type != EXPECTED_TYPE) return null

        val rightBattery = payload[OFFSET_RIGHT_BATTERY].toInt() and 0xFF
        val leftBattery = payload[OFFSET_LEFT_BATTERY].toInt() and 0xFF
        val caseBattery = payload[OFFSET_CASE_BATTERY].toInt() and 0xFF
        val extraStatus = payload[OFFSET_EXTRA_STATUS].toInt() and 0xFF
        val rawHex = payload.toHexString()

        AppLogger.d("XiaomiFastConnectParser", "serviceUUID=0x${serviceDataUuid16.toString(16)}")
        AppLogger.d("XiaomiFastConnectParser", "payloadLen=${payload.size}, payloadHex=$rawHex")
        AppLogger.d(
            "XiaomiFastConnectParser",
            "right=$rightBattery left=$leftBattery case=$caseBattery extraStatus=$extraStatus"
        )

        return BatteryState(
            deviceName = deviceName,
            macAddress = mac,
            leftBattery = leftBattery,
            rightBattery = rightBattery,
            caseBattery = caseBattery,
            extraStatus = extraStatus,
            rawPayloadHex = rawHex,
            updatedAt = System.currentTimeMillis()
        )
    }
}
