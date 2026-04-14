package com.example.twsbatterydemo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.twsbatterydemo.ble.BleScannerManager
import com.example.twsbatterydemo.ble.OpenFeelTargetSelector
import com.example.twsbatterydemo.model.LogExportUiState
import com.example.twsbatterydemo.model.OpenFeelProbeState
import com.example.twsbatterydemo.model.ScanUiState
import com.example.twsbatterydemo.model.ScannedDeviceObservation
import com.example.twsbatterydemo.util.DownloadLogExporter
import com.example.twsbatterydemo.util.InMemoryLogStore
import com.example.twsbatterydemo.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainViewModel(
    private val bleScannerManager: BleScannerManager
) : ViewModel() {

    private val logStore = InMemoryLogStore(capacity = 1000)
    private val targetSelector = OpenFeelTargetSelector()
    private var isObservationScanActive: Boolean = false

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState

    fun refreshEnvironment() {
        val missingPermissions = bleScannerManager.missingPermissions()
        val bluetoothEnabled = bleScannerManager.isBluetoothEnabled()
        val hasRequiredPermissions = missingPermissions.isEmpty()

        _uiState.update {
            it.copy(
                bluetoothEnabled = bluetoothEnabled,
                hasRequiredPermissions = hasRequiredPermissions,
                missingPermissions = missingPermissions,
                errorMessage = if (hasRequiredPermissions && bluetoothEnabled) null else it.errorMessage
            )
        }

        if (hasRequiredPermissions && bluetoothEnabled) {
            ensureObservationScan()
        } else {
            stopObservationScan()
        }
    }

    fun refreshBatteryNow() {
        refreshEnvironment()
        val state = _uiState.value

        if (!state.hasRequiredPermissions) {
            _uiState.update { it.copy(errorMessage = "请先授予蓝牙权限") }
            return
        }
        if (!state.bluetoothEnabled) {
            _uiState.update { it.copy(errorMessage = "请先打开蓝牙") }
            return
        }

        val targetMac = targetSelector.currentTargetMac()
        recordLog("battery_refresh targetMac=$targetMac")
        bleScannerManager.readBattery(
            macAddress = targetMac,
            enableSplitBatteryExperiment = true,
            autoTriggerSplitBatteryAfterReady = true,
            onLog = ::recordLog,
            onProbeState = ::updateProbeState
        )
    }

    fun exportLogs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val displayName = "ble_log_${timestamp}.txt"
            val content = logStore.snapshot().joinToString("\n")
            val result = DownloadLogExporter.exportText(
                context = context,
                displayName = displayName,
                content = content
            )
            recordLog(
                "log_export result=${result.message} uri=${result.contentUri ?: "null"} size=${result.sizeBytes ?: -1}"
            )
            _uiState.update {
                it.copy(
                    logExportState = LogExportUiState(
                        message = result.message,
                        contentUri = result.contentUri,
                        sizeBytes = result.sizeBytes
                    )
                )
            }
        }
    }


    fun stopScan() {
        stopObservationScan()
    }

    private fun ensureObservationScan() {
        if (isObservationScanActive) return

        val started = bleScannerManager.startScan(
            onError = { message ->
                recordLog("scan_error message=$message")
                _uiState.update { it.copy(errorMessage = message) }
            },
            onDebugLog = ::recordLog,
            onScanSeenDevice = ::recordObservation
        )

        if (started) {
            isObservationScanActive = true
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun stopObservationScan() {
        if (!isObservationScanActive) return
        bleScannerManager.stopScan()
        isObservationScanActive = false
    }

    private fun recordObservation(observation: ScannedDeviceObservation) {
        targetSelector.recordObservation(observation)
        recordLog(
            "target_observation mac=${observation.macAddress} " +
                "name=${observation.deviceName ?: "null"} " +
                "lastRssi=${observation.lastRssi} " +
                "firstSeen=${TimeUtils.format(observation.firstSeenAt)} " +
                "lastSeen=${TimeUtils.format(observation.lastSeenAt)} " +
                "reasons=${observation.matchReasons.joinToString("|")}"
        )
    }

    private fun updateProbeState(state: OpenFeelProbeState) {
        if (state.batteryLevelPercent != null) {
            targetSelector.markSuccessfulBatteryRead(state.targetMac)
        }

        _uiState.update { current ->
            val currentBattery = current.batteryReadState
            current.copy(
                batteryReadState = currentBattery.copy(
                    isConnecting = state.isConnecting,
                    isConnected = state.isConnected,
                    batteryLevelPercent = state.batteryLevelPercent ?: currentBattery.batteryLevelPercent,
                    experimentalSplitBattery = state.experimentalSplitBattery ?: currentBattery.experimentalSplitBattery,
                    manualExperimentStatus = state.manualExperimentStatus,
                    lastUpdatedAt = if (state.lastBatteryUpdatedAt > 0L) {
                        state.lastBatteryUpdatedAt
                    } else {
                        currentBattery.lastUpdatedAt
                    }
                ),
                errorMessage = null
            )
        }
    }

    private fun recordLog(message: String) {
        logStore.append("${TimeUtils.format(System.currentTimeMillis())} | $message")
    }

    override fun onCleared() {
        stopObservationScan()
        bleScannerManager.disconnectBatterySession()
        super.onCleared()
    }

    class Factory(
        private val bleScannerManager: BleScannerManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(bleScannerManager) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
