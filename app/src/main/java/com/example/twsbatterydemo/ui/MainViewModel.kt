package com.example.twsbatterydemo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.twsbatterydemo.ble.BleScannerManager
import com.example.twsbatterydemo.model.BatteryReadUiState
import com.example.twsbatterydemo.model.ScanUiState
import com.example.twsbatterydemo.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class MainViewModel(
    private val bleScannerManager: BleScannerManager
) : ViewModel() {

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

        AppLogger.d("MainViewModel", "refresh_requested targetMac=41:42:D3:16:6F:68")
        val started = bleScannerManager.refreshBattery(
            onLog = {},
            onState = ::updateBatteryState
        )
        if (!started) {
            if (bleScannerManager.isRefreshInFlight()) {
                return
            }
            _uiState.update { it.copy(errorMessage = "刷新失败，请确认耳机已开盖") }
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
                _uiState.update { it.copy(errorMessage = message) }
            }
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
