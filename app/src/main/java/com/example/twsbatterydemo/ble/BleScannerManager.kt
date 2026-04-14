package com.example.twsbatterydemo.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.twsbatterydemo.model.OpenFeelProbeState
import com.example.twsbatterydemo.model.ScannedDeviceObservation
import com.example.twsbatterydemo.model.ExperimentalSplitBattery
import com.example.twsbatterydemo.util.AppLogger
import com.example.twsbatterydemo.util.TimeUtils
import com.example.twsbatterydemo.util.hexToByteArrayOrNull
import com.example.twsbatterydemo.util.toHexString
import java.util.Locale
import java.util.UUID

class BleScannerManager(
    private val context: Context
) {

    private data class CharacteristicInfo(
        val characteristicUuid: String,
        val propertiesRaw: Int,
        val propertiesLabel: String,
        val canRead: Boolean,
        val canWrite: Boolean,
        val canWriteNoResponse: Boolean,
        val canNotify: Boolean,
        val canIndicate: Boolean
    )

    companion object {
        private const val ROSE_CAMBRIAN_NAME = "rose cambrian"
        private const val OPENFEEL_MARKER = "openfeel"
        private const val MANUFACTURER_ID_0C0B = 0x0C0B
        private const val MANUFACTURER_ID_0A0B = 0x0A0B
        private const val MAC_PREFIX_41_42 = "41:42"

        private const val BATTERY_SERVICE_180F = "0000180f-0000-1000-8000-00805f9b34fb"
        private const val BATTERY_LEVEL_2A19 = "00002a19-0000-1000-8000-00805f9b34fb"
        private const val MANUAL_TARGET_MAC = "41:42:D3:16:6F:68"
        private const val CMD_1_HEX = "ff000ffa010708090c0d0e122a2b2c2d2e33aa"
        private const val CMD_2_HEX = "ff0002fa2baa"
    }

    private val clientConfigUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager.adapter
    private val bleScanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null
    private var currentGatt: BluetoothGatt? = null
    private var probeState: OpenFeelProbeState = OpenFeelProbeState()
    private var probeStateCallback: ((OpenFeelProbeState) -> Unit)? = null
    private var probeLogCallback: ((String) -> Unit)? = null
    private var splitBatteryExperimentEnabled: Boolean = false
    private var autoTriggerSplitBatteryOnReady: Boolean = false
    private val f2FrameInspector = OpenFeelF2FrameInspector()
    private var discoveredServicesDone: Boolean = false
    private var f1WriteCharacteristic: BluetoothGattCharacteristic? = null
    private var f2NotifyEnabled: Boolean = false
    @Volatile
    private var manualExperimentActive: Boolean = false
    @Volatile
    private var manualExperimentWindowEndAt: Long = 0L
    @Volatile
    private var manualExperimentNotifyCount: Int = 0
    @Volatile
    private var manualExperiment040CCount: Int = 0
    @Volatile
    private var manualExperimentLastParsed: ExperimentalSplitBattery? = null
    @Volatile
    private var manualExperimentLastNotifyAt: Long = 0L

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
        onDebugLog: ((String) -> Unit)? = null,
        onScanSeenDevice: ((ScannedDeviceObservation) -> Unit)? = null
    ): Boolean {
        if (!hasRequiredPermissions()) {
            onError("Missing Bluetooth scan permissions")
            return false
        }
        if (!isBluetoothEnabled()) {
            onError("Bluetooth is disabled")
            return false
        }

        val scanner = bleScanner ?: run {
            onError("BLE scan is not supported")
            return false
        }

        if (scanCallback != null) return true

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                emitScanObservation(result, onDebugLog, onScanSeenDevice)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result ->
                    emitScanObservation(result, onDebugLog, onScanSeenDevice)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                val message = "Scan failed errorCode=$errorCode"
                AppLogger.e("BleScannerManager", message)
                onDebugLog?.invoke("ScanFailed: $message")
                onError(message)
            }
        }

        scanCallback = callback
        scanner.startScan(emptyList(), settings, callback)
        onDebugLog?.invoke("BLE scan started with NO FILTER")
        AppLogger.d("BleScannerManager", "BLE scan started with NO FILTER")
        return true
    }

    fun stopScan() {
        val callback = scanCallback ?: return
        if (hasRequiredPermissions()) {
            bleScanner?.stopScan(callback)
        }
        scanCallback = null
        AppLogger.d("BleScannerManager", "BLE scan stopped")
    }

    fun readBattery(
        macAddress: String,
        enableSplitBatteryExperiment: Boolean = false,
        autoTriggerSplitBatteryAfterReady: Boolean = false,
        onLog: (String) -> Unit,
        onProbeState: (OpenFeelProbeState) -> Unit
    ): Boolean {
        if (!hasRequiredPermissions()) {
            onLog("probe_error missing permissions")
            return false
        }
        if (!isBluetoothEnabled()) {
            onLog("probe_error bluetooth disabled")
            return false
        }

        val adapter = bluetoothAdapter ?: return false
        val device = runCatching { adapter.getRemoteDevice(macAddress) }.getOrNull() ?: run {
            onLog("probe_error invalid mac=$macAddress")
            return false
        }

        disconnectBatterySession()

        probeLogCallback = onLog
        probeStateCallback = onProbeState
        splitBatteryExperimentEnabled = enableSplitBatteryExperiment
        autoTriggerSplitBatteryOnReady = autoTriggerSplitBatteryAfterReady
        discoveredServicesDone = false
        f1WriteCharacteristic = null
        f2NotifyEnabled = false

        updateProbeState(
            probeState.copy(
                targetMac = macAddress,
                isConnecting = true,
                isConnected = false,
                discoveredBattery180F2A19 = false,
                discoveredFEF1F2 = false,
                discoveredServiceFE = false,
                discoveredCharF1 = false,
                discoveredCharF2 = false,
                manualExperimentStatus = "实验未运行",
                lastEventAt = System.currentTimeMillis()
            )
        )

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, batteryGattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, batteryGattCallback)
        }

        currentGatt = gatt
        emitProbeLog("probe_connect start mac=$macAddress name=${device.name ?: "null"}")
        return gatt != null
    }

    fun disconnectBatterySession() {
        runCatching {
            currentGatt?.disconnect()
            currentGatt?.close()
        }
        currentGatt = null
        splitBatteryExperimentEnabled = false
        autoTriggerSplitBatteryOnReady = false
        discoveredServicesDone = false
        f1WriteCharacteristic = null
        f2NotifyEnabled = false
        manualExperimentActive = false
        manualExperimentWindowEndAt = 0L

        if (probeState.isConnected || probeState.isConnecting) {
            updateProbeState(
                probeState.copy(
                    isConnecting = false,
                    isConnected = false,
                    lastEventAt = System.currentTimeMillis()
                )
            )
        }
    }

    private val batteryGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            emitProbeLog(
                "probe_connection_state mac=${gatt.device?.address ?: "null"} status=$status newState=$newState"
            )

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateProbeState(
                        probeState.copy(
                            targetMac = gatt.device?.address,
                            isConnecting = false,
                            isConnected = true,
                            lastEventAt = System.currentTimeMillis()
                        )
                    )
                    val started = gatt.discoverServices()
                    emitProbeLog("probe_discover_services started=$started")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    updateProbeState(
                        probeState.copy(
                            isConnecting = false,
                            isConnected = false,
                            lastEventAt = System.currentTimeMillis()
                        )
                    )
                    runCatching { gatt.close() }
                    if (currentGatt == gatt) {
                        currentGatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            emitProbeLog("probe_services_discovered status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) return
            discoveredServicesDone = true

            var foundBattery180F2A19 = false
            var foundServiceFE = false
            var foundCharF1 = false
            var foundCharF2 = false
            var batteryCharacteristic: BluetoothGattCharacteristic? = null
            var batteryPropsRaw: Int? = null
            var batteryPropsLabel: String? = null
            val pendingNotifyEnable = mutableListOf<Pair<BluetoothGattCharacteristic, Boolean>>()

            gatt.services.forEach { service ->
                val serviceUuid = service.uuid.toString().lowercase(Locale.US)
                val serviceShort = uuidTo16BitOrNull(serviceUuid)
                emitProbeLog("probe_service uuid=$serviceUuid")

                if (serviceShort == 0x00FE) {
                    foundServiceFE = true
                }

                service.characteristics.forEach { characteristic ->
                    val info = buildCharacteristicInfo(characteristic)
                    val charShort = uuidTo16BitOrNull(info.characteristicUuid)

                    emitProbeLog(
                        "probe_char service=$serviceUuid char=${info.characteristicUuid} " +
                            "propsRaw=${info.propertiesRaw} props=${info.propertiesLabel}"
                    )

                    if (serviceUuid == BATTERY_SERVICE_180F && info.characteristicUuid == BATTERY_LEVEL_2A19) {
                        foundBattery180F2A19 = true
                        batteryCharacteristic = characteristic
                        batteryPropsRaw = info.propertiesRaw
                        batteryPropsLabel = info.propertiesLabel
                    }

                    if (serviceShort == 0x00FE && charShort == 0x00F1) {
                        foundCharF1 = true
                        f1WriteCharacteristic = characteristic
                        if (info.canWrite || info.canWriteNoResponse) {
                            emitProbeLog("probe_f1_writable true (no auto write)")
                        }
                    }

                    if (serviceShort == 0x00FE && charShort == 0x00F2) {
                        foundCharF2 = true
                        if (info.canNotify || info.canIndicate) {
                            pendingNotifyEnable.add(characteristic to info.canIndicate)
                        }
                    }
                }
            }

            val foundFEF1F2 = foundServiceFE && (foundCharF1 || foundCharF2)
            val targetBatteryCharacteristic = batteryCharacteristic
            if (targetBatteryCharacteristic != null) {
                val canRead = hasProperty(targetBatteryCharacteristic, BluetoothGattCharacteristic.PROPERTY_READ)
                val readRequested = if (canRead) gatt.readCharacteristic(targetBatteryCharacteristic) else false
                if (!readRequested) {
                    val isConnectedNow =
                        bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
                    val reason = when {
                        !canRead -> "missingReadProperty"
                        !isConnectedNow -> "notConnected"
                        else -> "gattBusyOrOperationInProgress"
                    }
                    emitProbeLog(
                        "probe_read_battery_180f requested=false reason=$reason " +
                            "uuid=${targetBatteryCharacteristic.uuid} " +
                            "propsRaw=${batteryPropsRaw ?: -1} propsParsed=${batteryPropsLabel ?: "unknown"}"
                    )
                } else {
                    emitProbeLog(
                        "probe_read_battery_180f requested=true " +
                            "uuid=${targetBatteryCharacteristic.uuid} " +
                            "propsRaw=${batteryPropsRaw ?: -1} propsParsed=${batteryPropsLabel ?: "unknown"}"
                    )
                }
            } else {
                emitProbeLog("probe_read_battery_180f requested=false reason=2A19NotFound")
            }

            pendingNotifyEnable.forEach { (characteristic, preferIndicate) ->
                enableNotification(gatt, characteristic, preferIndicate)
                emitProbeLog("probe_notify_f2_enabled service=0x00FE char=0x00F2")
            }

            updateProbeState(
                probeState.copy(
                    discoveredBattery180F2A19 = foundBattery180F2A19,
                    discoveredFEF1F2 = foundFEF1F2,
                    discoveredServiceFE = foundServiceFE,
                    discoveredCharF1 = foundCharF1,
                    discoveredCharF2 = foundCharF2,
                    lastEventAt = System.currentTimeMillis()
                )
            )
            emitProbeLog(
                "probe_signature discovered180F2A19=$foundBattery180F2A19 discoveredFEF1F2=$foundFEF1F2"
            )

            if (autoTriggerSplitBatteryOnReady) {
                autoTriggerSplitBatteryOnReady = false
                Thread {
                    Thread.sleep(220L)
                    val ok = triggerManualSplitBatteryExperiment()
                    emitProbeLog("exp_f1_write_result auto_trigger=$ok")
                }.start()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            emitProbeLog("probe_descriptor_write uuid=${descriptor.uuid} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val owner = descriptor.characteristic?.uuid?.toString()?.lowercase(Locale.US)
                if (owner != null && isF2Characteristic(owner)) {
                    f2NotifyEnabled = true
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicRead(characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(characteristic, value, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicChanged(characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            emitProbeLog(
                "exp_f1_write_result ts=${System.currentTimeMillis()} uuid=${characteristic.uuid} " +
                    "status=$status hex=${(characteristic.value ?: ByteArray(0)).toHexString("")}"
            )
        }
    }

    private fun handleCharacteristicRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        val characteristicUuid = characteristic.uuid.toString().lowercase(Locale.US)
        val hex = value.toHexString()
        emitProbeLog("probe_char_read uuid=$characteristicUuid status=$status hex=$hex")

        if (characteristicUuid == BATTERY_LEVEL_2A19 && value.isNotEmpty()) {
            val level = value[0].toInt() and 0xFF
            emitProbeLog("probe_battery_180f level=$level")
            updateProbeState(
                probeState.copy(
                    batteryLevelPercent = level,
                    lastBatteryUpdatedAt = System.currentTimeMillis(),
                    lastEventAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val characteristicUuid = characteristic.uuid.toString().lowercase(Locale.US)
        val hex = value.toHexString()
        emitProbeLog("probe_char_notify uuid=$characteristicUuid hex=$hex")
        if (splitBatteryExperimentEnabled && isF2Characteristic(characteristicUuid)) {
            emitF2ExperimentLogs(characteristicUuid, value, hex)
        }
        if (manualExperimentActive && isF2Characteristic(characteristicUuid)) {
            handleManualExperimentNotify(value, hex)
        }
        updateProbeState(probeState.copy(lastEventAt = System.currentTimeMillis()))
    }

    fun triggerManualSplitBatteryExperiment(): Boolean {
        val gatt = currentGatt
        if (gatt == null) {
            emitProbeLog("exp_f1_write_result ts=${System.currentTimeMillis()} success=false reason=no_gatt")
            updateProbeState(probeState.copy(manualExperimentStatus = "实验未运行"))
            return false
        }

        val deviceMac = gatt.device?.address?.uppercase(Locale.US) ?: ""
        if (deviceMac != MANUAL_TARGET_MAC) {
            emitProbeLog(
                "exp_f1_write_result ts=${System.currentTimeMillis()} success=false reason=target_mismatch mac=$deviceMac"
            )
            updateProbeState(probeState.copy(manualExperimentStatus = "实验未运行"))
            return false
        }

        if (!probeState.isConnected || !discoveredServicesDone) {
            emitProbeLog(
                "exp_f1_write_result ts=${System.currentTimeMillis()} success=false reason=not_ready connected=${probeState.isConnected} discovered=$discoveredServicesDone"
            )
            updateProbeState(probeState.copy(manualExperimentStatus = "实验未运行"))
            return false
        }

        val f1 = f1WriteCharacteristic
        if (f1 == null) {
            emitProbeLog("exp_f1_write_result ts=${System.currentTimeMillis()} success=false reason=f1_not_found")
            updateProbeState(probeState.copy(manualExperimentStatus = "实验未运行"))
            return false
        }
        if (!f2NotifyEnabled) {
            emitProbeLog("exp_f1_write_result ts=${System.currentTimeMillis()} success=false reason=f2_notify_not_enabled")
            updateProbeState(probeState.copy(manualExperimentStatus = "实验未运行"))
            return false
        }

        val cmd1 = CMD_1_HEX.hexToByteArrayOrNull()
        val cmd2 = CMD_2_HEX.hexToByteArrayOrNull()
        if (cmd1 == null || cmd2 == null) {
            emitProbeLog("exp_f1_write_result ts=${System.currentTimeMillis()} success=false reason=cmd_hex_invalid")
            updateProbeState(probeState.copy(manualExperimentStatus = "实验未运行"))
            return false
        }

        manualExperimentActive = true
        manualExperimentWindowEndAt = 0L
        manualExperimentNotifyCount = 0
        manualExperiment040CCount = 0
        manualExperimentLastParsed = null
        manualExperimentLastNotifyAt = 0L
        updateProbeState(probeState.copy(manualExperimentStatus = "已写入，等待回包"))

        Thread {
            val firstOk = writeKnownF1Command(gatt, f1, cmd1)
            if (!firstOk) {
                finalizeManualExperiment("未收到分电量帧")
                return@Thread
            }
            Thread.sleep(180)
            val secondOk = writeKnownF1Command(gatt, f1, cmd2)
            if (!secondOk) {
                finalizeManualExperiment("未收到分电量帧")
                return@Thread
            }
            val start = System.currentTimeMillis()
            manualExperimentWindowEndAt = start + 15_000L
            emitProbeLog(
                "exp_f2_after_write window_start=$start window_end=${manualExperimentWindowEndAt} targetMac=$deviceMac"
            )
            Thread.sleep(15_000L)
            val tailGap = System.currentTimeMillis() - manualExperimentLastNotifyAt
            if (manualExperimentLastNotifyAt > 0L && tailGap in 0..400L) {
                Thread.sleep(450L)
            }
            finalizeManualExperiment(
                status = if (manualExperiment040CCount > 0) "收到分电量帧" else "未收到分电量帧"
            )
        }.start()

        return true
    }

    private fun writeKnownF1Command(
        gatt: BluetoothGatt,
        f1: BluetoothGattCharacteristic,
        command: ByteArray
    ): Boolean {
        val ts = System.currentTimeMillis()
        val hex = command.toHexString("")
        emitProbeLog("exp_f1_write ts=$ts uuid=${f1.uuid} hex=$hex")
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = preferredWriteType(f1)
            gatt.writeCharacteristic(f1, command, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            f1.value = command
            f1.writeType = preferredWriteType(f1)
            gatt.writeCharacteristic(f1)
        }
        emitProbeLog("exp_f1_write_result ts=${System.currentTimeMillis()} requested=$success hex=$hex")
        return success
    }

    private fun preferredWriteType(f1: BluetoothGattCharacteristic): Int {
        return if (hasProperty(f1, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
    }

    private fun handleManualExperimentNotify(value: ByteArray, rawHex: String) {
        if (!manualExperimentActive) return
        val now = System.currentTimeMillis()
        if (manualExperimentWindowEndAt > 0L && now > manualExperimentWindowEndAt) return
        manualExperimentLastNotifyAt = now
        manualExperimentNotifyCount += 1
        emitProbeLog("exp_f2_after_write ts=$now hex=$rawHex")
        when (val parsed = f2FrameInspector.parse040CBatteryCandidate(value)) {
            is Parse040CResult.NotMatched -> Unit
            is Parse040CResult.Malformed -> {
                emitProbeLog(
                    "exp_f2_after_write parse_failed reason=${parsed.reason} len=${parsed.length} hex=$rawHex"
                )
            }
            is Parse040CResult.Matched -> {
                manualExperiment040CCount += 1
                val parsedState = ExperimentalSplitBattery(
                    sequenceOrChannel = parsed.sequenceOrChannel,
                    leftRaw = parsed.leftRaw,
                    rightRaw = parsed.rightRaw,
                    caseRaw = parsed.caseRaw,
                    leftBattery = parsed.leftBattery,
                    rightBattery = parsed.rightBattery,
                    caseBattery = parsed.caseBattery,
                    leftFlag = parsed.leftFlag,
                    rightFlag = parsed.rightFlag,
                    caseFlag = parsed.caseFlag,
                    tail = parsed.tail,
                    updatedAt = now
                )
                manualExperimentLastParsed = parsedState
                emitProbeLog(
                    "exp_f2_after_write hit_040c seq=${parsed.sequenceOrChannel.toString(16).uppercase(Locale.US).padStart(2, '0')} " +
                        "left=${parsed.leftBattery} right=${parsed.rightBattery} case=${parsed.caseBattery} " +
                        "leftFlag=${parsed.leftFlag} rightFlag=${parsed.rightFlag} caseFlag=${parsed.caseFlag}"
                )
                updateProbeState(
                    probeState.copy(
                        experimentalSplitBattery = parsedState,
                        manualExperimentStatus = "收到分电量帧",
                        lastEventAt = now
                    )
                )
            }
        }
    }

    @Synchronized
    private fun finalizeManualExperiment(status: String) {
        if (!manualExperimentActive) return
        manualExperimentActive = false
        val last = manualExperimentLastParsed
        emitProbeLog(
            "exp_f2_after_write_summary hasNotify=${manualExperimentNotifyCount > 0} " +
                "has040c=${manualExperiment040CCount > 0} notifyCount=$manualExperimentNotifyCount " +
                "hit040cCount=$manualExperiment040CCount " +
                "last=${if (last == null) "none" else "L=${last.leftBattery} R=${last.rightBattery} C=${last.caseBattery}"}"
        )
        updateProbeState(
            probeState.copy(
                manualExperimentStatus = status,
                lastEventAt = System.currentTimeMillis()
            )
        )
    }

    private fun isF2Characteristic(characteristicUuid: String): Boolean {
        return uuidTo16BitOrNull(characteristicUuid) == 0x00F2
    }

    private fun emitF2ExperimentLogs(
        characteristicUuid: String,
        bytes: ByteArray,
        rawHex: String
    ) {
        val now = System.currentTimeMillis()
        val totalBattery = probeState.batteryLevelPercent
        val connectionTag = when {
            probeState.isConnecting -> "reading"
            probeState.isConnected -> "connected"
            else -> "disconnected"
        }
        val sceneTag = buildString {
            append("sig180F=").append(probeState.discoveredBattery180F2A19)
            append("|sigFE=").append(probeState.discoveredFEF1F2)
            append("|f1=").append(probeState.discoveredCharF1)
            append("|f2=").append(probeState.discoveredCharF2)
        }
        emitProbeLog(
            "exp_f2_notify ts=$now tsFmt=${TimeUtils.format(now)} " +
                "mac=${probeState.targetMac ?: "null"} conn=$connectionTag battery=${totalBattery ?: -1} " +
                "scene=$sceneTag char=$characteristicUuid hex=$rawHex"
        )

        val frame = f2FrameInspector.inspect(bytes)
        emitProbeLog(
            "exp_f2_frame len=${frame.length} preamble=${frame.preambleHex} leading=${frame.leadingBytesHex} " +
                "type=${frame.suspectedType ?: -1} opcode=${frame.suspectedOpcode ?: -1} subOpcode=${frame.suspectedSubOpcode ?: -1} " +
                "payloadZone=${frame.payloadZoneHex} checksumZone=${frame.checksumZoneHex}"
        )
        emitProbeLog(
            "exp_f2_family match0E06=${frame.matches0E06} match0E02=${frame.matches0E02} " +
                "match0B01=${frame.matches0B01} has09FF=${frame.has09FFHeader} has08EE=${frame.has08EEHeader}"
        )

        when (val parsed = f2FrameInspector.parse040CBatteryCandidate(bytes)) {
            is Parse040CResult.NotMatched -> Unit
            is Parse040CResult.Malformed -> {
                emitProbeLog(
                    "exp_battery_040c_parse_failed reason=${parsed.reason} len=${parsed.length} hex=$rawHex"
                )
            }
            is Parse040CResult.Matched -> {
                emitProbeLog(
                    "exp_battery_040c seq=${parsed.sequenceOrChannel.toString(16).uppercase(Locale.US).padStart(2, '0')} " +
                        "leftRaw=${parsed.leftRaw.toString(16).uppercase(Locale.US).padStart(2, '0')} " +
                        "rightRaw=${parsed.rightRaw.toString(16).uppercase(Locale.US).padStart(2, '0')} " +
                        "caseRaw=${parsed.caseRaw.toString(16).uppercase(Locale.US).padStart(2, '0')} " +
                        "left=${parsed.leftBattery} right=${parsed.rightBattery} case=${parsed.caseBattery} " +
                        "leftFlag=${parsed.leftFlag} rightFlag=${parsed.rightFlag} caseFlag=${parsed.caseFlag} " +
                        "tail=${parsed.tail.toString(16).uppercase(Locale.US).padStart(2, '0')}"
                )
                updateProbeState(
                    probeState.copy(
                        experimentalSplitBattery = ExperimentalSplitBattery(
                            sequenceOrChannel = parsed.sequenceOrChannel,
                            leftRaw = parsed.leftRaw,
                            rightRaw = parsed.rightRaw,
                            caseRaw = parsed.caseRaw,
                            leftBattery = parsed.leftBattery,
                            rightBattery = parsed.rightBattery,
                            caseBattery = parsed.caseBattery,
                            leftFlag = parsed.leftFlag,
                            rightFlag = parsed.rightFlag,
                            caseFlag = parsed.caseFlag,
                            tail = parsed.tail,
                            updatedAt = now
                        ),
                        lastEventAt = now
                    )
                )
            }
        }
    }

    private fun enableNotification(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        preferIndicate: Boolean
    ) {
        val localSet = gatt.setCharacteristicNotification(characteristic, true)
        emitProbeLog("probe_set_notify uuid=${characteristic.uuid} localSet=$localSet")
        val charUuid = characteristic.uuid.toString().lowercase(Locale.US)
        if (localSet && isF2Characteristic(charUuid)) {
            f2NotifyEnabled = true
        }

        val descriptor = characteristic.getDescriptor(clientConfigUuid)
        if (descriptor == null) {
            emitProbeLog("probe_set_notify uuid=${characteristic.uuid} cccd_not_found")
            return
        }

        val enableValue = if (preferIndicate) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        val writeRequested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, enableValue) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = enableValue
            gatt.writeDescriptor(descriptor)
        }

        emitProbeLog(
            "probe_set_notify uuid=${characteristic.uuid} cccdWrite=$writeRequested " +
                "mode=${if (preferIndicate) "indicate" else "notify"}"
        )
    }

    private fun buildCharacteristicInfo(characteristic: BluetoothGattCharacteristic): CharacteristicInfo {
        val properties = characteristic.properties
        val canRead = hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_READ)
        val canWrite = hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE)
        val canWriteNoResponse = hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
        val canNotify = hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_NOTIFY)
        val canIndicate = hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_INDICATE)

        val labels = mutableListOf<String>()
        if (canRead) labels.add("read")
        if (canWrite) labels.add("write")
        if (canWriteNoResponse) labels.add("writeNoRsp")
        if (canNotify) labels.add("notify")
        if (canIndicate) labels.add("indicate")
        if (labels.isEmpty()) labels.add("none($properties)")

        return CharacteristicInfo(
            characteristicUuid = characteristic.uuid.toString().lowercase(Locale.US),
            propertiesRaw = properties,
            propertiesLabel = labels.joinToString("|"),
            canRead = canRead,
            canWrite = canWrite,
            canWriteNoResponse = canWriteNoResponse,
            canNotify = canNotify,
            canIndicate = canIndicate
        )
    }

    private fun emitScanObservation(
        result: ScanResult,
        onDebugLog: ((String) -> Unit)?,
        onScanSeenDevice: ((ScannedDeviceObservation) -> Unit)?
    ) {
        val deviceName = runCatching { result.device.name }.getOrNull()
        val macAddress = runCatching { result.device.address }.getOrNull()
        val lowerName = deviceName?.lowercase(Locale.US).orEmpty()
        val record = result.scanRecord
        val hasRecord = record != null
        val serviceUuids = record?.serviceData?.keys?.joinToString(",") { it.uuid.toString() } ?: "none"
        val hasManufacturerData = (record?.manufacturerSpecificData?.size() ?: 0) > 0

        val manufacturerIds = mutableSetOf<Int>()
        if (record != null && hasManufacturerData) {
            val data = record.manufacturerSpecificData
            for (index in 0 until data.size()) {
                manufacturerIds.add(data.keyAt(index))
            }
        }

        val matchReasons = linkedSetOf<String>()
        if (lowerName.contains(ROSE_CAMBRIAN_NAME)) {
            matchReasons.add("matchedByRoseCambrianName")
        }
        if (manufacturerIds.contains(MANUFACTURER_ID_0C0B)) {
            matchReasons.add("matchedByManufacturer0x0C0B")
        }
        if (manufacturerIds.contains(MANUFACTURER_ID_0A0B)) {
            matchReasons.add("matchedByManufacturer0x0A0B")
        }
        if (!macAddress.isNullOrBlank() && macAddress.uppercase(Locale.US).startsWith(MAC_PREFIX_41_42)) {
            matchReasons.add("matchedByMacPrefix41_42")
        }

        val isObservedFamily = matchReasons.isNotEmpty() ||
            lowerName.contains(OPENFEEL_MARKER) ||
            lowerName.contains(ROSE_CAMBRIAN_NAME)

        if (isObservedFamily) {
            val scanMessage =
                "scanResult name=${deviceName ?: "null"} mac=${macAddress ?: "null"} rssi=${result.rssi} " +
                    "scanRecordNull=${!hasRecord} serviceDataUuids=[$serviceUuids] hasManufacturerData=$hasManufacturerData"
            AppLogger.d("BleScannerManager", scanMessage)
            onDebugLog?.invoke(scanMessage)

            if (matchReasons.isNotEmpty()) {
                onDebugLog?.invoke(
                    "probe_candidate_scan name=${deviceName ?: "null"} mac=${macAddress ?: "null"} " +
                        "reasons=${matchReasons.joinToString("|")}"
                )
            }
        }

        if (!macAddress.isNullOrBlank() && matchReasons.isNotEmpty()) {
            val now = System.currentTimeMillis()
            onScanSeenDevice?.invoke(
                ScannedDeviceObservation(
                    deviceName = deviceName,
                    macAddress = macAddress,
                    firstSeenAt = now,
                    lastSeenAt = now,
                    lastRssi = result.rssi,
                    matchReasons = matchReasons
                )
            )
        }

        if (record != null && hasManufacturerData && isObservedFamily) {
            val data = record.manufacturerSpecificData
            val idsText = buildList {
                for (index in 0 until data.size()) {
                    add(String.format("0x%04X", data.keyAt(index)))
                }
            }.joinToString(",")

            onDebugLog?.invoke(
                "manufacturerIds=[$idsText] name=${deviceName ?: "null"} mac=${macAddress ?: "null"}"
            )

            for (index in 0 until data.size()) {
                val manufacturerId = data.keyAt(index)
                val bytes = data.valueAt(index) ?: ByteArray(0)
                onDebugLog?.invoke(
                    "manufacturerData id=${String.format("0x%04X", manufacturerId)} len=${bytes.size} " +
                        "hex=${bytes.toHexString()} name=${deviceName ?: "null"} mac=${macAddress ?: "null"}"
                )
            }
        }
    }

    private fun hasProperty(characteristic: BluetoothGattCharacteristic, propertyMask: Int): Boolean {
        return characteristic.properties and propertyMask != 0
    }

    private fun updateProbeState(newState: OpenFeelProbeState) {
        probeState = newState
        probeStateCallback?.invoke(newState)
    }

    private fun emitProbeLog(message: String) {
        AppLogger.d("BleScannerManager", message)
        probeLogCallback?.invoke(message)
    }

    private fun uuidTo16BitOrNull(uuidString: String): Int? {
        val lower = uuidString.lowercase(Locale.US)
        val suffix = "-0000-1000-8000-00805f9b34fb"
        if (!lower.endsWith(suffix)) return null
        val head = lower.substringBefore("-")
        if (head.length != 8) return null
        return head.substring(4, 8).toIntOrNull(16)
    }
}
