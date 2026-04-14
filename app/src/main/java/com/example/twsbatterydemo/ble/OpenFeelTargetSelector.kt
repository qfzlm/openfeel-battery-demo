package com.example.twsbatterydemo.ble

import com.example.twsbatterydemo.model.ScannedDeviceObservation
import java.util.Locale

class OpenFeelTargetSelector {

    companion object {
        const val PRIMARY_PREFERRED_MAC = "41:42:D3:16:6F:68"
        private const val MAC_PREFIX_41_42 = "41:42"
        private const val MATCH_MANUFACTURER_0A0B = "matchedByManufacturer0x0A0B"
        private const val MATCH_MAC_PREFIX_41_42 = "matchedByMacPrefix41_42"
    }

    private val observationByMac = LinkedHashMap<String, ScannedDeviceObservation>()
    private var lidOpenAnchorAt: Long? = null
    private var lastSuccessfulTargetMac: String? = null

    fun recordObservation(observation: ScannedDeviceObservation) {
        val mac = observation.macAddress
        val existing = observationByMac[mac]
        val merged = ScannedDeviceObservation(
            deviceName = observation.deviceName ?: existing?.deviceName,
            macAddress = mac,
            firstSeenAt = existing?.firstSeenAt ?: observation.firstSeenAt,
            lastSeenAt = observation.lastSeenAt,
            lastRssi = observation.lastRssi,
            matchReasons = existing?.matchReasons.orEmpty() + observation.matchReasons
        )
        observationByMac[mac] = merged

        if (lidOpenAnchorAt == null && merged.matchReasons.contains(MATCH_MAC_PREFIX_41_42)) {
            lidOpenAnchorAt = merged.lastSeenAt
        }
    }

    fun markSuccessfulBatteryRead(macAddress: String?) {
        if (!macAddress.isNullOrBlank()) {
            lastSuccessfulTargetMac = macAddress
        }
    }

    fun currentTargetMac(currentMacAddress: String? = null): String {
        if (!currentMacAddress.isNullOrBlank()) {
            return currentMacAddress
        }
        if (!lastSuccessfulTargetMac.isNullOrBlank()) {
            return lastSuccessfulTargetMac!!
        }
        if (observationByMac.containsKey(PRIMARY_PREFERRED_MAC)) {
            return PRIMARY_PREFERRED_MAC
        }

        val anchorAt = lidOpenAnchorAt
        observationByMac.values
            .filter { candidate ->
                candidate.matchReasons.contains(MATCH_MANUFACTURER_0A0B) &&
                    candidate.macAddress.uppercase(Locale.US).startsWith(MAC_PREFIX_41_42) &&
                    (anchorAt == null || candidate.lastSeenAt >= anchorAt)
            }
            .maxByOrNull { it.lastSeenAt }
            ?.macAddress
            ?.let { return it }

        observationByMac.values
            .filter { candidate -> candidate.macAddress.uppercase(Locale.US).startsWith(MAC_PREFIX_41_42) }
            .maxByOrNull { it.lastSeenAt }
            ?.macAddress
            ?.let { return it }

        return PRIMARY_PREFERRED_MAC
    }
}
