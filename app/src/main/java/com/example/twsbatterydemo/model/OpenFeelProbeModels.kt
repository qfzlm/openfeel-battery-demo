package com.example.twsbatterydemo.model

data class ScannedDeviceObservation(
    val deviceName: String?,
    val macAddress: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val lastRssi: Int,
    val matchReasons: Set<String> = emptySet()
)

data class OpenFeelProbeState(
    val targetMac: String? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val batteryLevelPercent: Int? = null,
    val discoveredBattery180F2A19: Boolean = false,
    val discoveredFEF1F2: Boolean = false,
    val discoveredServiceFE: Boolean = false,
    val discoveredCharF1: Boolean = false,
    val discoveredCharF2: Boolean = false,
    val experimentalSplitBattery: ExperimentalSplitBattery? = null,
    val manualExperimentStatus: String = "实验未运行",
    val lastBatteryUpdatedAt: Long = 0L,
    val lastEventAt: Long = 0L
)

data class BatteryReadUiState(
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val batteryLevelPercent: Int? = null,
    val experimentalSplitBattery: ExperimentalSplitBattery? = null,
    val manualExperimentStatus: String = "实验未运行",
    val lastUpdatedAt: Long = 0L
)

data class ExperimentalSplitBattery(
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
    val tail: Int,
    val updatedAt: Long
)

data class LogExportUiState(
    val message: String? = null,
    val contentUri: String? = null,
    val sizeBytes: Long? = null
)
