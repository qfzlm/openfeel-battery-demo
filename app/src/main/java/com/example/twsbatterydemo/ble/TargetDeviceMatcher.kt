package com.example.twsbatterydemo.ble

import android.bluetooth.le.ScanResult
import java.util.Locale

data class TargetDeviceHit(
    val macAddress: String,
    val deviceName: String?,
    val rssi: Int,
    val reason: String
)

class TargetDeviceMatcher {

    companion object {
        const val PRIMARY_TARGET_MAC = "41:42:D3:16:6F:68"
        const val TARGET_MANUFACTURER_ID = 0x0A0B
        private const val TARGET_MAC_PREFIX = "41:42"
    }

    fun primaryTargetMac(): String = PRIMARY_TARGET_MAC

    fun match(result: ScanResult): TargetDeviceHit? {
        val macAddress = result.device?.address?.uppercase(Locale.US) ?: return null
        val deviceName = result.scanRecord?.deviceName
        val hasTargetManufacturer = hasManufacturerId(result, TARGET_MANUFACTURER_ID)
        val hasTargetPrefix = macAddress.startsWith(TARGET_MAC_PREFIX)

        val reason = when {
            macAddress == PRIMARY_TARGET_MAC -> "exact_mac"
            hasTargetManufacturer && hasTargetPrefix -> "manufacturer_0x0A0B_mac_prefix_41_42"
            else -> return null
        }

        return TargetDeviceHit(
            macAddress = macAddress,
            deviceName = deviceName,
            rssi = result.rssi,
            reason = reason
        )
    }

    private fun hasManufacturerId(result: ScanResult, expectedId: Int): Boolean {
        val record = result.scanRecord ?: return false
        val manufacturerData = record.manufacturerSpecificData
        for (index in 0 until manufacturerData.size()) {
            if (manufacturerData.keyAt(index) == expectedId) {
                return true
            }
        }
        return false
    }
}
