package com.example.twsbatterydemo.model

data class ScanUiState(
    val bluetoothEnabled: Boolean = false,
    val hasRequiredPermissions: Boolean = false,
    val missingPermissions: List<String> = emptyList(),
    val errorMessage: String? = null,
    val batteryReadState: BatteryReadUiState = BatteryReadUiState(),
    val logExportState: LogExportUiState = LogExportUiState()
)
