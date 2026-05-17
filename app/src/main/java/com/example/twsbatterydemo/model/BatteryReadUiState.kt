package com.example.twsbatterydemo.model

data class BatteryReadUiState(
    val isRefreshing: Boolean = false,
    val isRefreshButtonBusy: Boolean = false,
    val isConnected: Boolean = false,
    val totalBatteryPercent: Int? = null,
    val leftBatteryPercent: Int? = null,
    val rightBatteryPercent: Int? = null,
    val caseBatteryPercent: Int? = null,
    val lastUpdatedAt: Long = 0L
)

data class LogExportUiState(
    val message: String? = null,
    val contentUri: String? = null,
    val sizeBytes: Long? = null
)
