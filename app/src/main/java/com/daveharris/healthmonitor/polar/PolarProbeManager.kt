package com.daveharris.healthmonitor.polar

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import com.daveharris.healthmonitor.data.AppDatabase
import com.daveharris.healthmonitor.data.DeviceProfileEntity
import com.daveharris.healthmonitor.util.GsonProvider
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.androidcommunications.api.ble.model.gatt.client.BatteryPresentState
import com.polar.androidcommunications.api.ble.model.gatt.client.BleDisClient
import com.polar.androidcommunications.api.ble.model.gatt.client.ChargeState
import com.polar.androidcommunications.api.ble.model.gatt.client.PowerSourceState
import com.polar.androidcommunications.api.ble.model.gatt.client.PowerSourcesState
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarPhysicalConfiguration
import com.polar.sdk.api.model.activity.Polar247HrSamplesData
import com.polar.sdk.api.model.activity.Polar247PPiSamplesData
import com.polar.sdk.api.model.activity.PolarActivitySamplesDayData
import com.polar.sdk.api.model.activity.PolarDailySummaryData
import com.polar.sdk.api.model.sleep.PolarNightlyRechargeData
import com.polar.sdk.api.model.sleep.PolarSleepData
import com.polar.sdk.api.model.PolarSkinTemperatureData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

data class DeviceRuntimeState(
    val scannedDevices: List<PolarDeviceInfo> = emptyList(),
    val connectedDevice: PolarDeviceInfo? = null,
    val connectionPhase: String = "idle",
    val batteryLevel: Int? = null,
    val chargingState: ChargeState = ChargeState.UNKNOWN,
    val powerSourcesState: PowerSourcesState = PowerSourcesState(
        BatteryPresentState.UNKNOWN,
        PowerSourceState.UNKNOWN,
        PowerSourceState.UNKNOWN
    ),
    val firmwareVersion: String? = null,
    val readyFeatures: List<PolarBleApi.PolarBleSdkFeature> = emptyList(),
    val unavailableFeatures: List<PolarBleApi.PolarBleSdkFeature> = emptyList(),
    val bluetoothPowered: Boolean = false,
    val lastError: String? = null
)

