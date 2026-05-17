package com.example.twsbatterydemo.ble

import android.Manifest
import android.annotation.SuppressLint
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
import android.content.pm.PackageManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.twsbatterydemo.model.BatteryReadUiState
import com.example.twsbatterydemo.protocol.OpenFeelBatteryParser
import com.example.twsbatterydemo.protocol.SplitBatteryFrame
import com.example.twsbatterydemo.util.AppLogger
import com.example.twsbatterydemo.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class OpenFeelGattSession(
    private val context: Context
) {
    companion object {
        private const val UI_SPLIT_WAIT_TIMEOUT_MS = 2_500L
        private const val SPLIT_OBSERVE_WINDOW_MS = 8_000L
    }

    private data class RefreshCapabilities(
        val canReadBattery: Boolean,
        val canWriteSplit: Boolean,
        val canNotifySplit: Boolean
    )

    private data class PendingWriteLog(
        val sequence: Int,
        val hex: String
    )

    private data class SplitRequestStats(
        val requestId: Long,
        val windowEndAt: Long,
        val notifyCount: Int = 0,
        val splitFrameCount: Int = 0,
        val writeRequestCount: Int = 0,
        val writeRequestSuccessCount: Int = 0,
        val lastNotifyAt: Long = 0L,
        val lastFrame: SplitBatteryFrame? = null
    )

    private sealed interface RefreshPipelineState {
        data object Idle : RefreshPipelineState
        data class Connecting(
            val token: Long,
            val startedAt: Long
        ) : RefreshPipelineState
        data class Ready(
            val token: Long,
            val startedAt: Long,
            val caps: RefreshCapabilities,
            val isNotifyReady: Boolean
        ) : RefreshPipelineState
        data class SplitObserving(
            val token: Long,
            val startedAt: Long,
            val requestId: Long,
            val caps: RefreshCapabilities,
            val windowEndAt: Long,
            val firstFrameAtMs: Long? = null
        ) : RefreshPipelineState
    }

    private data class RefreshUiState(
        val isRefreshing: Boolean = false
    )

    private val bluetoothManager: BluetoothManager =
        context.getSystemService(BluetoothManager::class.java)
    private val clientConfigUuid: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val writeLogLock = Any()
    private val splitStatsLock = Any()
    private val pendingWriteLogs = ArrayDeque<PendingWriteLog>()

    private var currentGatt: BluetoothGatt? = null
    private var currentState: BatteryReadUiState = BatteryReadUiState()
    private var logSink: ((String) -> Unit)? = null
    private var stateSink: ((BatteryReadUiState) -> Unit)? = null
    @Volatile
    private var pipelineState: RefreshPipelineState = RefreshPipelineState.Idle
    private var uiState: RefreshUiState = RefreshUiState()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var uiTimeoutJob: Job? = null
    private var notifyFallbackJob: Job? = null
    private var splitRequestJob: Job? = null
    private var batteryReadGateJob: Job? = null

    private var activeSplitRequestId: Long = 0L

    private var batteryCharacteristic: BluetoothGattCharacteristic? = null
    private var splitWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var splitNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var splitNotifyReady: Boolean = false
    private var splitRequestScheduled: Boolean = false
    private var splitRequestStats: SplitRequestStats? = null
    private var cachedCapabilities = RefreshCapabilities(
        canReadBattery = false,
        canWriteSplit = false,
        canNotifySplit = false
    )
    private var servicesReady = false
    private var activeRefreshToken: Long = 0L
    private var activeRefreshStartAt: Long = 0L
    @Volatile
    private var uiRefreshCompleted: Boolean = false
    @Volatile
    private var splitTriggerStarted: Boolean = false
    @Volatile
    private var splitFirstFrameAtMs: Long? = null
    @Volatile
    private var refreshInFlight: Boolean = false
    @Volatile
    private var batteryReadGateOpen: Boolean = false
    @Volatile
    private var splitPendingBatteryGate: Boolean = false

    fun startRefresh(
        macAddress: String,
        onLog: (String) -> Unit,
        onState: (BatteryReadUiState) -> Unit
    ): Boolean {
        if (refreshInFlight) {
            onLog("refresh_pipeline_skip reason=in_flight")
            return false
        }
        logSink = onLog
        stateSink = onState
        setRefreshInFlightAndButtonBusy(true)
        activeRefreshToken += 1
        activeRefreshStartAt = System.currentTimeMillis()
        uiRefreshCompleted = false
        splitTriggerStarted = false
        splitFirstFrameAtMs = null
        splitRequestScheduled = false
        splitPendingBatteryGate = false
        batteryReadGateOpen = false
        splitRequestStats = null
        synchronized(writeLogLock) { pendingWriteLogs.clear() }
        uiState = RefreshUiState(isRefreshing = true)
        clearScheduledJobs()

        val normalizedMac = macAddress.uppercase(Locale.US)
        val reusableReason = reusableReason(normalizedMac)
        val reusable = reusableReason == null
        emitLog("refresh_pipeline_start mac=$normalizedMac token=$activeRefreshToken refreshId=$activeRefreshToken")
        emitLog("refresh_reuse_session=$reusable${if (reusableReason != null) " reason=$reusableReason" else ""} refreshId=$activeRefreshToken")
        scheduleUiCompleteTimeout(activeRefreshToken)

        if (reusable) {
            val gatt = currentGatt ?: run {
                setRefreshInFlightAndButtonBusy(false)
                return false
            }
            updateState(currentState.copy(isRefreshing = true, isConnected = true))
            transitionTo(
                RefreshPipelineState.Ready(
                    token = activeRefreshToken,
                    startedAt = activeRefreshStartAt,
                    caps = cachedCapabilities,
                    isNotifyReady = splitNotifyReady
                )
            )
            requestBatteryLevel(gatt)
            splitRequestScheduled = cachedCapabilities.canWriteSplit && splitWriteCharacteristic != null
            if (splitNotifyReady) {
                requestSplitBatteryIfReady()
            } else {
                val notifyChar = splitNotifyCharacteristic
                if (notifyChar != null && cachedCapabilities.canNotifySplit) {
                    enableSplitNotify(gatt, notifyChar)
                    scheduleNotifyReadyFallback(activeRefreshToken)
                } else {
                    emitLog(capabilityMissingSummary(cachedCapabilities))
                    setRefreshInFlightAndButtonBusy(false)
                }
            }
            return true
        }

        resetSessionForReconnect()
        updateState(currentState.copy(isRefreshing = true, isConnected = false))

        val adapter = bluetoothManager.adapter ?: run {
            emitLog("connection_start failed reason=no_adapter")
            updateState(currentState.copy(isRefreshing = false, isConnected = false))
            completeUiRefreshIfNeeded("no_adapter")
            setRefreshInFlightAndButtonBusy(false)
            return false
        }

        val device = runCatching { adapter.getRemoteDevice(normalizedMac) }.getOrNull() ?: run {
            emitLog("connection_start failed reason=invalid_mac mac=$normalizedMac")
            updateState(currentState.copy(isRefreshing = false, isConnected = false))
            completeUiRefreshIfNeeded("invalid_mac")
            setRefreshInFlightAndButtonBusy(false)
            return false
        }

        currentGatt = connectGattSafely(device)
        emitLog("connection_start mac=$normalizedMac name=${safeDeviceName(device)} refreshId=${currentRefreshId()}")
        val started = currentGatt != null
        if (started) {
            transitionTo(
                RefreshPipelineState.Connecting(
                    token = activeRefreshToken,
                    startedAt = activeRefreshStartAt
                )
            )
        }
        if (!started) {
            emitLog("refresh_pipeline_summary result=failed reason=connectGatt_returned_null refreshId=${currentRefreshId()}")
            completeUiRefreshIfNeeded("connect_failed")
            setRefreshInFlightAndButtonBusy(false)
        }
        return started
    }

    fun disconnect() {
        clearScheduledJobs()
        activeSplitRequestId = 0L
        synchronized(splitStatsLock) {
            splitRequestStats = null
        }
        splitRequestScheduled = false
        splitPendingBatteryGate = false
        batteryReadGateOpen = false
        splitNotifyReady = false
        servicesReady = false
        cachedCapabilities = RefreshCapabilities(
            canReadBattery = false,
            canWriteSplit = false,
            canNotifySplit = false
        )
        batteryCharacteristic = null
        splitWriteCharacteristic = null
        splitNotifyCharacteristic = null
        synchronized(writeLogLock) {
            pendingWriteLogs.clear()
        }

        runCatching {
            currentGatt?.let { disconnectGattSafely(it) }
            currentGatt?.let { closeGattSafely(it) }
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
        setRefreshInFlightAndButtonBusy(false)
        uiRefreshCompleted = false
        splitTriggerStarted = false
        splitFirstFrameAtMs = null
        splitPendingBatteryGate = false
        batteryReadGateOpen = false
        transitionTo(RefreshPipelineState.Idle)
    }

    fun isRefreshInFlight(): Boolean = refreshInFlight

    private fun setRefreshInFlightAndButtonBusy(inFlight: Boolean) {
        refreshInFlight = inFlight
        updateState(currentState.copy(isRefreshButtonBusy = inFlight))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentGatt(gatt)) return

            emitLog(
                "connection_state mac=${gatt.device.address} status=$status state=$newState refreshId=${currentRefreshId()}"
            )

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    updateState(currentState.copy(isConnected = true))
                    val started = discoverServicesSafely(gatt)
                    emitLog("discover_services started=$started refreshId=${currentRefreshId()}")
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
                    completeUiRefreshIfNeeded("disconnected")
                    setRefreshInFlightAndButtonBusy(false)
                    servicesReady = false
                    clearScheduledJobs()
                    closeGattSafely(gatt)
                    transitionTo(RefreshPipelineState.Idle)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitLog("discover_services result=failed status=$status refreshId=${currentRefreshId()}")
                updateState(currentState.copy(isRefreshing = false, isConnected = true))
                emitLog("refresh_pipeline_summary result=failed reason=discover_services_failed status=$status refreshId=${currentRefreshId()}")
                completeUiRefreshIfNeeded("discover_failed")
                setRefreshInFlightAndButtonBusy(false)
                return
            }

            servicesReady = true

            var canReadBattery = false
            var canWriteSplit = false
            var canNotifySplit = false

            gatt.services.forEach { service ->
                val serviceUuid = service.uuid.toString().lowercase(Locale.US)
                val isRelevantService = serviceUuid == OpenFeelBatteryParser.BATTERY_SERVICE_UUID ||
                    OpenFeelBatteryParser.isPrivateService(serviceUuid)
                if (!isRelevantService) return@forEach

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

            val capabilities = RefreshCapabilities(
                canReadBattery = canReadBattery,
                canWriteSplit = canWriteSplit,
                canNotifySplit = canNotifySplit
            )
            emitLog(
                "capability_detected batteryRead=${capabilities.canReadBattery} " +
                    "splitWrite=${capabilities.canWriteSplit} splitNotify=${capabilities.canNotifySplit} " +
                    "refreshId=${currentRefreshId()}"
            )
            cachedCapabilities = capabilities
            requestBatteryLevel(gatt)
            splitRequestScheduled = capabilities.canWriteSplit && splitWriteCharacteristic != null
            (pipelineState as? RefreshPipelineState.Connecting)?.let { connecting ->
                transitionTo(
                    RefreshPipelineState.Ready(
                        token = connecting.token,
                        startedAt = connecting.startedAt,
                        caps = capabilities,
                        isNotifyReady = false
                    )
                )
            }

            if (splitNotifyCharacteristic != null && capabilities.canNotifySplit) {
                enableSplitNotify(gatt, splitNotifyCharacteristic!!)
                scheduleNotifyReadyFallback(activeRefreshToken)
            } else {
                emitLog("f2_notify_enable_result requested=false reason=notify_characteristic_unavailable refreshId=${currentRefreshId()}")
            }

            updateState(currentState.copy(isRefreshing = true, isConnected = true))
            if (splitRequestScheduled && capabilities.canNotifySplit) {
                requestSplitBatteryIfReady()
            } else {
                emitLog(capabilityMissingSummary(capabilities))
                setRefreshInFlightAndButtonBusy(false)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (!isCurrentGatt(gatt)) return

            val ownerUuid = descriptor.characteristic.uuid.toString().lowercase(Locale.US)
            if (OpenFeelBatteryParser.isPrivateNotifyCharacteristic(ownerUuid)) {
                splitNotifyReady = status == BluetoothGatt.GATT_SUCCESS
                (pipelineState as? RefreshPipelineState.Ready)?.let { ready ->
                    transitionTo(ready.copy(isNotifyReady = splitNotifyReady))
                }
                emitLog("f2_notify_enable_result requested=true status=$status uuid=$ownerUuid refreshId=${currentRefreshId()}")
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
            val pending = synchronized(writeLogLock) {
                if (pendingWriteLogs.isEmpty()) null else pendingWriteLogs.removeFirst()
            }
            emitLog(
                "f1_write_${pending?.sequence ?: -1}_result status=$status " +
                    "uuid=${characteristic.uuid} hex=${pending?.hex ?: "unknown"} refreshId=${currentRefreshId()}"
            )
        }
    }

    private fun requestBatteryLevel(gatt: BluetoothGatt) {
        val characteristic = batteryCharacteristic
        if (characteristic == null) {
            emitLog("battery_read requested=false reason=characteristic_not_found refreshId=${currentRefreshId()}")
            openBatteryReadGate("no_characteristic")
            return
        }

        val canRead = hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_READ)
        if (!canRead) {
            emitLog("battery_read requested=false reason=missing_read_property refreshId=${currentRefreshId()}")
            openBatteryReadGate("missing_read_property")
            return
        }

        val queued = readCharacteristicSafely(gatt, characteristic)
        emitLog("battery_read_start queued=$queued uuid=${characteristic.uuid} refreshId=${currentRefreshId()}")
        if (queued) {
            scheduleBatteryReadGateTimeout(activeRefreshToken)
        } else {
            openBatteryReadGate("read_not_queued")
        }
    }

    private fun enableSplitNotify(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val localSet = setCharacteristicNotificationSafely(gatt, characteristic, true)
        emitLog("f2_notify_enable_start uuid=${characteristic.uuid} localSet=$localSet refreshId=${currentRefreshId()}")

        val descriptor = characteristic.getDescriptor(clientConfigUuid)
        if (descriptor == null) {
            splitNotifyReady = localSet
            (pipelineState as? RefreshPipelineState.Ready)?.let { ready ->
                transitionTo(ready.copy(isNotifyReady = splitNotifyReady))
            }
            emitLog("f2_notify_enable_result requested=false reason=cccd_not_found fallbackReady=$splitNotifyReady uuid=${characteristic.uuid} refreshId=${currentRefreshId()}")
            requestSplitBatteryIfReady()
            return
        }

        val enableValue = if (hasProperty(characteristic, BluetoothGattCharacteristic.PROPERTY_INDICATE)) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }

        val requested = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            writeDescriptorSafely(gatt, descriptor, enableValue)
        } else {
            descriptor.value = enableValue
            writeDescriptorSafely(gatt, descriptor)
        }

        if (!requested && localSet) {
            splitNotifyReady = true
            (pipelineState as? RefreshPipelineState.Ready)?.let { ready ->
                transitionTo(ready.copy(isNotifyReady = splitNotifyReady))
            }
            emitLog("f2_notify_enable_result requested=false reason=descriptor_write_not_requested fallbackReady=true uuid=${characteristic.uuid} refreshId=${currentRefreshId()}")
            requestSplitBatteryIfReady()
        } else if (!requested) {
            emitLog("f2_notify_enable_result requested=$requested uuid=${characteristic.uuid} refreshId=${currentRefreshId()}")
        }
    }

    private fun requestSplitBatteryIfReady() {
        val gatt = currentGatt ?: return
        val characteristic = splitWriteCharacteristic ?: return
        if (!splitRequestScheduled || !splitNotifyReady) return
        if (!batteryReadGateOpen) {
            if (!splitPendingBatteryGate) {
                splitPendingBatteryGate = true
                emitLog("split_wait_battery_read_gate requestId=pending refreshId=${currentRefreshId()}")
            }
            return
        }

        splitRequestScheduled = false
        splitPendingBatteryGate = false
        splitTriggerStarted = true
        activeSplitRequestId += 1
        val requestId = activeSplitRequestId
        val windowEndAt = System.currentTimeMillis() + SPLIT_OBSERVE_WINDOW_MS
        (pipelineState as? RefreshPipelineState.Ready)?.let { ready ->
            transitionTo(
                RefreshPipelineState.SplitObserving(
                    token = ready.token,
                    startedAt = ready.startedAt,
                    requestId = requestId,
                    caps = ready.caps,
                    windowEndAt = windowEndAt
                )
            )
        }
        synchronized(splitStatsLock) {
            splitRequestStats = SplitRequestStats(requestId = requestId, windowEndAt = windowEndAt)
        }
        emitLog("split_trigger_start requestId=$requestId windowMs=$SPLIT_OBSERVE_WINDOW_MS refreshId=${currentRefreshId()}")

        splitRequestJob?.cancel()
        splitRequestJob = sessionScope.launch {
            val commands = OpenFeelBatteryParser.splitBatteryCommands()
            for (index in commands.indices) {
                val command = commands[index]
                if (requestId != activeSplitRequestId || !isCurrentGatt(gatt)) return@launch
                writeSplitCommand(gatt, characteristic, command, index + 1, requestId)
                if (index == 0) {
                    delay(180L)
                }
            }

            val observeDelay = (windowEndAt - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(observeDelay)

            val stats = synchronized(splitStatsLock) { splitRequestStats }
            if (stats != null && stats.requestId == requestId) {
                val tailGap = System.currentTimeMillis() - stats.lastNotifyAt
                if (stats.lastNotifyAt > 0L && tailGap in 0..400L) {
                    delay(450L)
                }
            }
            finalizeSplitRequest(requestId)
        }
    }

    private fun writeSplitCommand(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        command: ByteArray,
        sequence: Int,
        requestId: Long
    ) {
        val hex = command.toHexString("")
        synchronized(writeLogLock) {
            pendingWriteLogs.addLast(PendingWriteLog(sequence = sequence, hex = hex))
        }

        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeType = preferredWriteType(characteristic)
            writeCharacteristicSafely(gatt, characteristic, command, writeType)
        } else {
            characteristic.value = command
            characteristic.writeType = preferredWriteType(characteristic)
            writeCharacteristicSafely(gatt, characteristic)
        }
        synchronized(splitStatsLock) {
            val stats = splitRequestStats
            if (stats != null && stats.requestId == requestId) {
                splitRequestStats = stats.copy(
                    writeRequestCount = stats.writeRequestCount + 1,
                    writeRequestSuccessCount = stats.writeRequestSuccessCount + if (queued) 1 else 0
                )
            }
        }

        emitLog(
            "f1_write_${sequence}_queue queued=$queued uuid=${characteristic.uuid} hex=$hex requestId=$requestId refreshId=${currentRefreshId()}"
        )
    }

    private fun handleCharacteristicRead(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ) {
        val characteristicUuid = characteristic.uuid.toString().lowercase(Locale.US)
        val hex = value.toHexString()

        emitLog("battery_read_result uuid=$characteristicUuid status=$status hex=$hex refreshId=${currentRefreshId()}")

        if (status != BluetoothGatt.GATT_SUCCESS) return
        if (!OpenFeelBatteryParser.isBatteryLevelCharacteristic(characteristicUuid)) return

        val batteryLevel = OpenFeelBatteryParser.parseBatteryLevel(value) ?: return
        val updatedAt = System.currentTimeMillis()
        emitLog("battery_value total=$batteryLevel refreshId=${currentRefreshId()}")
        updateState(
            currentState.copy(
                totalBatteryPercent = batteryLevel,
                lastUpdatedAt = updatedAt
            )
        )
        openBatteryReadGate("read_result")
    }

    private fun handleCharacteristicChanged(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val characteristicUuid = characteristic.uuid.toString().lowercase(Locale.US)
        if (!OpenFeelBatteryParser.isPrivateNotifyCharacteristic(characteristicUuid)) return

        val rawHex = value.toHexString()
        emitLog("f2_raw_notify ts=${System.currentTimeMillis()} hex=$rawHex refreshId=${currentRefreshId()}")

        val parsed = OpenFeelBatteryParser.parseSplitBatteryFrame(value)
        if (parsed != null) {
            val updatedAt = System.currentTimeMillis()
            val statsAtFrame = synchronized(splitStatsLock) { splitRequestStats }
            val receiveSource = when {
                statsAtFrame == null -> "passive"
                statsAtFrame.writeRequestSuccessCount > 0 -> "active_trigger"
                statsAtFrame.writeRequestCount > 0 -> "passive_after_queue_failed"
                else -> "passive"
            }
            if (splitFirstFrameAtMs == null) {
                splitFirstFrameAtMs = (updatedAt - activeRefreshStartAt).coerceAtLeast(0L)
                (pipelineState as? RefreshPipelineState.SplitObserving)?.let { observing ->
                    transitionTo(observing.copy(firstFrameAtMs = splitFirstFrameAtMs))
                }
                completeUiRefreshIfNeeded("split_frame")
            }
            emitLog(
                "split_040c_parsed seq=${toHexByte(parsed.sequence)} " +
                    "left=${parsed.leftBattery} right=${parsed.rightBattery} case=${parsed.caseBattery} " +
                    "source=$receiveSource refreshId=${currentRefreshId()}"
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

        val observingState = pipelineState as? RefreshPipelineState.SplitObserving
        val splitTriggeredForSummary = observingState != null
        val firstFrameAtForSummary = observingState?.firstFrameAtMs ?: -1L
        emitLog(
            "refresh_pipeline_summary result=completed splitTriggered=$splitTriggeredForSummary hasNotify=${stats.notifyCount > 0} " +
                "has040c=${stats.splitFrameCount > 0} notifyCount=${stats.notifyCount} " +
                "frameCount=${stats.splitFrameCount} writeCount=${stats.writeRequestCount} " +
                "writeSuccessCount=${stats.writeRequestSuccessCount} " +
                "firstFrameAt=${firstFrameAtForSummary}ms " +
                "last=${formatLastFrame(stats.lastFrame)} requestId=$requestId refreshId=${currentRefreshId()}"
        )
        val shadowObserving = pipelineState as? RefreshPipelineState.SplitObserving
        val shadowTriggered = shadowObserving != null
        val shadowFirstFrame = shadowObserving?.firstFrameAtMs ?: -1L
        val oldFirstFrame = splitFirstFrameAtMs ?: -1L
        if (shadowTriggered != splitTriggerStarted || shadowFirstFrame != oldFirstFrame) {
            emitLog(
                "pipeline_state_shadow MISMATCH " +
                    "splitTriggeredShadow=$shadowTriggered splitTriggeredOld=$splitTriggerStarted " +
                    "firstFrameAtShadow=${shadowFirstFrame}ms firstFrameAtOld=${oldFirstFrame}ms " +
                    "requestId=$requestId refreshId=${currentRefreshId()}"
            )
        } else {
            emitLog(
                "pipeline_state_shadow MATCH " +
                    "splitTriggered=$shadowTriggered firstFrameAt=${shadowFirstFrame}ms " +
                    "requestId=$requestId refreshId=${currentRefreshId()}"
            )
        }
        synchronized(splitStatsLock) {
            splitRequestStats = null
        }
        completeUiRefreshIfNeeded("window_complete")
        setRefreshInFlightAndButtonBusy(false)
        splitTriggerStarted = false
        splitFirstFrameAtMs = null
        splitPendingBatteryGate = false
        transitionTo(RefreshPipelineState.Idle)
    }

    private fun scheduleNotifyReadyFallback(refreshToken: Long) {
        notifyFallbackJob?.cancel()
        notifyFallbackJob = sessionScope.launch {
            delay(1_200L)
            if (refreshToken != activeRefreshToken) return@launch
            val oldNotifyReady = splitNotifyReady
            val shadowState = pipelineState
            when (shadowState) {
                is RefreshPipelineState.Ready -> {
                    val shadowNotifyReady = shadowState.isNotifyReady
                    if (oldNotifyReady == shadowNotifyReady) {
                        emitLog(
                            "notify_ready_shadow MATCH old=$oldNotifyReady shadow=$shadowNotifyReady " +
                                "state=Ready inFlight=$refreshInFlight scheduled=$splitRequestScheduled " +
                                "token=$refreshToken refreshId=${currentRefreshId()}"
                        )
                    } else {
                        emitLog(
                            "notify_ready_shadow MISMATCH old=$oldNotifyReady shadow=$shadowNotifyReady " +
                                "state=Ready inFlight=$refreshInFlight scheduled=$splitRequestScheduled " +
                                "token=$refreshToken refreshId=${currentRefreshId()}"
                        )
                    }
                }

                else -> {
                    emitLog(
                        "notify_ready_shadow UNAVAILABLE old=$oldNotifyReady shadow=unavailable " +
                            "state=${shadowState.javaClass.simpleName} inFlight=$refreshInFlight scheduled=$splitRequestScheduled " +
                            "token=$refreshToken refreshId=${currentRefreshId()}"
                    )
                }
            }
            if (!refreshInFlight || !splitRequestScheduled || splitNotifyReady) return@launch
            emitLog("f2_notify_enable_result requested=timeout_fallback fallbackReady=true token=$refreshToken refreshId=${currentRefreshId()}")
            splitNotifyReady = true
            (pipelineState as? RefreshPipelineState.Ready)?.let { ready ->
                transitionTo(ready.copy(isNotifyReady = true))
            }
            requestSplitBatteryIfReady()
        }
    }

    private fun scheduleUiCompleteTimeout(refreshToken: Long) {
        uiTimeoutJob?.cancel()
        uiTimeoutJob = sessionScope.launch {
            delay(UI_SPLIT_WAIT_TIMEOUT_MS)
            if (refreshToken != activeRefreshToken) return@launch
            completeUiRefreshIfNeeded("timeout")
        }
    }

    private fun scheduleBatteryReadGateTimeout(refreshToken: Long) {
        batteryReadGateJob?.cancel()
        emitLog("battery_read_gate_wait timeoutMs=500 refreshId=${currentRefreshId()}")
        batteryReadGateJob = sessionScope.launch {
            delay(500L)
            if (refreshToken != activeRefreshToken) return@launch
            openBatteryReadGate("timeout")
        }
    }

    private fun openBatteryReadGate(reason: String) {
        if (batteryReadGateOpen) return
        batteryReadGateOpen = true
        batteryReadGateJob?.cancel()
        batteryReadGateJob = null
        emitLog("battery_read_gate_open reason=$reason refreshId=${currentRefreshId()}")
        if (splitPendingBatteryGate) {
            splitPendingBatteryGate = false
            requestSplitBatteryIfReady()
        }
    }

    @Synchronized
    private fun completeUiRefreshIfNeeded(@Suppress("UNUSED_PARAMETER") reason: String) {
        if (uiRefreshCompleted) return
        uiRefreshCompleted = true
        uiState = RefreshUiState(isRefreshing = false)
        val elapsedMs = (System.currentTimeMillis() - activeRefreshStartAt).coerceAtLeast(0L)
        emitLog("refresh_ui_complete reason=$reason elapsedMs=$elapsedMs refreshId=${currentRefreshId()}")
        updateState(currentState.copy(isRefreshing = false))
    }

    private fun reusableReason(targetMac: String): String? {
        val gatt = currentGatt ?: return "no_gatt"
        val addressMatch = gatt.device.address.equals(targetMac, ignoreCase = true)
        if (!addressMatch) return "mac_changed"
        if (!servicesReady) return "services_not_ready"

        val connectedByProfile = isGattConnected(gatt)
        if (!connectedByProfile) return "gatt_not_connected"

        if (!cachedCapabilities.canReadBattery) return "battery_read_unavailable"
        if (!cachedCapabilities.canWriteSplit) return "split_write_unavailable"
        if (!cachedCapabilities.canNotifySplit) return "split_notify_unavailable"
        if (batteryCharacteristic == null) return "battery_char_missing"
        if (splitWriteCharacteristic == null) return "split_write_char_missing"
        if (splitNotifyCharacteristic == null) return "split_notify_char_missing"
        return null
    }

    private fun resetSessionForReconnect() {
        runCatching {
            currentGatt?.let { disconnectGattSafely(it) }
            currentGatt?.let { closeGattSafely(it) }
        }
        currentGatt = null
        splitNotifyReady = false
        servicesReady = false
        splitPendingBatteryGate = false
        batteryReadGateOpen = false
        cachedCapabilities = RefreshCapabilities(
            canReadBattery = false,
            canWriteSplit = false,
            canNotifySplit = false
        )
        batteryCharacteristic = null
        splitWriteCharacteristic = null
        splitNotifyCharacteristic = null
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
        val guardedState = if (uiRefreshCompleted && newState.isRefreshing) {
            emitLog("refresh_ui_guard suppress_isRefreshing_true refreshId=${currentRefreshId()}")
            newState.copy(isRefreshing = false)
        } else {
            newState
        }
        currentState = guardedState
        stateSink?.invoke(guardedState)
    }

    private fun emitLog(message: String) {
        AppLogger.d("OpenFeelGattSession", message)
        logSink?.invoke(message)
    }

    private fun safeEmitDebugLog(message: String) {
        runCatching { emitLog(message) }
    }

    private fun formatLastFrame(frame: SplitBatteryFrame?): String {
        if (frame == null) return "none"
        return "L=${frame.leftBattery} R=${frame.rightBattery} C=${frame.caseBattery}"
    }

    private fun capabilityMissingSummary(capabilities: RefreshCapabilities): String {
        return "refresh_pipeline_summary result=completed splitTriggered=false reason=capability_missing " +
            "batteryRead=${capabilities.canReadBattery} " +
            "splitWrite=${capabilities.canWriteSplit} " +
            "splitNotify=${capabilities.canNotifySplit} refreshId=${currentRefreshId()}"
    }

    private fun toHexByte(value: Int): String {
        return value.toString(16).uppercase(Locale.US).padStart(2, '0')
    }

    private fun currentRefreshId(): Long = activeRefreshToken

    private fun transitionTo(newState: RefreshPipelineState) {
        val oldState = pipelineState
        pipelineState = newState
        emitLog(
            "pipeline_state from=${oldState.javaClass.simpleName} " +
                "to=${newState.javaClass.simpleName} refreshId=${currentRefreshId()}"
        )
    }

    private fun clearScheduledJobs() {
        uiTimeoutJob?.cancel()
        notifyFallbackJob?.cancel()
        splitRequestJob?.cancel()
        batteryReadGateJob?.cancel()
        uiTimeoutJob = null
        notifyFallbackJob = null
        splitRequestJob = null
        batteryReadGateJob = null
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGattSafely(device: BluetoothDevice): BluetoothGatt? {
        if (!hasConnectPermission()) return null
        return runCatching {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }.onFailure {
            safeEmitDebugLog(
                "op=connectGatt ex=${it.javaClass.simpleName} " +
                    "msg=${it.message ?: "null"} mac=${device.address}"
            )
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(device: BluetoothDevice): String {
        if (!hasConnectPermission()) return "null"
        return runCatching { device.name ?: "null" }.getOrDefault("null")
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGattSafely(gatt: BluetoothGatt) {
        if (!hasConnectPermission()) return
        runCatching { gatt.disconnect() }
    }

    @SuppressLint("MissingPermission")
    private fun closeGattSafely(gatt: BluetoothGatt) {
        if (!hasConnectPermission()) return
        runCatching { gatt.close() }
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesSafely(gatt: BluetoothGatt): Boolean {
        if (!hasConnectPermission()) return false
        return runCatching { gatt.discoverServices() }
            .onFailure {
                safeEmitDebugLog(
                    "op=discoverServices ex=${it.javaClass.simpleName} " +
                        "msg=${it.message ?: "null"} mac=${gatt.device.address}"
                )
            }
            .getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristicSafely(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!hasConnectPermission()) return false
        return runCatching { gatt.readCharacteristic(characteristic) }
            .onFailure {
                safeEmitDebugLog(
                    "op=readCharacteristic ex=${it.javaClass.simpleName} " +
                        "msg=${it.message ?: "null"} uuid=${characteristic.uuid} mac=${gatt.device.address}"
                )
            }
            .getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun setCharacteristicNotificationSafely(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean
    ): Boolean {
        if (!hasConnectPermission()) return false
        return runCatching { gatt.setCharacteristicNotification(characteristic, enabled) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun writeDescriptorSafely(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        if (!hasConnectPermission()) return false
        return runCatching {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorSafely(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor
    ): Boolean {
        if (!hasConnectPermission()) return false
        return runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun writeCharacteristicSafely(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        if (!hasConnectPermission()) return false
        return runCatching {
            gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
        }.onFailure {
            safeEmitDebugLog(
                "op=writeCharacteristic_api33 ex=${it.javaClass.simpleName} " +
                    "msg=${it.message ?: "null"} uuid=${characteristic.uuid} writeType=$writeType mac=${gatt.device.address}"
            )
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicSafely(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!hasConnectPermission()) return false
        return runCatching { gatt.writeCharacteristic(characteristic) }
            .onFailure {
                safeEmitDebugLog(
                    "op=writeCharacteristic_legacy ex=${it.javaClass.simpleName} " +
                        "msg=${it.message ?: "null"} uuid=${characteristic.uuid} mac=${gatt.device.address}"
                )
            }
            .getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun isGattConnected(gatt: BluetoothGatt): Boolean {
        if (!hasConnectPermission()) return false
        return runCatching {
            bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        }.getOrDefault(false)
    }
}
