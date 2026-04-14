package com.example.twsbatterydemo.protocol

import com.example.twsbatterydemo.model.BatteryState

class ProtocolDispatcher(
    private val parsers: List<EarbudProtocolParser>
) {
    // 单一职责: 仅做解析器选择与调用，不处理扫描、去重、UI状态
    fun parse(
        serviceDataUuid16: Int,
        payload: ByteArray,
        deviceName: String?,
        mac: String?
    ): BatteryState? {
        for (parser in parsers) {
            if (!parser.canParse(serviceDataUuid16, payload)) continue
            parser.parse(serviceDataUuid16, payload, deviceName, mac)?.let { return it }
        }
        return null
    }
}
