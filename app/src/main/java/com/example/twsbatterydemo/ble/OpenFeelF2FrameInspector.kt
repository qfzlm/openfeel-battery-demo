package com.example.twsbatterydemo.ble

import com.example.twsbatterydemo.util.toHexString

data class OpenFeelF2FrameObservation(
    val length: Int,
    val preambleHex: String,
    val leadingBytesHex: String,
    val suspectedType: Int?,
    val suspectedOpcode: Int?,
    val suspectedSubOpcode: Int?,
    val payloadZoneHex: String,
    val checksumZoneHex: String,
    val matches0E06: Boolean,
    val matches0E02: Boolean,
    val matches0B01: Boolean,
    val has09FFHeader: Boolean,
    val has08EEHeader: Boolean
)

class OpenFeelF2FrameInspector {

    fun inspect(bytes: ByteArray): OpenFeelF2FrameObservation {
        val length = bytes.size
        val preambleHex = bytes.copyOfRange(0, minOf(2, length)).toHexString()
        val leadingBytesHex = bytes.copyOfRange(0, minOf(8, length)).toHexString()
        val suspectedType = bytes.byteAtOrNull(2)
        val suspectedOpcode = bytes.byteAtOrNull(5)
        val suspectedSubOpcode = bytes.byteAtOrNull(6)

        val payloadZone = if (length > 4) bytes.copyOfRange(2, length - 2) else ByteArray(0)
        val checksumZone = if (length >= 2) bytes.copyOfRange(length - 2, length) else ByteArray(0)

        return OpenFeelF2FrameObservation(
            length = length,
            preambleHex = preambleHex,
            leadingBytesHex = leadingBytesHex,
            suspectedType = suspectedType,
            suspectedOpcode = suspectedOpcode,
            suspectedSubOpcode = suspectedSubOpcode,
            payloadZoneHex = payloadZone.toHexString(),
            checksumZoneHex = checksumZone.toHexString(),
            matches0E06 = bytes.containsPair(0x0E, 0x06),
            matches0E02 = bytes.containsPair(0x0E, 0x02),
            matches0B01 = bytes.containsPair(0x0B, 0x01),
            has09FFHeader = bytes.startsWith(0x09, 0xFF),
            has08EEHeader = bytes.startsWith(0x08, 0xEE)
        )
    }

    fun parse040CBatteryCandidate(bytes: ByteArray): Parse040CResult {
        if (bytes.size < 4) return Parse040CResult.NotMatched
        val header = bytes[0].toInt() and 0xFF
        val type = bytes[2].toInt() and 0xFF
        val subtype = bytes[3].toInt() and 0xFF
        if (header != 0xDD || type != 0x04 || subtype != 0x0C) {
            return Parse040CResult.NotMatched
        }
        if (bytes.size < 8) {
            return Parse040CResult.Malformed(
                reason = "length_too_short_for_040c",
                length = bytes.size
            )
        }

        val seq = bytes[1].toInt() and 0xFF
        val leftRaw = bytes[4].toInt() and 0xFF
        val rightRaw = bytes[5].toInt() and 0xFF
        val caseRaw = bytes[6].toInt() and 0xFF
        val tail = bytes[7].toInt() and 0xFF

        return Parse040CResult.Matched(
            sequenceOrChannel = seq,
            leftRaw = leftRaw,
            rightRaw = rightRaw,
            caseRaw = caseRaw,
            leftBattery = leftRaw and 0x7F,
            rightBattery = rightRaw and 0x7F,
            caseBattery = caseRaw and 0x7F,
            leftFlag = leftRaw and 0x80 != 0,
            rightFlag = rightRaw and 0x80 != 0,
            caseFlag = caseRaw and 0x80 != 0,
            tail = tail
        )
    }

    private fun ByteArray.byteAtOrNull(index: Int): Int? {
        return if (index in indices) this[index].toInt() and 0xFF else null
    }

    private fun ByteArray.startsWith(first: Int, second: Int): Boolean {
        if (size < 2) return false
        return (this[0].toInt() and 0xFF) == first && (this[1].toInt() and 0xFF) == second
    }

    private fun ByteArray.containsPair(first: Int, second: Int): Boolean {
        if (size < 2) return false
        for (i in 0 until size - 1) {
            val a = this[i].toInt() and 0xFF
            val b = this[i + 1].toInt() and 0xFF
            if (a == first && b == second) return true
        }
        return false
    }
}

sealed class Parse040CResult {
    data object NotMatched : Parse040CResult()

    data class Malformed(
        val reason: String,
        val length: Int
    ) : Parse040CResult()

    data class Matched(
        val sequenceOrChannel: Int,
        val leftRaw: Int,
        val rightRaw: Int,
        val caseRaw: Int,
        val leftBattery: Int,
        val rightBattery: Int,
        val caseBattery: Int,
        val leftFlag: Boolean,
        val rightFlag: Boolean,
        val caseFlag: Boolean,
        val tail: Int
    ) : Parse040CResult()
}
