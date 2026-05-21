package com.example.twsbatterydemo.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.twsbatterydemo.model.BatteryReadUiState
import com.example.twsbatterydemo.util.AppLogger

class BleScannerManager(
    private val context: Context
) {

    companion object {
        private const val TARGET_SCAN_LOG_INTERVAL_MS = 30_000L
    }

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val targetMatcher = TargetDeviceMatcher()
    private val gattSession = OpenFeelGattSession(context)
    private val lastScanLogAtByMac = mutableMapOf<String, Long>()

    private var scanCallback: ScanCallback? = null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun requiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun missingPermissions(): List<String> {
        return requiredPermissions().filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    fun hasRequiredPermissions(): Boolean = missingPermissions().isEmpty()

    fun startScan(
        onError: (String) -> Unit,
        onDebugLog: ((String) -> Unit)? = null
    ): Boolean {
        if (!hasRequiredPermissions()) {
            onError("缺少蓝牙扫描权限")
            return false
        }
        if (!isBluetoothEnabled()) {
            onError("蓝牙未开启")
            return false
        }

        val scanner = bleScanner ?: run {
            onError("当前设备不支持 BLE 扫描")
            return false
        }

        if (scanCallback != null) return true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitTargetScanHit(result, onDebugLog)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { emitTargetScanHit(it, onDebugLog) }
            }

            override fun onScanFailed(errorCode: Int) {
                val message = "扫描失败 errorCode=$errorCode"
                AppLogger.e("BleScannerManager", message)
                onDebugLog?.invoke("scan_failed errorCode=$errorCode")
                onError(message)
            }
        }

        scanCallback = callback
        if (!startScanSafely(scanner, settings, callback)) {
            onError("启动扫描失败")
            return false
        }
        AppLogger.d("BleScannerManager", "scan_start mode=no_filter")
        onDebugLog?.invoke("scan_start mode=no_filter")
        return true
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        if (hasRequiredPermissions()) {
            bleScanner?.let { stopScanSafely(it, callback) }
        }
        scanCallback = null
    }

    fun refreshBattery(
        onLog: (String) -> Unit,
        onState: (BatteryReadUiState) -> Unit
    ): Boolean {
        AppLogger.d("BleScannerManager", "refresh_enter")
        if (!hasRequiredPermissions()) {
            AppLogger.e("BleScannerManager", "refresh_failed reason=missing_permissions")
            onLog("refresh_failed reason=missing_permissions")
            return false
        }
        if (!isBluetoothEnabled()) {
            AppLogger.e("BleScannerManager", "refresh_failed reason=bluetooth_disabled")
            onLog("refresh_failed reason=bluetooth_disabled")
            return false
        }
        return gattSession.startRefresh(
            macAddress = targetMatcher.primaryTargetMac(),
            onLog = onLog,
            onState = onState
        )
    }

    fun disconnectBatterySession() {
        gattSession.disconnect()
    }

    fun isRefreshInFlight(): Boolean = gattSession.isRefreshInFlight()

    private fun emitTargetScanHit(
        result: ScanResult,
        onDebugLog: ((String) -> Unit)?
    ) {
        val hit = targetMatcher.match(result) ?: return
        val now = System.currentTimeMillis()
        val lastLoggedAt = lastScanLogAtByMac[hit.macAddress] ?: 0L
        if (now - lastLoggedAt < TARGET_SCAN_LOG_INTERVAL_MS) return

        lastScanLogAtByMac[hit.macAddress] = now
        AppLogger.d(
            "BleScannerManager",
            "scan_target_hit mac=${hit.macAddress} name=${hit.deviceName ?: "null"} rssi=${hit.rssi} reason=${hit.reason}"
        )
        onDebugLog?.invoke(
            "scan_target_hit mac=${hit.macAddress} name=${hit.deviceName ?: "null"} " +
                "rssi=${hit.rssi} reason=${hit.reason}"
        )
    }

    private fun hasScanPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun startScanSafely(
        scanner: BluetoothLeScanner,
        settings: ScanSettings,
        callback: ScanCallback
    ): Boolean {
        if (!hasScanPermission()) return false
        return runCatching {
            scanner.startScan(emptyList(), settings, callback)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun stopScanSafely(
        scanner: BluetoothLeScanner,
        callback: ScanCallback
    ) {
        if (!hasScanPermission()) return
        runCatching { scanner.stopScan(callback) }
    }
}
