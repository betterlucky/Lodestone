package com.daveharris.healthmonitor.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import com.daveharris.healthmonitor.HealthMonitorApp
import com.daveharris.healthmonitor.MorningReadScheduler
import com.daveharris.healthmonitor.OvernightPpiScheduler
import com.daveharris.healthmonitor.data.DailyReviewRepository
import com.daveharris.healthmonitor.data.DeviceProfileEntity
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.ProbeRepository
import com.daveharris.healthmonitor.data.SyncWindowConfig
import com.daveharris.healthmonitor.data.TrafficLightStatus
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import com.polar.sdk.api.PolarBleApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

class ProbeViewModel(
    application: Application,
    private val repository: ProbeRepository,
    private val dailyReviewRepository: DailyReviewRepository
) : AndroidViewModel(application) {
    val runtimeState = repository.runtimeState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        repository.runtimeState.value
    )
    val deviceProfile = repository.deviceProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val ftuProfile = repository.ftuProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val observedCapabilities = repository.observedCapabilities.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncRuns = repository.syncRuns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val syncDomainResults = repository.syncDomainResults.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val appSettings = repository.appSettings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val dailyCheckIns = dailyReviewRepository.dailyCheckIns.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val foodDailySummaries = dailyReviewRepository.foodDailySummaries.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val dailyWeights = dailyReviewRepository.dailyWeights.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val morningRead = repository.morningRead.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val latestOfflinePpiNightSummary = repository.latestOfflinePpiNightSummary.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null
    )

    var isBusy by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var selectedDeviceId by mutableStateOf<String?>(null)
        private set
    var syncWindowConfig by mutableStateOf(SyncWindowConfig())
        private set
    var firmwareRediscoveryNeeded by mutableStateOf(false)
        private set
    var checkInDate by mutableStateOf(LocalDate.now().toString())
        private set
    var eveningOutcomeDraft by mutableStateOf<TrafficLightStatus?>(null)
        private set
    var approachToDayDraft by mutableStateOf<TrafficLightStatus?>(null)
        private set
    var muscleWeaknessTodayDraft by mutableStateOf(false)
        private set
    var notesDraft by mutableStateOf("")
        private set
    var currentFoodSummary by mutableStateOf<FoodDailySummaryEntity?>(null)
        private set
    var currentDailyWeight by mutableStateOf<DailyWeightEntity?>(null)
        private set
    var overnightStartTimeDraft by mutableStateOf("23:00")
        private set
    var overnightStopTimeDraft by mutableStateOf("10:30")
        private set
    var scheduledStartEnabled by mutableStateOf(false)
        private set
    var scheduledStopEnabled by mutableStateOf(false)
        private set
    var nextScheduledStartEpochMs by mutableStateOf<Long?>(null)
        private set
    var nextScheduledStopEpochMs by mutableStateOf<Long?>(null)
        private set
    private var foodSummaryJob: Job? = null
    private var reviewLoadJob: Job? = null
    private val overnightPrefs = application.getSharedPreferences("overnight_ppi", Context.MODE_PRIVATE)

    init {
        loadOvernightSettings()
        viewModelScope.launch {
            repository.appSettings.filterNotNull().collect { settings ->
                selectedDeviceId = selectedDeviceId ?: settings.selectedDeviceId
                syncWindowConfig = SyncWindowConfig(
                    sleepDays = settings.sleepDays,
                    nightlyRechargeDays = settings.nightlyRechargeDays,
                    hrDays = settings.hrDays,
                    ppiDays = settings.ppiDays
                )
            }
        }
        viewModelScope.launch {
            runtimeState.collect { runtime ->
                firmwareRediscoveryNeeded = repository.hasSelectedDeviceFirmwareChanged(
                    deviceId = selectedDeviceId,
                    runtimeFirmware = runtime.firmwareVersion
                )
            }
        }
        refreshFoodImportForDate(checkInDate)
    }

    fun scanDevices() {
        viewModelScope.launch {
            runBusyAction("Searching for Polar devices…") {
                repository.search()
                statusMessage = "Scan completed. Review the discovered devices below."
            }
        }
    }

    fun selectDevice(deviceId: String) {
        selectedDeviceId = deviceId
        persistAppSettings()
        viewModelScope.launch {
            firmwareRediscoveryNeeded = repository.hasSelectedDeviceFirmwareChanged(
                deviceId = deviceId,
                runtimeFirmware = runtimeState.value.firmwareVersion
            )
        }
        val profile = deviceProfile.value
        if (profile?.deviceId == deviceId) {
            statusMessage = "Selected ${profile.name}."
        }
    }

    fun connectSelectedDevice() {
        val deviceId = selectedDeviceId ?: return
        viewModelScope.launch {
            runBusyAction("Connecting…") {
                repository.connect(deviceId)
                statusMessage = "Connect requested for $deviceId. If this stalls, close Polar Flow or disable its Bluetooth access first."
            }
        }
    }

    fun disconnectSelectedDevice() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Disconnecting…") {
                repository.disconnect(deviceId)
                statusMessage = "Disconnect requested for $deviceId."
            }
        }
    }

    private suspend fun connectAndAwaitSelectedDevice(deviceId: String): String {
        fun DeviceRuntimeState.matchesSelectedDevice(): Boolean {
            val device = connectedDevice
            return connectionPhase == "connected" &&
                (
                    device?.deviceId.equals(deviceId, ignoreCase = true) ||
                        device?.address.equals(deviceId, ignoreCase = true)
                    )
        }

        repository.connect(deviceId)
        withTimeout(45_000) {
            repository.runtimeState.first { runtime ->
                runtime.matchesSelectedDevice()
            }
        }
        withTimeout(20_000) {
            repository.runtimeState.first { runtime ->
                runtime.matchesSelectedDevice() &&
                    (
                        runtime.firmwareVersion != null ||
                            runtime.readyFeatures.isNotEmpty() ||
                            runtime.unavailableFeatures.isNotEmpty()
                        )
            }
        }
        return repository.runtimeState.value.connectedDevice?.deviceId ?: deviceId
    }

    fun updateSyncDays(
        sleepDays: Int = syncWindowConfig.sleepDays,
        nightlyRechargeDays: Int = syncWindowConfig.nightlyRechargeDays,
        hrDays: Int = syncWindowConfig.hrDays,
        ppiDays: Int = syncWindowConfig.ppiDays
    ) {
        syncWindowConfig = SyncWindowConfig(sleepDays, nightlyRechargeDays, hrDays, ppiDays).normalized()
        persistAppSettings()
    }

    fun refreshFtuStatus() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Checking FTU status…") {
                val isDone = repository.refreshFtuStatus(deviceId).getOrThrow()
                statusMessage = if (isDone) "Device reports FTU complete." else "Device reports FTU incomplete."
            }
        }
    }

    fun discoverCapabilities() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Running capability discovery…") {
                repository.runCapabilityDiscovery(deviceId).getOrThrow()
                persistAppSettings()
                firmwareRediscoveryNeeded = false
                statusMessage = "Capability discovery completed."
            }
        }
    }

    fun runManualSync() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Running sync…") {
                repository.runManualSync(deviceId, syncWindowConfig).getOrThrow()
                persistAppSettings()
                firmwareRediscoveryNeeded = false
                statusMessage = "Manual sync completed."
            }
        }
    }

    fun startOvernightPpiNow() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Starting overnight PPI recording…") {
                val connectedId = connectAndAwaitSelectedDevice(deviceId)
                repository.startNormalOfflineRecordingSmoke(connectedId, PolarBleApi.PolarDeviceDataType.PPI).getOrThrow()
                rollScheduledStartForward(connectedId)
                statusMessage = "Overnight PPI recording started."
            }
        }
    }

    fun markAwakeAndFetchOvernightPpi() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Stopping overnight PPI and syncing…") {
                val connectedId = connectAndAwaitSelectedDevice(deviceId)
                repository.recordWakeMarker(
                    sourceDate = LocalDate.now().toString(),
                    deviceId = connectedId,
                    notes = "I’m awake button"
                )
                repository.stopAndFetchNormalOfflineRecordingSmoke(connectedId, PolarBleApi.PolarDeviceDataType.PPI).getOrThrow()
                repository.runManualSync(connectedId, syncWindowConfig).getOrThrow()
                scheduleMorningReadCheckIfNeeded(connectedId)
                persistAppSettings()
                rollScheduledStopForward(connectedId)
                statusMessage = "Awake recorded. PPI fetched and normal sync completed."
            }
        }
    }

    fun updateOvernightStartTime(value: String) {
        overnightStartTimeDraft = value
        saveOvernightPrefs()
    }

    fun updateOvernightStopTime(value: String) {
        overnightStopTimeDraft = value
        saveOvernightPrefs()
    }

    fun updateScheduledStartEnabled(enabled: Boolean) {
        scheduledStartEnabled = enabled
        if (enabled) {
            scheduleStart()
        } else {
            OvernightPpiScheduler.cancelStart(getApplication())
            nextScheduledStartEpochMs = null
            saveOvernightPrefs()
            statusMessage = "Scheduled PPI start disabled."
        }
    }

    fun updateScheduledStopEnabled(enabled: Boolean) {
        scheduledStopEnabled = enabled
        if (enabled) {
            scheduleStop()
        } else {
            OvernightPpiScheduler.cancelStop(getApplication())
            nextScheduledStopEpochMs = null
            saveOvernightPrefs()
            statusMessage = "Scheduled PPI stop disabled."
        }
    }

    fun scheduleStart() {
        try {
            val next = OvernightPpiScheduler.scheduleNextStart(getApplication(), overnightStartTimeDraft, selectedDeviceId)
            scheduledStartEnabled = true
            nextScheduledStartEpochMs = next
            saveOvernightPrefs()
            statusMessage = "Scheduled overnight PPI start."
        } catch (error: DateTimeParseException) {
            scheduledStartEnabled = false
            nextScheduledStartEpochMs = null
            saveOvernightPrefs()
            statusMessage = "Use 24-hour time, for example 23:00."
        }
    }

    fun scheduleStop() {
        try {
            val next = OvernightPpiScheduler.scheduleNextStop(getApplication(), overnightStopTimeDraft, selectedDeviceId)
            scheduledStopEnabled = true
            nextScheduledStopEpochMs = next
            saveOvernightPrefs()
            statusMessage = "Scheduled overnight PPI stop/fetch."
        } catch (error: DateTimeParseException) {
            scheduledStopEnabled = false
            nextScheduledStopEpochMs = null
            saveOvernightPrefs()
            statusMessage = "Use 24-hour time, for example 10:30."
        }
    }

    fun exportInspectorData() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            runBusyAction("Exporting JSON…") {
                val file = repository.exportInspectorData(context).getOrThrow()
                statusMessage = "Exported JSON to ${file.absolutePath}"
            }
        }
    }

    fun consumeMessage() {
        statusMessage = null
    }

    fun updateCheckInDate(value: String) {
        selectCheckInDate(value)
    }

    fun setCheckInDateToToday() {
        selectCheckInDate(LocalDate.now().toString())
    }

    fun setCheckInDateToYesterday() {
        selectCheckInDate(LocalDate.now().minusDays(1).toString())
    }

    fun resetSelectedReviewDate() {
        val date = checkInDate
        if (runCatching { LocalDate.parse(date) }.isFailure) {
            statusMessage = "Choose a valid date first."
            return
        }
        viewModelScope.launch {
            if (date == LocalDate.now().toString()) {
                runBusyAction("Resetting today…") {
                    eveningOutcomeDraft = null
                    approachToDayDraft = null
                    muscleWeaknessTodayDraft = false
                    notesDraft = ""
                    dailyReviewRepository.clearFoodImportForDate(date)
                    currentFoodSummary = null
                    currentDailyWeight = null
                    statusMessage = "Reset today's review and food import."
                }
            } else {
                val existing = dailyReviewRepository.getDailyCheckIn(date)
                if (existing != null) {
                    hydrateDailyCheckIn(existing)
                    statusMessage = "Reloaded saved review for $date."
                } else {
                    eveningOutcomeDraft = null
                    approachToDayDraft = null
                    muscleWeaknessTodayDraft = false
                    notesDraft = ""
                    statusMessage = "No saved review for $date."
                }
                refreshFoodImportForDate(date)
            }
        }
    }

    fun setEveningOutcome(status: TrafficLightStatus?) {
        eveningOutcomeDraft = status
    }

    fun setApproachToDay(status: TrafficLightStatus?) {
        approachToDayDraft = status
    }

    fun updateNotesDraft(value: String) {
        notesDraft = value
    }

    fun updateMuscleWeaknessToday(value: Boolean) {
        muscleWeaknessTodayDraft = value
    }

    fun loadDailyCheckIn(date: String = checkInDate) {
        checkInDate = date
        reviewLoadJob?.cancel()
        reviewLoadJob = viewModelScope.launch {
            val existing = dailyReviewRepository.getDailyCheckIn(date)
            if (date != checkInDate) return@launch
            if (existing != null) {
                hydrateDailyCheckIn(existing)
                statusMessage = "Loaded saved check-in for $date."
            } else {
                checkInDate = date
                statusMessage = "No saved check-in for $date. Current draft left unchanged."
            }
            refreshFoodImportForDate(date)
        }
    }

    fun saveDailyCheckIn() {
        viewModelScope.launch {
            runBusyAction("Saving evening check-in…") {
                val outcome = eveningOutcomeDraft ?: error("Select how the day ended before saving.")
                dailyReviewRepository.saveDailyCheckIn(
                    sourceDate = checkInDate,
                    eveningOutcome = outcome.name,
                    approachToDay = approachToDayDraft?.name,
                    muscleWeaknessToday = muscleWeaknessTodayDraft,
                    notes = notesDraft
                )
                val savedDate = checkInDate
                eveningOutcomeDraft = null
                approachToDayDraft = null
                muscleWeaknessTodayDraft = false
                notesDraft = ""
                statusMessage = "Saved review for $savedDate. Entry fields cleared."
            }
        }
    }

    fun importLatestFoodCsvFromDownloads() {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val date = checkInDate
            runBusyAction("Looking for food CSV for $date…") {
                val importedCount = dailyReviewRepository.importLatestFoodCsvFromDownloads(context, date).getOrThrow()
                refreshFoodImportForDate(checkInDate)
                statusMessage = if (importedCount > 0) {
                    "Food log synced for $date."
                } else {
                    "Food CSV found, but no dated entries were imported."
                }
            }
        }
    }

    fun saveFoodFolder(uri: Uri) {
        val context = getApplication<Application>()
        dailyReviewRepository.saveFoodFolder(context, uri)
        statusMessage = "FoodLogData folder authorised. Sync food log will use it next time."
    }

    fun importFoodCsv(uri: Uri) {
        val context = getApplication<Application>()
        viewModelScope.launch {
            val date = checkInDate
            runBusyAction("Importing food CSV for $date…") {
                val importedCount = dailyReviewRepository.importFoodCsv(context, uri, date).getOrThrow()
                refreshFoodImportForDate(checkInDate)
                statusMessage = if (importedCount > 0) {
                    "Food CSV import successful for $date."
                } else {
                    "Food CSV imported, but no dated entries were found."
                }
            }
        }
    }

    fun runAutomationCommand(action: String, deviceId: String?) {
        deviceId?.let { selectDevice(it) }
        when (action.lowercase()) {
            "scan" -> scanDevices()
            "connect" -> connectSelectedDevice()
            "disconnect" -> disconnectSelectedDevice()
            "discover" -> discoverCapabilities()
            "sync" -> runManualSync()
            "overnight_start" -> startOvernightPpiNow()
            "overnight_awake" -> markAwakeAndFetchOvernightPpi()
        }
    }

    private fun loadOvernightSettings() {
        overnightStartTimeDraft = overnightPrefs.getString("start_time", "23:00") ?: "23:00"
        overnightStopTimeDraft = overnightPrefs.getString("stop_time", "10:30") ?: "10:30"
        scheduledStartEnabled = overnightPrefs.getBoolean("start_enabled", false)
        scheduledStopEnabled = overnightPrefs.getBoolean("stop_enabled", false)
        nextScheduledStartEpochMs = overnightPrefs.getLong("next_start_epoch_ms", 0L).takeIf { it > 0L }
        nextScheduledStopEpochMs = overnightPrefs.getLong("next_stop_epoch_ms", 0L).takeIf { it > 0L }
    }

    private fun saveOvernightPrefs() {
        overnightPrefs.edit()
            .putString("start_time", overnightStartTimeDraft)
            .putString("stop_time", overnightStopTimeDraft)
            .putString("device_id", selectedDeviceId)
            .putBoolean("start_enabled", scheduledStartEnabled)
            .putBoolean("stop_enabled", scheduledStopEnabled)
            .putLong("next_start_epoch_ms", nextScheduledStartEpochMs ?: 0L)
            .putLong("next_stop_epoch_ms", nextScheduledStopEpochMs ?: 0L)
            .apply()
    }

    private fun rollScheduledStartForward(deviceId: String) {
        if (!scheduledStartEnabled) return
        nextScheduledStartEpochMs = OvernightPpiScheduler.scheduleNextStartAfter(
            context = getApplication(),
            timeText = overnightStartTimeDraft,
            deviceId = deviceId,
            earliest = LocalDateTime.now().plusHours(12)
        )
        saveOvernightPrefs()
    }

    private fun rollScheduledStopForward(deviceId: String) {
        if (!scheduledStopEnabled) return
        nextScheduledStopEpochMs = OvernightPpiScheduler.scheduleNextStopAfter(
            context = getApplication(),
            timeText = overnightStopTimeDraft,
            deviceId = deviceId,
            earliest = LocalDateTime.now().plusHours(12)
        )
        saveOvernightPrefs()
    }

    private suspend fun scheduleMorningReadCheckIfNeeded(deviceId: String) {
        val targetDate = LocalDate.now().toString()
        if (repository.hasSleepRecordForDate(targetDate)) {
            MorningReadScheduler.cancel(getApplication())
        } else {
            MorningReadScheduler.scheduleNextCheck(getApplication(), targetDate, deviceId)
        }
    }

    private suspend fun runBusyAction(workingMessage: String, block: suspend () -> Unit) {
        isBusy = true
        statusMessage = workingMessage
        try {
            block()
        } catch (error: Throwable) {
            statusMessage = error.message ?: error.javaClass.simpleName
        } finally {
            isBusy = false
        }
    }

    private fun persistAppSettings() {
        viewModelScope.launch {
            repository.saveAppSettings(
                selectedDeviceId = selectedDeviceId,
                syncWindowConfig = syncWindowConfig,
                lastKnownFirmwareBySelectedDevice = runtimeState.value.firmwareVersion
            )
        }
    }

    private fun hydrateDailyCheckIn(entity: DailyCheckInEntity) {
        checkInDate = entity.sourceDate
        eveningOutcomeDraft = runCatching { TrafficLightStatus.valueOf(entity.eveningOutcome) }.getOrNull()
        approachToDayDraft = entity.approachToDay?.let { value ->
            runCatching { TrafficLightStatus.valueOf(value) }.getOrNull()
        }
        muscleWeaknessTodayDraft = entity.muscleWeaknessToday
        notesDraft = entity.notes.orEmpty()
    }

    private fun selectCheckInDate(date: String) {
        checkInDate = date
        if (runCatching { LocalDate.parse(date) }.isFailure) {
            return
        }
        reviewLoadJob?.cancel()
        reviewLoadJob = viewModelScope.launch {
            val existing = dailyReviewRepository.getDailyCheckIn(date)
            if (date != checkInDate) return@launch
            if (existing != null) {
                hydrateDailyCheckIn(existing)
            } else {
                eveningOutcomeDraft = null
                approachToDayDraft = null
                muscleWeaknessTodayDraft = false
                notesDraft = ""
            }
            refreshFoodImportForDate(date)
        }
    }

    private fun refreshFoodImportForDate(date: String) {
        foodSummaryJob?.cancel()
        foodSummaryJob = viewModelScope.launch {
            currentFoodSummary = dailyReviewRepository.getFoodDailySummary(date)
            currentDailyWeight = dailyReviewRepository.getDailyWeight(date)
        }
    }

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as HealthMonitorApp
                if (modelClass.isAssignableFrom(ProbeViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return ProbeViewModel(app, app.container.repository, app.container.dailyReviewRepository) as T
                }
                error("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
