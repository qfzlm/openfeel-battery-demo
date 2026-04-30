package com.example.twsbatterydemo.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.twsbatterydemo.ble.BleScannerManager
import com.example.twsbatterydemo.model.BatteryReadUiState
import com.example.twsbatterydemo.model.LogExportUiState
import com.example.twsbatterydemo.model.ScanUiState
import com.example.twsbatterydemo.util.DownloadLogExporter
import com.example.twsbatterydemo.util.InMemoryLogStore
import com.example.twsbatterydemo.util.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainViewModel(
    private val bleScannerManager: BleScannerManager
) : ViewModel() {

    private val logStore = InMemoryLogStore(capacity = 1000)
    private val exportFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private var isScanActive = false

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState

    fun refreshEnvironment() {
        val missingPermissions = bleScannerManager.missingPermissions()
        val bluetoothEnabled = bleScannerManager.isBluetoothEnabled()
        val hasRequiredPermissions = missingPermissions.isEmpty()

        _uiState.update { current ->
            current.copy(
                bluetoothEnabled = bluetoothEnabled,
                hasRequiredPermissions = hasRequiredPermissions,
                missingPermissions = missingPermissions,
                errorMessage = if (hasRequiredPermissions && bluetoothEnabled) null else current.errorMessage
            )
        }

        if (!(hasRequiredPermissions && bluetoothEnabled)) {
            stopScan()
        }
    }

    fun refreshBatteryNow() {
        refreshEnvironment()
        val state = _uiState.value

        if (bleScannerManager.isRefreshInFlight()) {
            recordLog("refresh_ignored reason=in_flight")
            return
        }
        if (!state.hasRequiredPermissions) {
            _uiState.update { it.copy(errorMessage = "请先授予蓝牙权限") }
            return
        }
        if (!state.bluetoothEnabled) {
            _uiState.update { it.copy(errorMessage = "请先打开蓝牙") }
            return
        }

        recordLog("refresh_requested targetMac=41:42:D3:16:6F:68")
        val started = bleScannerManager.refreshBattery(
            onLog = ::recordLog,
            onState = ::updateBatteryState
        )
        if (!started) {
            if (bleScannerManager.isRefreshInFlight()) {
                recordLog("refresh_ignored reason=in_flight")
                return
            }
            _uiState.update { it.copy(errorMessage = "刷新失败，请确认耳机已开盖") }
        }
    }

    fun exportLogs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val displayName = "ble_log_${LocalDateTime.now().format(exportFormatter)}.txt"
            val result = DownloadLogExporter.exportText(
                context = context,
                displayName = displayName,
                content = logStore.snapshot().joinToString("\n")
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
        if (!isScanActive) return
        bleScannerManager.stopScan()
        isScanActive = false
    }

    private fun ensureBackgroundScan() {
        if (isScanActive) return

        val started = bleScannerManager.startScan(
            onError = { message ->
                recordLog("scan_error message=$message")
                _uiState.update { it.copy(errorMessage = message) }
            },
            onDebugLog = ::recordLog
        )

        if (started) {
            isScanActive = true
            _uiState.update { it.copy(errorMessage = null) }
        }
    }

    private fun updateBatteryState(newState: BatteryReadUiState) {
        _uiState.update { current ->
            current.copy(
                batteryReadState = newState,
                errorMessage = null
            )
        }
    }

    private fun recordLog(message: String) {
        logStore.append("${TimeUtils.format(System.currentTimeMillis())} | $message")
    }

    override fun onCleared() {
        stopScan()
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
