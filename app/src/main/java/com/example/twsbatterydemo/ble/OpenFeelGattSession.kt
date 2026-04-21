package com.example.twsbatterydemo.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.example.twsbatterydemo.model.BatteryReadUiState
import com.example.twsbatterydemo.protocol.OpenFeelBatteryParser
import com.example.twsbatterydemo.protocol.SplitBatteryFrame
import com.example.twsbatterydemo.util.AppLogger
import com.example.twsbatterydemo.util.toHexString
import java.util.Locale
import java.util.UUID

class OpenFeelGattSession(
    private val context: Context
) {

    private data class SplitRequestStats(
        val requestId: Long,
        val windowEndAt: Long,
        val notifyCount: Int = 0,
        val splitFrameCount: Int = 0,
        val lastNotifyAt: Long = 0L,
        val lastFrame: SplitBatteryFrame? = null
    )

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val clientConfigUuid: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val writeLogLock = Any()
    private val splitStatsLock = Any()
    private val pendingWriteHexes = ArrayDeque<String>()

    private var currentGatt: BluetoothGatt? = null
    private var currentState: BatteryReadUiState = BatteryReadUiState()
    private var logSink: ((String) -> Unit)? = null
    private var stateSink: ((BatteryReadUiState) -> Unit)? = null

    private var activeSessionId: Long = 0L
    private var activeSplitRequestId: Long = 0L

    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var splitWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var splitNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var splitNotifyReady: Boolean = false
    private var splitRequestScheduled: Boolean = false
    private var splitRequestStats: SplitRequestStats? = null

    fun startRefresh(
        macAddress: String,
        onLog: (String) -> Unit,
        onState: (BatteryReadUiState) -> Unit
    ): Boolean {
        disconnect()

        logSink = onLog
        stateSink = onState
        activeSessionId += 1
        batteryCharacteristic = null
        splitWriteCharacteristic = null
        splitNotifyCharacteristic = null
        splitNotifyReady = false
        splitRequestScheduled = false
        splitRequestStats = null
        synchronized(writeLogLock) {
            pendingWriteHexes.clear()
        }

        updateState(
            currentState.copy(
                isRefreshing = true,
                isConnected = false
            )
        )

        val adapter = bluetoothManager.adapter ?: run {
            emitLog("connection_start failed reason=no_adapter")
            updateState(currentState.copy(isRefreshing = false, isConnected = false))
            return false
        }

        val device = runCatching { adapter.getRemoteDevice(macAddress) }.getOrNull() ?: run {
            emitLog("connection_start failed reason=invalid_mac mac=$macAddress")
            updateState(currentState.copy(isRefreshing = false, isConnected = false))
            return false
        }

        currentGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        emitLog("connection_start mac=$macAddress name=${device.name ?: "null"}")
        return currentGatt != null
    }

    fun disconnect() {
        activeSessionId += 1
        activeSplitRequestId = 0L
        synchronized(splitStatsLock) {
            splitRequestStats = null
        }
        splitRequestScheduled = false
        splitNotifyReady = false
        batteryCharacteristic = null
        splitWriteCharacteristic = null
        splitNotifyCharacteristic = null
        synchronized(writeLogLock) {
            pendingWriteHexes.clear()
        }

        runCatching {
            currentGatt?.disconnect()
            currentGatt?.close()
        }
        currentGatt = null

        if (currentState.isConnected || currentState.isRefreshing) {
            updateState(
                currentState.copy(
                    isRefreshing = false,
                    isConnected = false
                )
            )
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentGatt(gatt)) return

            emitLog(
                "connection_state mac=${gatt.device.address} status=$status state=$newState"
            )

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateState(currentState.copy(isConnected = true))
                    val started = gatt.discoverServices()
                    emitLog("discover_services started=$started")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    currentGatt = null
                    synchronized(splitStatsLock) {
                        splitRequestStats = null
                    }
                    splitRequestScheduled = false
                    splitNotifyReady = false
                    batteryCharacteristic = null
                    splitWriteCharacteristic = null
                    splitNotifyCharacteristic = null
                    updateState(
                        currentState.copy(
                            isRefreshing = false,
                            isConnected = false
                        )
                    )
                    runCatching { gatt.close() }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLog("discover_services result=failed status=$status")
                updateState(currentState.copy(isRefreshing = false, isConnected = true))
                return
            }

            var hasBatteryService = false
            var hasPrivateService = false
            var canReadBattery = false
            var canWriteSplit = false
            var canNotifySplit = false

            gatt.services.forEach { service ->
                val serviceUuid = service.uuid.toString().lowercase(Locale.US)
                if (serviceUuid == OpenFeelBatteryParser.BATTERY_SERVICE_UUID) {
                    hasBatteryService = true
                }
                if (OpenFeelBatteryParser.isPrivateService(serviceUuid)) {
                    hasPrivateService = true
                }

                service.characteristics.forEach { characteristic ->
                    val characteristicUuid = characteristic.uuid.toString().lowercase(Locale.US)

                    if (OpenFeelBatteryParser.isBatteryLevelCharacteristic(characteristicUuid)) {
                        batteryCharacteristic = characteristic
                        canReadBattery = hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_READ)
                    }

                    if (OpenFeelBatteryParser.isPrivateWriteCharacteristic(characteristicUuid)) {
                        splitWriteCharacteristic = characteristic
                        canWriteSplit =
                            hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE) ||
                            hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                    }

                    if (OpenFeelBatteryParser.isPrivateNotifyCharacteristic(characteristicUuid)) {
                        splitNotifyCharacteristic = characteristic
                        canNotifySplit =
                            hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_NOTIFY) ||
                            hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_INDICATE)
                    }
                }
            }

            emitLog("gatt_services battery180f=$hasBatteryService privateFe=$hasPrivateService")
            emitLog("gatt_chars batteryRead=$canReadBattery splitWrite=$canWriteSplit splitNotify=$canNotifySplit")

            requestBatteryLevel(gatt)

            if (splitNotifyCharacteristic != null && canNotifySplit) {
                enableSplitNotify(gatt, splitNotifyCharacteristic!!)
            } else {
                emitLog("split_request skipped reason=notify_characteristic_unavailable")
            }

            splitRequestScheduled = splitWriteCharacteristic != null && canWriteSplit
            updateState(currentState.copy(isRefreshing = false, isConnected = true))
            requestSplitBatteryIfReady()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrentGatt(gatt)) return

            val ownerUuid = descriptor.characteristic.uuid.toString().lowercase(Locale.US)
            if (OpenFeelBatteryParser.isPrivateNotifyCharacteristic(ownerUuid)) {
                splitNotifyReady = status == BluetoothGatt.GATT_SUCCESS
                emitLog("notify_enable uuid=$ownerUuid status=$status")
                requestSplitBatteryIfReady()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return
            handleCharacteristicRead(characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return
            handleCharacteristicRead(characteristic, value, status)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (!isCurrentGatt(gatt)) return
            handleCharacteristicChanged(characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isCurrentGatt(gatt)) return
            handleCharacteristicChanged(characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) return
            val hex = synchronized(writeLogLock) {
                if (pendingWriteHexes.isEmpty()) null else pendingWriteHexes.removeFirst()
            }
            emitLog(
                "exp_f1_write_result ts=${System.currentTimeMillis()} uuid=${characteristic.uuid} " +
                    "status=$status hex=${hex ?: "unknown"}"
            )
        }
    }

    private fun requestBatteryLevel(gatt: BluetoothGatt) {
        val characteristic = batteryCharacteristic
        if (characteristic == null) {
            emitLog("battery_read requested=false reason=characteristic_not_found")
            return
        }

        val canRead = hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_READ)
        if (!canRead) {
            emitLog("battery_read requested=false reason=missing_read_property")
            return
        }

        val requested = gatt.readCharacteristic(characteristic)
        emitLog("battery_read requested=$requested uuid=${characteristic.uuid}")
    }

    private fun enableSplitNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val localSet = gatt.setCharacteristicNotification(characteristic, true)
        emitLog("notify_enable localSet=$localSet uuid=${characteristic.uuid}")

        val descriptor = characteristic.getDescriptor(clientConfigUuid)
        if (descriptor == null) {
            emitLog("notify_enable skipped reason=cccd_not_found uuid=${characteristic.uuid}")
            return
        }

        val enableValue = if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, enableValue) == BluetoothStatusCodes.SUCCESS
        } else {
            descriptor.value = enableValue
            gatt.writeDescriptor(descriptor)
        }

        emitLog("notify_enable requested=$requested uuid=${characteristic.uuid}")
    }

    private fun requestSplitBatteryIfReady() {
        val gatt = currentGatt ?: return
        val characteristic = splitWriteCharacteristic ?: return
        if (!splitRequestScheduled || !splitNotifyReady) return

        splitRequestScheduled = false
        activeSplitRequestId += 1
        val requestId = activeSplitRequestId
        val windowEndAt = System.currentTimeMillis() + 15_500L
        synchronized(splitStatsLock) {
            splitRequestStats = SplitRequestStats(requestId = requestId, windowEndAt = windowEndAt)
        }

        Thread {
            val commands = OpenFeelBatteryParser.splitBatteryCommands()
            commands.forEachIndexed { index, command ->
                if (requestId != activeSplitRequestId || !isCurrentGatt(gatt)) return@Thread
                writeSplitCommand(gatt, characteristic, command)
                if (index == 0) {
                    Thread.sleep(180L)
                }
            }

            emitLog("split_request windowEndAt=$windowEndAt")
            val delay = (windowEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
            Thread.sleep(delay)

            val stats = synchronized(splitStatsLock) { splitRequestStats }
            if (stats != null && stats.requestId == requestId) {
                val tailGap = System.currentTimeMillis() - stats.lastNotifyAt
                if (stats.lastNotifyAt > 0L && tailGap in 0..400L) {
                    Thread.sleep(450L)
                }
            }
            finalizeSplitRequest(requestId)
        }.start()
    }

    private fun writeSplitCommand(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        command: ByteArray
    ) {
        val hex = command.toHexString("")
        synchronized(writeLogLock) {
            pendingWriteHexes.addLast(hex)
        }

        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = preferredWriteType(characteristic)
            gatt.writeCharacteristic(characteristic, command, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.value = command
            characteristic.writeType = preferredWriteType(characteristic)
            gatt.writeCharacteristic(characteristic)
        }

        emitLog(
            "exp_f1_write ts=${System.currentTimeMillis()} uuid=${characteristic.uuid} " +
                "requested=$requested hex=$hex"
        )
    }

    private fun handleCharacteristicRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        val characteristicUuid = characteristic.uuid.toString().lowercase(Locale.US)
        val hex = value.toHexString()

        emitLog("battery_read_result uuid=$characteristicUuid status=$status hex=$hex")

        if (status != BluetoothGatt.GATT_SUCCESS) return
        if (!OpenFeelBatteryParser.isBatteryLevelCharacteristic(characteristicUuid)) return

        val batteryLevel = OpenFeelBatteryParser.parseBatteryLevel(value) ?: return
        val updatedAt = System.currentTimeMillis()
        emitLog("battery_value total=$batteryLevel")
        updateState(
            currentState.copy(
                totalBatteryPercent = batteryLevel,
                lastUpdatedAt = updatedAt
            )
        )
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val characteristicUuid = characteristic.uuid.toString().lowercase(Locale.US)
        if (!OpenFeelBatteryParser.isPrivateNotifyCharacteristic(characteristicUuid)) return

        val rawHex = value.toHexString()
        emitLog("f2_notify ts=${System.currentTimeMillis()} hex=$rawHex")

        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(value)
        if (parsed != null) {
            val updatedAt = System.currentTimeMillis()
            emitLog(
                "split_battery seq=${toHexByte(parsed.sequence)} " +
                    "left=${parsed.leftBattery} right=${parsed.rightBattery} case=${parsed.caseBattery}"
            )
            updateState(
                currentState.copy(
                    leftBatteryPercent = parsed.leftBattery,
                    rightBatteryPercent = parsed.rightBattery,
                    caseBatteryPercent = parsed.caseBattery,
                    lastUpdatedAt = updatedAt
                )
            )
        }

        val stats = synchronized(splitStatsLock) { splitRequestStats } ?: return
        if (System.currentTimeMillis() > stats.windowEndAt) return

        synchronized(splitStatsLock) {
            splitRequestStats = stats.copy(
                notifyCount = stats.notifyCount + 1,
                splitFrameCount = stats.splitFrameCount + if (parsed != null) 1 else 0,
                lastNotifyAt = System.currentTimeMillis(),
                lastFrame = parsed ?: stats.lastFrame
            )
        }
    }

    @Synchronized
    private fun finalizeSplitRequest(requestId: Long) {
        val stats = synchronized(splitStatsLock) { splitRequestStats } ?: return
        if (stats.requestId != requestId) return

        emitLog(
            "split_summary hasNotify=${stats.notifyCount > 0} has040c=${stats.splitFrameCount > 0} " +
                "notifyCount=${stats.notifyCount} frameCount=${stats.splitFrameCount} " +
                "last=${formatLastFrame(stats.lastFrame)}"
        )
        synchronized(splitStatsLock) {
            splitRequestStats = null
        }
    }

    private fun preferredWriteType(characteristic: BluetoothGattCharacteristic): Int {
        return if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
    }

    private fun hasProperty(characteristic: BluetoothGattCharacteristic, propertyMask: Int): Boolean {
        return characteristic.properties and propertyMask != 0
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean {
        return currentGatt === gatt
    }

    private fun updateState(newState: BatteryReadUiState) {
        currentState = newState
        stateSink?.invoke(newState)
    }

    private fun emitLog(message: String) {
        AppLogger.d("OpenFeelGattSession", message)
        logSink?.invoke(message)
    }

    private fun formatLastFrame(frame: SplitBatteryFrame?): String {
        if (frame == null) return "none"
        return "L=${frame.leftBattery} R=${frame.rightBattery} C=${frame.caseBattery}"
    }

    private fun toHexByte(value: Int): String {
        return value.toString(16).uppercase(Locale.US).padStart(2, '0')
    }
}