class PolarProbeManager(
    context: Context,
    private val database: AppDatabase
) : PolarBleApiCallback() {
    private val appContext = context.applicationContext
    private val features = setOf(
        PolarBleApi.PolarBleSdkFeature.FEATURE_HR,
        PolarBleApi.PolarBleSdkFeature.FEATURE_DEVICE_INFO,
        PolarBleApi.PolarBleSdkFeature.FEATURE_BATTERY_INFO,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_TIME_SETUP,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ACTIVITY_DATA,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_SLEEP_DATA,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_OFFLINE_RECORDING,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_TRAINING_DATA,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_TEMPERATURE_DATA,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_DEVICE_CONTROL,
        PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_FEATURES_CONFIGURATION_SERVICE
    )

    val api = PolarBleApiDefaultImpl.defaultImplementation(context, features)
    private val dao = database.probeDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _runtimeState = MutableStateFlow(DeviceRuntimeState())
    val runtimeState = _runtimeState.asStateFlow()

    init {
        api.setApiCallback(this)
        api.setAutomaticReconnection(false)
    }

    fun searchForDevices(namePrefix: String? = "Polar"): Flow<PolarDeviceInfo> {
        _runtimeState.value = _runtimeState.value.copy(scannedDevices = emptyList(), lastError = null)
        return api.searchForDevice(namePrefix).catch { error ->
            _runtimeState.value = _runtimeState.value.copy(lastError = error.message ?: "Search failed")
            throw error
        }
    }

    suspend fun connectToDevice(deviceId: String) = api.connectToDevice(deviceId)

    suspend fun disconnectDevice(deviceId: String) = api.disconnectFromDevice(deviceId)

    suspend fun isFtuDone(deviceId: String): Boolean = api.isFtuDone(deviceId)

    suspend fun getUserPhysicalConfiguration(deviceId: String): PolarPhysicalConfiguration? =
        api.getUserPhysicalConfiguration(deviceId)

    suspend fun startSyncNotifications(deviceId: String): Boolean =
        api.sendInitializationAndStartSyncNotifications(deviceId)

    suspend fun stopSyncNotifications(deviceId: String) {
        api.sendTerminateAndStopSyncNotifications(deviceId)
    }

    suspend fun fetchSleep(deviceId: String, from: LocalDate, to: LocalDate): List<PolarSleepData> =
        api.getSleep(deviceId, from, to)

    suspend fun fetchNightlyRecharge(deviceId: String, from: LocalDate, to: LocalDate): List<PolarNightlyRechargeData> =
        api.getNightlyRecharge(deviceId, from, to)

    suspend fun fetch247Hr(deviceId: String, from: LocalDate, to: LocalDate): List<Polar247HrSamplesData> =
        api.get247HrSamples(deviceId, from, to)

    suspend fun fetch247Ppi(deviceId: String, from: LocalDate, to: LocalDate): List<Polar247PPiSamplesData> =
        api.get247PPiSamples(deviceId, from, to)

    suspend fun fetchSkinTemperature(deviceId: String, from: LocalDate, to: LocalDate): List<PolarSkinTemperatureData> =
        api.getSkinTemperature(deviceId, from, to)

    suspend fun fetchDailySummary(deviceId: String, from: LocalDate, to: LocalDate): List<PolarDailySummaryData> =
        api.getDailySummaryData(deviceId, from, to)

    suspend fun fetchActivitySamples(deviceId: String, from: LocalDate, to: LocalDate): List<PolarActivitySamplesDayData> =
        api.getActivitySampleData(deviceId, from, to)

    suspend fun listDeviceFiles(deviceId: String, path: String, recursive: Boolean): List<String> =
        api.getFileList(deviceId, path, recursive)

    suspend fun getDiskSpace(deviceId: String) = api.getDiskSpace(deviceId)

    suspend fun deleteDeviceDateFolders(deviceId: String, from: LocalDate, to: LocalDate) =
        api.deleteDeviceDateFolders(deviceId, from, to)

    suspend fun getAvailableOfflineRecordingDataTypes(deviceId: String): Set<PolarBleApi.PolarDeviceDataType> =
        api.getAvailableOfflineRecordingDataTypes(deviceId)

    suspend fun requestOfflineRecordingSettings(
        deviceId: String,
        dataType: PolarBleApi.PolarDeviceDataType
    ) = api.requestOfflineRecordingSettings(deviceId, dataType)

    suspend fun getOfflineRecordingStatus(deviceId: String): List<PolarBleApi.PolarDeviceDataType> =
        api.getOfflineRecordingStatus(deviceId)

    suspend fun startOfflineRecording(
        deviceId: String,
        dataType: PolarBleApi.PolarDeviceDataType
    ) {
        val settings = runCatching { api.requestOfflineRecordingSettings(deviceId, dataType).maxSettings() }.getOrNull()
        api.startOfflineRecording(deviceId, dataType, settings)
    }

    suspend fun stopOfflineRecording(deviceId: String, dataType: PolarBleApi.PolarDeviceDataType) =
        api.stopOfflineRecording(deviceId, dataType)

    suspend fun listOfflineRecordings(deviceId: String): List<PolarOfflineRecordingEntry> =
        api.listOfflineRecordings(deviceId).toList()

    suspend fun listSplitOfflineRecordings(deviceId: String): List<PolarOfflineRecordingEntry> =
        api.listSplitOfflineRecordings(deviceId).toList()

    suspend fun fetchOfflineRecord(
        deviceId: String,
        entry: PolarOfflineRecordingEntry
    ): PolarOfflineRecordingData = api.getOfflineRecord(deviceId, entry)

    @Suppress("DEPRECATION")
    suspend fun fetchSplitOfflineRecord(
        deviceId: String,
        entry: PolarOfflineRecordingEntry
    ): PolarOfflineRecordingData = api.getSplitOfflineRecord(deviceId, entry)

    suspend fun removeOfflineRecord(deviceId: String, entry: PolarOfflineRecordingEntry) =
        api.removeOfflineRecord(deviceId, entry)

    override fun blePowerStateChanged(powered: Boolean) {
        _runtimeState.value = _runtimeState.value.copy(bluetoothPowered = powered)
    }

    override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) {
        _runtimeState.value = _runtimeState.value.copy(
            connectedDevice = polarDeviceInfo,
            connectionPhase = "connecting",
            lastError = null
        )
        persistDeviceProfile()
    }

    override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
        _runtimeState.value = _runtimeState.value.copy(
            connectedDevice = polarDeviceInfo,
            connectionPhase = "connected",
            lastError = null
        )
        persistDeviceProfile()
    }

    override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) {
        val bondState = describeBondStateForAddress(polarDeviceInfo.address)
        val pairingHint = if (bondState == "none") {
            "Disconnected while Android shows no BLE bond. Polar 360 may refuse re-pairing until factory reset if the original bond was lost."
        } else {
            "Disconnected"
        }
        _runtimeState.value = _runtimeState.value.copy(
            connectedDevice = null,
            connectionPhase = "disconnected",
            lastError = pairingHint
        )
        persistDeviceProfile(polarDeviceInfo.deviceId)
    }

    override fun bleSdkFeaturesReadiness(
        identifier: String,
        ready: List<PolarBleApi.PolarBleSdkFeature>,
        unavailable: List<PolarBleApi.PolarBleSdkFeature>
    ) {
        _runtimeState.value = _runtimeState.value.copy(
            readyFeatures = ready,
            unavailableFeatures = unavailable
        )
        persistDeviceProfile(identifier)
    }

    override fun disInformationReceived(identifier: String, uuid: UUID, value: String) {
        if (uuid == BleDisClient.SOFTWARE_REVISION_STRING) {
            _runtimeState.value = _runtimeState.value.copy(firmwareVersion = value)
            persistDeviceProfile(identifier)
        }
    }

    override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit

    override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit

    override fun batteryLevelReceived(identifier: String, level: Int) {
        _runtimeState.value = _runtimeState.value.copy(batteryLevel = level)
        persistDeviceProfile(identifier)
    }

    override fun batteryChargingStatusReceived(identifier: String, chargingStatus: ChargeState) {
        _runtimeState.value = _runtimeState.value.copy(chargingState = chargingStatus)
        persistDeviceProfile(identifier)
    }

    override fun powerSourcesStateReceived(identifier: String, powerSourcesState: PowerSourcesState) {
        _runtimeState.value = _runtimeState.value.copy(powerSourcesState = powerSourcesState)
        persistDeviceProfile(identifier)
    }

    suspend fun collectSearchResults(prefix: String? = "Polar"): List<PolarDeviceInfo> {
        val devices = searchForDevices(prefix).toList().distinctBy { it.deviceId }
        _runtimeState.value = _runtimeState.value.copy(scannedDevices = devices)
        return devices
    }

    fun recordSearchResult(device: PolarDeviceInfo) {
        _runtimeState.value = _runtimeState.value.copy(
            scannedDevices = (_runtimeState.value.scannedDevices + device).distinctBy { it.deviceId }
        )
    }

    private fun persistDeviceProfile(identifierOverride: String? = null) {
        val state = _runtimeState.value
        val device = state.connectedDevice
        val deviceId = identifierOverride ?: device?.deviceId ?: return
        scope.launch {
            val current = dao.getDeviceProfile(deviceId)
            val address = device?.address ?: current?.address ?: ""
            val bondState = if (address.isNotBlank()) describeBondStateForAddress(address) else "unknown"
            dao.upsertDeviceProfile(
                DeviceProfileEntity(
                    deviceId = deviceId,
                    name = device?.name ?: current?.name ?: "Unknown",
                    address = address,
                    firmwareVersion = state.firmwareVersion ?: current?.firmwareVersion,
                    batteryLevel = state.batteryLevel ?: current?.batteryLevel,
                    isConnected = state.connectionPhase == "connected",
                    lastSeenAtEpochMs = System.currentTimeMillis(),
                    readyFeaturesJson = GsonProvider.gson.toJson(state.readyFeatures.map { it.name }),
                    unavailableFeaturesJson = GsonProvider.gson.toJson(state.unavailableFeatures.map { it.name }),
                    featureSummary = "ready=${state.readyFeatures.size}, unavailable=${state.unavailableFeatures.size}",
                    notes = "bond=$bondState"
                )
            )
        }
    }

    private fun describeBondStateForAddress(address: String): String {
        return runCatching {
            val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
            when (adapter?.getRemoteDevice(address)?.bondState) {
                BluetoothDevice.BOND_BONDED -> "bonded"
                BluetoothDevice.BOND_BONDING -> "bonding"
                BluetoothDevice.BOND_NONE -> "none"
                else -> "unknown"
            }
        }.getOrDefault("unknown")
    }
}
