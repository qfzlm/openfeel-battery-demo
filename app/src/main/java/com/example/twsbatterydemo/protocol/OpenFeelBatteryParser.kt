package com.example.twsbatterydemo.protocol

import com.example.twsbatterydemo.util.hexToByteArrayOrNull
import java.util.Locale

data class SplitBatteryFrame(
    val sequence: Int,
    val leftRaw: Int,
    val rightRaw: Int,
    val caseRaw: Int,
    val leftBattery: Int,
    val rightBattery: Int,
    val caseBattery: Int,
    val tail: Int
)

object OpenFeelBatteryParser {

    const val BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL_UUID = "00002a19-0000-1000-8000-00805f9b34fb"

    private const val PRIVATE_SERVICE_SHORT_UUID = 0x00FE
    private const val PRIVATE_WRITE_SHORT_UUID = 0x00F1
    private const val PRIVATE_NOTIFY_SHORT_UUID = 0x00F2

    private val splitBatteryCommandOne =
        requireNotNull("FF000FFA010708090C0D0E122A2B2C2D2E33AA".hexToByteArrayOrNull())
    private val splitBatteryCommandTwo =
        requireNotNull("FF0002FA2BAA".hexToByteArrayOrNull())

    fun splitBatteryCommands(): List<ByteArray> {
        return listOf(splitBatteryCommandOne.copyOf(), splitBatteryCommandTwo.copyOf())
    }

    fun isBatteryLevelCharacteristic(uuid: String): Boolean {
        return uuid.lowercase(Locale.US) == BATTERY_LEVEL_UUID
    }

    fun isPrivateService(uuid: String): Boolean = shortUuid(uuid) == PRIVATE_SERVICE_SHORT_UUID

    fun isPrivateWriteCharacteristic(uuid: String): Boolean = shortUuid(uuid) == PRIVATE_WRITE_SHORT_UUID

    fun isPrivateNotifyCharacteristic(uuid: String): Boolean = shortUuid(uuid) == PRIVATE_NOTIFY_SHORT_UUID

    fun parseBatteryLevel(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        return value[0].toInt() and 0xFF
    }

    fun parseSplitBatteryFrame(value: ByteArray): SplitBatteryFrame? {
        if (value.size < 8) return null
        if ((value[0].toInt() and 0xFF) != 0xDD) return null
        if ((value[2].toInt() and 0xFF) != 0x04) return null
        if ((value[3].toInt() and 0xFF) != 0x0C) return null

        val sequence = value[1].toInt() and 0xFF
        val leftRaw = value[4].toInt() and 0xFF
        val rightRaw = value[5].toInt() and 0xFF
        val caseRaw = value[6].toInt() and 0xFF
        val tail = value[7].toInt() and 0xFF

        return SplitBatteryFrame(
            sequence = sequence,
            leftRaw = leftRaw,
            rightRaw = rightRaw,
            caseRaw = caseRaw,
            leftBattery = leftRaw and 0x7F,
            rightBattery = rightRaw and 0x7F,
            caseBattery = caseRaw and 0x7F,
            tail = tail
        )
    }

    private fun shortUuid(uuid: String): Int? {
        val normalized = uuid.lowercase(Locale.US)
        val suffix = "-0000-1000-8000-00805f9b34fb"
        if (!normalized.endsWith(suffix)) return null
        val prefix = normalized.substringBefore("-")
        if (prefix.length != 8) return null
        return prefix.substring(4, 8).toIntOrNull(16)
    }
}
