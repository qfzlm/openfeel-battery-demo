package com.example.twsbatterydemo.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenFeelBatteryParserTest {

    @Test
    fun parseBatteryLevel_empty_returnsNull() {
        val parsed = OpenFeelBatteryParser.parseBatteryLevel(byteArrayOf())
        assertNull(parsed)
    }

    @Test
    fun parseBatteryLevel_0x64_returns100() {
        val parsed = OpenFeelBatteryParser.parseBatteryLevel(byteArrayOf(0x64.toByte()))
        assertEquals(100, parsed)
    }

    @Test
    fun parseBatteryLevel_0xFF_returns255() {
        // Current source behavior: value[0].toInt() and 0xFF
        val parsed = OpenFeelBatteryParser.parseBatteryLevel(byteArrayOf(0xFF.toByte()))
        assertEquals(255, parsed)
    }

    @Test
    fun parseSplitBatteryFrame_tooShort_returnsNull() {
        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(
            byteArrayOf(0xDD.toByte(), 0x01, 0x04, 0x0C, 0x64)
        )
        assertNull(parsed)
    }

    @Test
    fun parseSplitBatteryFrame_invalidHeader_returnsNull() {
        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(
            byteArrayOf(0xCC.toByte(), 0x01, 0x04, 0x0C, 0x64, 0x64, 0x1E, 0xAA.toByte())
        )
        assertNull(parsed)
    }

    @Test
    fun parseSplitBatteryFrame_invalidType_returnsNull() {
        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(
            byteArrayOf(0xDD.toByte(), 0x01, 0x05, 0x0C, 0x64, 0x64, 0x1E, 0xAA.toByte())
        )
        assertNull(parsed)
    }

    @Test
    fun parseSplitBatteryFrame_invalidSubType_returnsNull() {
        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(
            byteArrayOf(0xDD.toByte(), 0x01, 0x04, 0x0D, 0x64, 0x64, 0x1E, 0xAA.toByte())
        )
        assertNull(parsed)
    }

    @Test
    fun parseSplitBatteryFrame_validFrame_parsesExpectedValues() {
        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(
            byteArrayOf(0xDD.toByte(), 0x02, 0x04, 0x0C, 0xE4.toByte(), 0xE4.toByte(), 0x1E, 0xAA.toByte())
        )

        assertNotNull(parsed)
        assertEquals(0x02, parsed!!.sequence)
        assertEquals(100, parsed.leftBattery)
        assertEquals(100, parsed.rightBattery)
        assertEquals(30, parsed.caseBattery)
    }

    @Test
    fun parseSplitBatteryFrame_validFrameWithFlags_masksHighBit() {
        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(
            byteArrayOf(0xDD.toByte(), 0x06, 0x04, 0x0C, 0xE4.toByte(), 0x84.toByte(), 0x28, 0xAA.toByte())
        )

        assertNotNull(parsed)
        assertEquals(0x06, parsed!!.sequence)
        assertEquals(100, parsed.leftBattery)
        assertEquals(4, parsed.rightBattery)
        assertEquals(40, parsed.caseBattery)
    }

    @Test
    fun parseSplitBatteryFrame_appliesSevenBitMaskToExtremeRawLevels() {
        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(
            byteArrayOf(0xDD.toByte(), 0x0A, 0x04, 0x0C, 0xFF.toByte(), 0x80.toByte(), 0x7F, 0xAA.toByte())
        )

        assertNotNull(parsed)
        assertEquals(0x0A, parsed!!.sequence)
        assertEquals(127, parsed.leftBattery)
        assertEquals(0, parsed.rightBattery)
        assertEquals(127, parsed.caseBattery)
    }

    @Test
    fun parseSplitBatteryFrame_sizeGreaterThan8_stillParses() {
        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(
            byteArrayOf(
                0xDD.toByte(), 0x09, 0x04, 0x0C, 0x64, 0x00, 0x28, 0xAA.toByte(),
                0x11, 0x22
            )
        )

        assertNotNull(parsed)
        assertEquals(0x09, parsed!!.sequence)
        assertEquals(100, parsed.leftBattery)
        assertEquals(0, parsed.rightBattery)
        assertEquals(40, parsed.caseBattery)
    }

    @Test
    fun isBatteryLevelCharacteristic_caseInsensitive() {
        assertTrue(OpenFeelBatteryParser.isBatteryLevelCharacteristic("00002A19-0000-1000-8000-00805F9B34FB"))
        assertFalse(OpenFeelBatteryParser.isBatteryLevelCharacteristic("00002A1A-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun privateUuidMatchers_matchExpectedShortUuids() {
        assertTrue(OpenFeelBatteryParser.isPrivateService("000000fe-0000-1000-8000-00805f9b34fb"))
        assertTrue(OpenFeelBatteryParser.isPrivateWriteCharacteristic("000000f1-0000-1000-8000-00805f9b34fb"))
        assertTrue(OpenFeelBatteryParser.isPrivateNotifyCharacteristic("000000f2-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun privateUuidMatchers_rejectNonBluetoothBaseSuffix() {
        assertFalse(OpenFeelBatteryParser.isPrivateService("000000fe-1111-2222-3333-444444444444"))
        assertFalse(OpenFeelBatteryParser.isPrivateWriteCharacteristic("000000f1-1111-2222-3333-444444444444"))
        assertFalse(OpenFeelBatteryParser.isPrivateNotifyCharacteristic("000000f2-1111-2222-3333-444444444444"))
    }
}
