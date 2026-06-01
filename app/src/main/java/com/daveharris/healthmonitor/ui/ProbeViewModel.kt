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
import com.daveharris.healthmonitor.SyncCommandWorker
import com.daveharris.healthmonitor.SyncCoordinator
import com.daveharris.healthmonitor.resolveLodestoneDisplayDate
import com.daveharris.healthmonitor.sleepTargetDateForBedtime
import com.daveharris.healthmonitor.data.DailyReviewRepository
import com.daveharris.healthmonitor.data.DeviceProfileEntity
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.JournalMajorTaskTypes
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.PaybackPeakConfidence
import com.daveharris.healthmonitor.data.ProbeRepository
import com.daveharris.healthmonitor.data.SleepEpisodeKinds
import com.daveharris.healthmonitor.data.SleepEpisodeSources
import com.daveharris.healthmonitor.data.SyncRunProfile
import com.daveharris.healthmonitor.data.SyncWindowConfig
import com.daveharris.healthmonitor.data.TrafficLightStatus
import com.daveharris.healthmonitor.data.WakeMarkerSources
import com.daveharris.healthmonitor.health.HealthConnectAnalysisExporter
import com.daveharris.healthmonitor.health.Sleep2ScreenshotImporter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.ceil

class ProbeViewModel(
    application: Application,
    private val repository: ProbeRepository,
    private val syncCoordinator: SyncCoordinator,
    private val dailyReviewRepository: DailyReviewRepository,
    private val healthConnectAnalysisExporter: HealthConnectAnalysisExporter
) : AndroidViewModel(application) {
    private val initialCheckInDate = LocalDate.now().toString()
    private val sleepEpisodeReviewDates = MutableStateFlow(listOf(initialCheckInDate))
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
    val morningPredictionSnapshots = repository.morningPredictionSnapshots.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentWakeMarkers = repository.recentWakeMarkers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val sleepEpisodeReviewState = combine(
        repository.recentSleepEpisodes,
        sleepEpisodeReviewDates,
        dailyReviewRepository.dailyCheckIns
    ) { episodes, dates, checkIns ->
        buildSleepEpisodeReviewState(
            activeDate = checkInDate,
            reviewDates = dates,
            episodes = episodes,
            reviewedDates = checkIns.map { it.sourceDate }.toSet()
        )
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SleepEpisodeReviewState.empty(initialCheckInDate)
        )
    var isBusy by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var selectedDeviceId by mutableStateOf<String?>(null)
        private set
    var syncWindowConfig by mutableStateOf(SyncWindowConfig())
        private set
    var markerMode by mutableStateOf(NowMarkerMode.BEDTIME_AND_WAKING)
        private set
    var checkInIntent by mutableStateOf(NowCheckInIntent.INFO)
        private set
    var firmwareRediscoveryNeeded by mutableStateOf(false)
        private set
    var checkInDate by mutableStateOf(initialCheckInDate)
        private set
    var eveningOutcomeDraft by mutableStateOf<TrafficLightStatus?>(null)
        private set
    var approachToDayDraft by mutableStateOf<TrafficLightStatus?>(null)
        private set
    var muscleWeaknessTodayDraft by mutableStateOf(false)
        private set
    var manualGripStrengthKgDraft by mutableStateOf("")
        private set
    var notesDraft by mutableStateOf("")
        private set
    var dayShapeCapturedDraft by mutableStateOf(false)
        private set
    var mostlyHorizontalDraft by mutableStateOf(false)
        private set
    var leftHouseDraft by mutableStateOf(false)
        private set
    var majorTaskDraft by mutableStateOf(false)
        private set
    var majorTaskTypeDraft by mutableStateOf<String?>(null)
        private set
    var pemPaybackTodayDraft by mutableStateOf(false)
        private set
    var paybackPeakTodayDraft by mutableStateOf(false)
        private set
    private var paybackPeakConfidenceDraft by mutableStateOf<String?>(null)
    var currentFoodSummary by mutableStateOf<FoodDailySummaryEntity?>(null)
        private set
    var currentDailyWeight by mutableStateOf<DailyWeightEntity?>(null)
        private set
    var showOutcomeValidation by mutableStateOf(false)
        private set
    var saveSuccessFlash by mutableStateOf(false)
        private set
    var healthConnectPermissionsGranted by mutableStateOf(false)
        private set
    var lastSleep2ScreenshotPath by mutableStateOf<String?>(null)
        private set
    var sleepReportRetryCooldownUntilEpochMs by mutableStateOf(loadSleepReportRetryCooldown(application))
        private set
    private var foodSummaryJob: Job? = null
    private var reviewLoadJob: Job? = null
    private var checkInIntentResetJob: Job? = null

    init {
        viewModelScope.launch {
            repository.appSettings.filterNotNull().collect { settings ->
                selectedDeviceId = selectedDeviceId ?: settings.selectedDeviceId
                syncWindowConfig = SyncWindowConfig(
                    sleepDays = settings.sleepDays,
                    nightlyRechargeDays = settings.nightlyRechargeDays,
                    hrDays = settings.hrDays,
                    ppiDays = settings.ppiDays
                )
                markerMode = settings.markerMode.toNowMarkerMode()
                checkInIntent = normalizeCheckInIntent(checkInIntent, markerMode)
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
        viewModelScope.launch {
            morningRead.filterNotNull().collect { snapshot ->
                repository.recordMorningPredictionSnapshot(snapshot)
            }
        }
        refreshFoodImportForDate(checkInDate)
        refreshHealthConnectPermissions()
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
                val result = syncCoordinator.runExclusiveDeviceOperation(deviceId) { it }
                selectedDeviceId = result
                persistAppSettings()
                statusMessage = "Connected to $result."
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

    fun updateSyncDays(
        sleepDays: Int = syncWindowConfig.sleepDays,
        nightlyRechargeDays: Int = syncWindowConfig.nightlyRechargeDays,
        hrDays: Int = syncWindowConfig.hrDays,
        ppiDays: Int = syncWindowConfig.ppiDays
    ) {
        syncWindowConfig = SyncWindowConfig(sleepDays, nightlyRechargeDays, hrDays, ppiDays).normalized()
        persistAppSettings()
    }

    fun updateMarkerMode(mode: NowMarkerMode) {
        markerMode = mode
        resetCheckInIntent()
        persistAppSettings()
        statusMessage = "Marker mode set to ${mode.settingsLabel()}."
    }

    fun resetCheckInIntent() {
        checkInIntentResetJob?.cancel()
        checkInIntentResetJob = null
        checkInIntent = NowCheckInIntent.INFO
    }

    private fun selectCheckInIntent(intent: NowCheckInIntent) {
        checkInIntent = normalizeCheckInIntent(intent, markerMode)
        checkInIntentResetJob?.cancel()
        checkInIntentResetJob = null
        if (checkInIntent != NowCheckInIntent.INFO) {
            checkInIntentResetJob = viewModelScope.launch {
                delay(CHECK_IN_INTENT_RESET_MS)
                resetCheckInIntent()
            }
        }
    }

    fun refreshFtuStatus() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Checking FTU status…") {
                val isDone = syncCoordinator.runExclusiveDeviceOperation(deviceId) { connectedId ->
                    selectedDeviceId = connectedId
                    repository.refreshFtuStatus(connectedId).getOrThrow()
                }
                persistAppSettings()
                statusMessage = if (isDone) "Device reports FTU complete." else "Device reports FTU incomplete."
            }
        }
    }

    fun discoverCapabilities() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Running capability discovery…") {
                val result = syncCoordinator.runExclusiveDeviceOperation(deviceId) { connectedId ->
                    repository.runCapabilityDiscovery(connectedId).getOrThrow()
                    connectedId
                }
                selectedDeviceId = result
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
                SyncCommandWorker.cancelBulkWork(getApplication())
                val result = syncCoordinator.runSync(
                    deviceId = deviceId,
                    config = syncWindowConfig,
                    profile = SyncRunProfile.MORNING_CORE
                )
                selectedDeviceId = result.connectedDeviceId
                persistAppSettings()
                firmwareRediscoveryNeeded = false
                statusMessage = if (result.recoveredMorningPpi) {
                    "Manual sync recovered PPI after reconnecting to the Loop."
                } else {
                    "Manual sync completed."
                }
            }
        }
    }

    fun runCheckInSync() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        selectCheckInIntent(NowCheckInIntent.INFO)
        val today = currentLodestoneDate()
        selectCheckInDate(today)
        viewModelScope.launch {
            runBusyAction("Checking in…") {
                SyncCommandWorker.cancelBulkWork(getApplication())
                val result = syncCoordinator.runSync(
                    deviceId = deviceId,
                    config = syncWindowConfig,
                    profile = SyncRunProfile.CHECK_IN,
                    scheduleMorningRetryIfNeeded = true,
                    lodestoneTargetDate = today
                )
                selectedDeviceId = result.connectedDeviceId
                persistAppSettings()
                firmwareRediscoveryNeeded = false
                val candidateCount = refreshInferredSleepEpisodeCandidates(listOf(today))
                statusMessage = if (result.recoveredMorningPpi) {
                    "Check-in sync recovered PPI after reconnecting to the Loop.${candidateCount.sleepCandidateSuffix()}"
                } else {
                    "Check-in sync completed.${candidateCount.sleepCandidateSuffix()}"
                }
            }
        }
    }

    fun runCatchUpSync() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        val today = currentLodestoneDate()
        val sourceDates = catchUpSourceDates(today)
        selectCheckInDate(today)
        viewModelScope.launch {
            runBusyAction("Catching up…") {
                SyncCommandWorker.cancelBulkWork(getApplication())
                val catchUpConfig = syncWindowConfig.copy(
                    sleepDays = syncWindowConfig.sleepDays.coerceAtLeast(14),
                    nightlyRechargeDays = syncWindowConfig.nightlyRechargeDays.coerceAtLeast(14),
                    hrDays = syncWindowConfig.hrDays.coerceAtLeast(7),
                    ppiDays = syncWindowConfig.ppiDays.coerceAtLeast(7)
                )
                val result = syncCoordinator.runSync(
                    deviceId = deviceId,
                    config = catchUpConfig,
                    profile = SyncRunProfile.CHECK_IN,
                    scheduleMorningRetryIfNeeded = true,
                    lodestoneTargetDate = today
                )
                selectedDeviceId = result.connectedDeviceId
                persistAppSettings()
                firmwareRediscoveryNeeded = false
                val candidateCount = refreshInferredSleepEpisodeCandidates(sourceDates)
                setSleepEpisodeReviewDates(today, sourceDates)
                statusMessage = if (result.recoveredMorningPpi) {
                    "Catch-up sync recovered PPI after reconnecting to the Loop.${candidateCount.sleepCandidateSuffix()}"
                } else {
                    "Catch-up sync completed.${candidateCount.sleepCandidateSuffix()}"
                }
            }
        }
    }

    fun markAwakeAndSync(markerEpochMs: Long = System.currentTimeMillis()) {
        runMarkerCheckIn(
            intent = NowCheckInIntent.WAKING,
            markerSource = WakeMarkerSources.IM_AWAKE,
            markerNotes = "Waking & sync",
            workingMessage = "Recording wake time and checking in…",
            successMessage = "Wake marker saved. Check-in sync completed.",
            recoveredMessage = "Wake marker saved. PPI recovered after reconnecting to the Loop.",
            markerEpochMs = markerEpochMs
        )
    }

    fun markGoingToBed(markerEpochMs: Long = System.currentTimeMillis()) {
        runMarkerCheckIn(
            intent = NowCheckInIntent.BEDTIME,
            markerSource = WakeMarkerSources.GOING_TO_BED,
            markerNotes = "Bedtime & sync",
            workingMessage = "Recording bedtime marker and checking in…",
            successMessage = "Bedtime marker saved. Check-in sync completed.",
            recoveredMessage = "Bedtime marker saved. PPI recovered after reconnecting to the Loop.",
            markerEpochMs = markerEpochMs
        )
    }

    private fun runMarkerCheckIn(
        intent: NowCheckInIntent,
        markerSource: String,
        markerNotes: String,
        workingMessage: String,
        successMessage: String,
        recoveredMessage: String,
        markerEpochMs: Long = System.currentTimeMillis()
    ) {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        selectCheckInIntent(intent)
        val targetDate = if (intent == NowCheckInIntent.BEDTIME) {
            sleepTargetDateForBedtime(markerEpochMs).toString()
        } else {
            resolveLodestoneDisplayDate(
                nowEpochMs = markerEpochMs,
                latestMorningReadSourceDate = morningRead.value?.sourceDate,
                wakeMarkers = recentWakeMarkers.value
            ).sourceDate
        }
        selectCheckInDate(targetDate)
        viewModelScope.launch {
            isBusy = true
            statusMessage = workingMessage
            var syncCompleted = false
            try {
                SyncCommandWorker.cancelBulkWork(getApplication())
                val result = syncCoordinator.runSync(
                    deviceId = deviceId,
                    config = syncWindowConfig,
                    profile = SyncRunProfile.CHECK_IN,
                    scheduleMorningRetryIfNeeded = true,
                    cancelMorningRetryFirst = intent == NowCheckInIntent.WAKING,
                    lodestoneTargetDate = targetDate
                )
                selectedDeviceId = result.connectedDeviceId
                persistAppSettings()
                firmwareRediscoveryNeeded = false
                syncCompleted = true
                repository.recordWakeMarker(
                    sourceDate = targetDate,
                    markerEpochMs = markerEpochMs,
                    markerSource = markerSource,
                    deviceId = result.connectedDeviceId,
                    notes = markerNotes
                )
                val candidateCount = refreshInferredSleepEpisodeCandidates(listOf(targetDate))
                statusMessage = if (result.recoveredMorningPpi) {
                    "$recoveredMessage${candidateCount.sleepCandidateSuffix()}"
                } else {
                    "$successMessage${candidateCount.sleepCandidateSuffix()}"
                }
            } catch (error: Throwable) {
                val markerSaved = if (syncCompleted) {
                    false
                } else {
                    runCatching {
                        repository.recordWakeMarker(
                            sourceDate = targetDate,
                            markerEpochMs = markerEpochMs,
                            markerSource = markerSource,
                            deviceId = selectedDeviceId,
                            notes = markerNotes
                        )
                    }.isSuccess
                }
                val message = error.message ?: error.javaClass.simpleName
                statusMessage = when {
                    syncCompleted -> "Check-in sync completed, but the marker was not saved: $message"
                    markerSaved -> "${intent.markerSavedLabel()} saved, but check-in sync failed: $message"
                    else -> "${intent.markerSavedLabel()} was not saved; check-in sync failed: $message"
                }
            } finally {
                isBusy = false
                resetCheckInIntent()
            }
        }
    }

    fun retryFinalSleepReport() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        val today = currentLodestoneDate()
        val remainingMs = sleepReportRetryCooldownUntilEpochMs - System.currentTimeMillis()
        if (remainingMs > 0L) {
            statusMessage = "Sleep report retry is cooling down. Try again in about ${remainingMinutes(remainingMs)}m."
            return
        }
        viewModelScope.launch {
            runBusyAction("Retrying final sleep report…") {
                saveSleepReportRetryCooldown(System.currentTimeMillis() + SLEEP_REPORT_RETRY_COOLDOWN_MS)
                if (repository.hasSleepRecordForDate(today)) {
                    statusMessage = "Final sleep report is already present."
                    return@runBusyAction
                }
                SyncCommandWorker.cancelBulkWork(getApplication())
                val result = syncCoordinator.runSync(
                    deviceId = deviceId,
                    config = syncWindowConfig,
                    profile = SyncRunProfile.MORNING_SLEEP_RETRY,
                    lodestoneTargetDate = today
                )
                selectedDeviceId = result.connectedDeviceId
                persistAppSettings()
                val finalReportPresent = repository.hasSleepRecordForDate(today)
                if (finalReportPresent) {
                    MorningReadScheduler.cancel(getApplication())
                }
                statusMessage = if (finalReportPresent) {
                    "Final sleep report retry completed. Sleep report is present."
                } else {
                    "Sleep report retry completed. Final report is still pending."
                }
            }
        }
    }

    fun sleepReportRetryCooldownLabel(): String? {
        val remainingMs = sleepReportRetryCooldownUntilEpochMs - System.currentTimeMillis()
        return if (remainingMs > 0L) {
            "Available in about ${remainingMinutes(remainingMs)}m"
        } else {
            null
        }
    }

    fun prepareForPolarFlowUpdate() {
        val deviceId = selectedDeviceId ?: deviceProfile.value?.deviceId ?: return
        viewModelScope.launch {
            runBusyAction("Releasing Loop connection for Polar Flow…") {
                repository.disconnect(deviceId)
                statusMessage = "Lodestone disconnected. Open Polar Flow, sync the Loop, close Flow, then return here and run sync."
            }
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

    fun acceptSleepEpisodeAsMain(id: Long) {
        viewModelScope.launch {
            runBusyAction("Saving sleep decision…") {
                val saved = repository.confirmSleepEpisode(
                    id = id,
                    episodeKind = SleepEpisodeKinds.MAIN_SLEEP,
                    source = SleepEpisodeSources.MIXED,
                    isPrimaryForReadiness = true,
                    notes = "Accepted as main sleep"
                )
                statusMessage = if (saved) {
                    "Sleep window confirmed for the current signal."
                } else {
                    "Sleep window was not found."
                }
            }
        }
    }

    fun acceptSleepEpisodeAsNap(id: Long) {
        viewModelScope.launch {
            runBusyAction("Saving nap decision…") {
                val saved = repository.confirmSleepEpisode(
                    id = id,
                    episodeKind = SleepEpisodeKinds.NAP,
                    source = SleepEpisodeSources.MIXED,
                    isPrimaryForReadiness = false,
                    notes = "Accepted as nap"
                )
                statusMessage = if (saved) {
                    "Nap saved as context."
                } else {
                    "Sleep/rest window was not found."
                }
            }
        }
    }

    fun markSleepEpisodeAsRest(id: Long) {
        viewModelScope.launch {
            runBusyAction("Saving rest decision…") {
                val saved = repository.confirmSleepEpisode(
                    id = id,
                    episodeKind = SleepEpisodeKinds.REST_CANDIDATE,
                    source = SleepEpisodeSources.MIXED,
                    isPrimaryForReadiness = false,
                    notes = "Marked as rest, not main sleep"
                )
                statusMessage = if (saved) {
                    "Rest window saved as context."
                } else {
                    "Sleep/rest window was not found."
                }
            }
        }
    }

    fun rejectSleepEpisodeCandidate(id: Long) {
        viewModelScope.launch {
            runBusyAction("Dismissing suggestion…") {
                val saved = repository.confirmSleepEpisode(
                    id = id,
                    episodeKind = SleepEpisodeKinds.REST_CANDIDATE,
                    source = SleepEpisodeSources.MIXED,
                    isPrimaryForReadiness = false,
                    notes = "Dismissed as not sleep"
                )
                statusMessage = if (saved) {
                    "Suggestion dismissed as not sleep."
                } else {
                    "Sleep/rest suggestion was not found."
                }
            }
        }
    }

    fun clearSleepEpisodeDecision(id: Long) {
        viewModelScope.launch {
            runBusyAction("Clearing sleep decision…") {
                repository.deleteSleepEpisode(id)
                statusMessage = "Sleep decision cleared. Run Check in or Catch up to refresh candidates."
            }
        }
    }

    fun editSleepEpisodeWindow(id: Long, startEpochMs: Long, endEpochMs: Long) {
        if (endEpochMs <= startEpochMs) {
            statusMessage = "Sleep/rest end must be after the start."
            return
        }
        viewModelScope.launch {
            runBusyAction("Saving edited window…") {
                val saved = repository.editSleepEpisodeWindow(
                    id = id,
                    startEpochMs = startEpochMs,
                    endEpochMs = endEpochMs,
                    notes = "Edited window"
                )
                statusMessage = if (saved) {
                    val candidateCount = refreshInferredSleepEpisodeCandidates(sleepEpisodeReviewDates.value)
                    "Sleep/rest window edited.${candidateCount.sleepCandidateSuffix()}"
                } else {
                    "Sleep/rest window was not found."
                }
            }
        }
    }

    fun addManualSleepWindow(sourceDate: String, startEpochMs: Long, endEpochMs: Long) {
        if (runCatching { LocalDate.parse(sourceDate) }.isFailure) {
            statusMessage = "Choose a valid date first."
            return
        }
        if (endEpochMs <= startEpochMs) {
            statusMessage = "Sleep/rest end must be after the start."
            return
        }
        viewModelScope.launch {
            runBusyAction("Saving manual sleep window…") {
                repository.addManualSleepWindow(
                    sourceDate = sourceDate,
                    startEpochMs = startEpochMs,
                    endEpochMs = endEpochMs
                )
                val candidateCount = refreshInferredSleepEpisodeCandidates(listOf(sourceDate))
                setSleepEpisodeReviewDates(sourceDate)
                statusMessage = "Manual sleep window saved for the current signal.${candidateCount.sleepCandidateSuffix()}"
            }
        }
    }

    fun editWakeMarker(id: Long, sourceDate: String, markerEpochMs: Long, markerSource: String) {
        val targetDate = when (markerSource) {
            WakeMarkerSources.GOING_TO_BED -> sleepTargetDateForBedtime(markerEpochMs).toString()
            WakeMarkerSources.IM_AWAKE -> resolveLodestoneDisplayDate(
                nowEpochMs = markerEpochMs,
                latestMorningReadSourceDate = morningRead.value?.sourceDate,
                wakeMarkers = recentWakeMarkers.value.filterNot { it.id == id }
            ).sourceDate
            else -> sourceDate
        }
        viewModelScope.launch {
            runBusyAction("Saving marker time…") {
                val saved = repository.updateWakeMarkerTime(
                    id = id,
                    sourceDate = targetDate,
                    markerEpochMs = markerEpochMs
                )
                statusMessage = if (saved) {
                    val candidateCount = refreshInferredSleepEpisodeCandidates(listOf(targetDate))
                    "Marker time saved.${candidateCount.sleepCandidateSuffix()}"
                } else {
                    "Marker was not found."
                }
            }
        }
    }

    fun markNoMainSleep(sourceDate: String) {
        if (runCatching { LocalDate.parse(sourceDate) }.isFailure) {
            statusMessage = "Choose a valid date first."
            return
        }
        viewModelScope.launch {
            runBusyAction("Saving no-sleep decision…") {
                repository.markNoMainSleep(sourceDate)
                statusMessage = "No main sleep saved for $sourceDate."
            }
        }
    }

    fun updateCheckInDate(value: String) {
        selectCheckInDate(value)
    }

    fun setCheckInDateToToday() {
        selectCheckInDate(currentLodestoneDate())
    }

    fun setCheckInDateToYesterday() {
        selectCheckInDate(
            runCatching { LocalDate.parse(currentLodestoneDate()).minusDays(1).toString() }
                .getOrElse { LocalDate.now().minusDays(1).toString() }
        )
    }

    fun resetSelectedReviewDate() {
        val date = checkInDate
        if (runCatching { LocalDate.parse(date) }.isFailure) {
            statusMessage = "Choose a valid date first."
            return
        }
        viewModelScope.launch {
            if (date == currentLodestoneDate()) {
                runBusyAction("Resetting today…") {
                    clearDailyCheckInDraft()
                    dailyReviewRepository.clearFoodImportForDate(date)
                    currentFoodSummary = null
                    currentDailyWeight = null
                    statusMessage = "Reset today's journal and food import."
                }
            } else {
                val existing = dailyReviewRepository.getDailyCheckIn(date)
                if (existing != null) {
                    hydrateDailyCheckIn(existing)
                    statusMessage = "Reloaded saved journal for $date."
                } else {
                    clearDailyCheckInDraft()
                    statusMessage = "No saved journal for $date."
                }
                refreshFoodImportForDate(date)
            }
        }
    }

    fun setEveningOutcome(status: TrafficLightStatus?) {
        eveningOutcomeDraft = status
        if (status != null) {
            showOutcomeValidation = false
        }
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

    fun updateManualGripStrengthKg(value: String) {
        manualGripStrengthKgDraft = sanitizeGripStrengthInput(value)
    }

    fun updateMostlyHorizontal(value: Boolean) {
        markDayShapeCaptured()
        mostlyHorizontalDraft = value
    }

    fun updateLeftHouse(value: Boolean) {
        markDayShapeCaptured()
        leftHouseDraft = value
    }

    fun updateMajorTask(value: Boolean) {
        markDayShapeCaptured()
        majorTaskDraft = value
        if (!value) {
            majorTaskTypeDraft = null
        }
    }

    fun updateMajorTaskType(value: String?) {
        markDayShapeCaptured()
        majorTaskTypeDraft = value?.takeIf { it in majorTaskTypes }
        majorTaskDraft = majorTaskTypeDraft != null || majorTaskDraft
    }

    fun updatePemPaybackToday(value: Boolean) {
        markDayShapeCaptured()
        pemPaybackTodayDraft = value
        if (!value) {
            paybackPeakTodayDraft = false
            if (paybackPeakConfidenceDraft in peakOnlyConfidences) {
                paybackPeakConfidenceDraft = null
            }
        }
    }

    fun updatePaybackPeakToday(value: Boolean) {
        markDayShapeCaptured()
        paybackPeakTodayDraft = value
        if (value) {
            pemPaybackTodayDraft = true
            paybackPeakConfidenceDraft = PaybackPeakConfidence.USER_SELECTED
        } else if (paybackPeakConfidenceDraft in peakOnlyConfidences) {
            paybackPeakConfidenceDraft = null
        }
    }

    fun markPaybackPeakDate(sourceDate: String) {
        viewModelScope.launch {
            dailyReviewRepository.updatePaybackPeakMarker(
                sourceDate = sourceDate,
                paybackPeakToday = true,
                paybackPeakConfidence = PaybackPeakConfidence.USER_SELECTED
            )
            if (sourceDate == checkInDate) {
                dayShapeCapturedDraft = true
                pemPaybackTodayDraft = true
                paybackPeakTodayDraft = true
                paybackPeakConfidenceDraft = PaybackPeakConfidence.USER_SELECTED
            }
            statusMessage = "Marked $sourceDate as the payback peak."
        }
    }

    fun markPaybackPeakNotSure(episodeEndDate: String) {
        viewModelScope.launch {
            dailyReviewRepository.updatePaybackPeakMarker(
                sourceDate = episodeEndDate,
                paybackPeakToday = false,
                paybackPeakConfidence = PaybackPeakConfidence.NOT_SURE
            )
            statusMessage = "Payback peak left unknown for that spell."
        }
    }

    fun dismissPaybackPeakPrompt(episodeEndDate: String) {
        viewModelScope.launch {
            dailyReviewRepository.updatePaybackPeakMarker(
                sourceDate = episodeEndDate,
                paybackPeakToday = false,
                paybackPeakConfidence = PaybackPeakConfidence.DISMISSED
            )
            statusMessage = "Payback peak prompt dismissed."
        }
    }

    fun loadDailyCheckIn(date: String = checkInDate) {
        setSleepEpisodeReviewDates(date)
        reviewLoadJob?.cancel()
        reviewLoadJob = viewModelScope.launch {
            val existing = dailyReviewRepository.getDailyCheckIn(date)
            if (date != checkInDate) return@launch
            if (existing != null) {
                hydrateDailyCheckIn(existing)
                statusMessage = "Loaded saved check-in for $date."
            } else {
                setSleepEpisodeReviewDates(date)
                statusMessage = "No saved check-in for $date. Current draft left unchanged."
            }
            refreshFoodImportForDate(date)
        }
    }

    fun saveDailyCheckIn() {
        viewModelScope.launch {
            if (eveningOutcomeDraft == null) {
                showOutcomeValidation = true
                statusMessage = "Select how the day ended before saving."
                return@launch
            }
            showOutcomeValidation = false
            runBusyAction("Saving evening check-in…") {
                val context = getApplication<Application>()
                val savedDate = checkInDate
                val foodImportResult = runCatching {
                    dailyReviewRepository.importLatestFoodCsvFromDownloads(context, savedDate).getOrThrow()
                }
                currentFoodSummary = dailyReviewRepository.getFoodDailySummary(savedDate)
                currentDailyWeight = dailyReviewRepository.getDailyWeight(savedDate)

                val outcome = eveningOutcomeDraft!!
                dailyReviewRepository.saveDailyCheckIn(
                    sourceDate = savedDate,
                    eveningOutcome = outcome.name,
                    approachToDay = approachToDayDraft?.name,
                    muscleWeaknessToday = muscleWeaknessTodayDraft,
                    notes = notesDraft,
                    dayShapeCaptured = dayShapeCapturedDraft,
                    mostlyHorizontal = if (dayShapeCapturedDraft) mostlyHorizontalDraft else null,
                    leftHouse = if (dayShapeCapturedDraft) leftHouseDraft else null,
                    majorTask = if (dayShapeCapturedDraft) majorTaskDraft else null,
                    majorTaskType = if (dayShapeCapturedDraft && majorTaskDraft) majorTaskTypeDraft else null,
                    pemPaybackToday = if (dayShapeCapturedDraft) pemPaybackTodayDraft else null,
                    paybackPeakToday = if (dayShapeCapturedDraft) paybackPeakTodayDraft else null,
                    paybackPeakConfidence = paybackPeakConfidenceForSave(),
                    manualGripStrengthKg = gripStrengthKgOrNull(manualGripStrengthKgDraft)
                )
                if (!pemPaybackTodayDraft) {
                    autoMarkSingleDayPaybackPeakIfNeeded(savedDate)
                }
                clearDailyCheckInDraft()
                saveSuccessFlash = true
                statusMessage = buildSaveStatusMessage(savedDate, foodImportResult)
            }
        }
    }

    fun clearOutcomeValidation() {
        showOutcomeValidation = false
    }

    fun clearSaveSuccessFlash() {
        saveSuccessFlash = false
    }

    private fun buildSaveStatusMessage(savedDate: String, foodImportResult: Result<Int>): String {
        val foodMessage = when {
            foodImportResult.isSuccess && foodImportResult.getOrDefault(0) > 0 -> {
                val parts = listOfNotNull(
                    currentFoodSummary?.totalCaloriesKcal?.let { "$it kcal" },
                    currentFoodSummary?.eventCount?.let { "$it items" },
                    currentDailyWeight?.weightKg?.let { String.format(java.util.Locale.UK, "%.1f kg", it) }
                )
                if (parts.isEmpty()) " Imported food log." else " Imported food log (${parts.joinToString(", ")})."
            }
            foodImportResult.isFailure && foodImportResult.exceptionOrNull()?.message?.contains("No food_log CSV", ignoreCase = true) == true ->
                if (currentFoodSummary != null || currentDailyWeight != null) {
                    " No new food log found; existing food data left unchanged."
                } else {
                    " No food log found for that date."
                }
            foodImportResult.isFailure ->
                " Food import failed: ${foodImportResult.exceptionOrNull()?.message ?: "unknown error"}."
            else ->
                " No dated food entries were imported."
        }
        return "Saved journal entry for $savedDate.$foodMessage Entry fields cleared."
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
            "awake_sync" -> markAwakeAndSync()
            "health_connect_export" -> exportHealthConnectSleepAnalysis()
        }
    }

    fun refreshHealthConnectPermissions() {
        viewModelScope.launch {
            healthConnectPermissionsGranted = runCatching {
                healthConnectAnalysisExporter.hasRequiredPermissions()
            }.getOrDefault(false)
        }
    }

    fun handleHealthConnectPermissionResult(granted: Set<String>) {
        healthConnectPermissionsGranted = granted.containsAll(HealthConnectAnalysisExporter.REQUIRED_PERMISSIONS)
        statusMessage = if (healthConnectPermissionsGranted) {
            "Health Connect access granted."
        } else {
            "Health Connect access was not granted. Open Health Connect settings and allow Sleep, Heart rate, and HRV for Lodestone."
        }
    }

    fun notifyHealthConnectSettingsOpened() {
        statusMessage = "Opened Health Connect settings. Choose Lodestone under app permissions and allow Sleep, Heart rate, and HRV."
    }

    fun exportHealthConnectSleepAnalysis() {
        val date = runCatching { LocalDate.parse(checkInDate) }.getOrElse { LocalDate.now() }
        viewModelScope.launch {
            runBusyAction("Exporting Health Connect sleep analysis…") {
                val file = healthConnectAnalysisExporter.exportSleepAnalysis(date)
                statusMessage = "Health Connect sleep export saved: ${file.absolutePath}"
                refreshHealthConnectPermissions()
            }
        }
    }

    fun importSleep2Screenshot(uri: Uri) {
        val date = runCatching { LocalDate.parse(checkInDate) }.getOrElse { LocalDate.now() }
        val context = getApplication<Application>()
        viewModelScope.launch {
            runBusyAction("Importing Sleep2 screenshot for $date…") {
                val file = Sleep2ScreenshotImporter(context).importScreenshot(uri, date)
                lastSleep2ScreenshotPath = file.absolutePath
                statusMessage = "Sleep2 screenshot saved for $date: ${file.absolutePath}"
            }
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
            resetCheckInIntent()
        }
    }

    private fun persistAppSettings() {
        viewModelScope.launch {
            repository.saveAppSettings(
                selectedDeviceId = selectedDeviceId,
                syncWindowConfig = syncWindowConfig,
                lastKnownFirmwareBySelectedDevice = runtimeState.value.firmwareVersion,
                markerMode = markerMode.name
            )
        }
    }

    private fun saveSleepReportRetryCooldown(cooldownUntilEpochMs: Long) {
        sleepReportRetryCooldownUntilEpochMs = cooldownUntilEpochMs
        getApplication<Application>()
            .getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(SLEEP_REPORT_RETRY_COOLDOWN_UNTIL, cooldownUntilEpochMs)
            .apply()
    }

    private fun remainingMinutes(remainingMs: Long): Int =
        ceil(remainingMs / 60_000.0).toInt().coerceAtLeast(1)

    private fun hydrateDailyCheckIn(entity: DailyCheckInEntity) {
        setSleepEpisodeReviewDates(entity.sourceDate)
        eveningOutcomeDraft = runCatching { TrafficLightStatus.valueOf(entity.eveningOutcome) }.getOrNull()
        approachToDayDraft = entity.approachToDay?.let { value ->
            runCatching { TrafficLightStatus.valueOf(value) }.getOrNull()
        }
        muscleWeaknessTodayDraft = entity.muscleWeaknessToday
        manualGripStrengthKgDraft = entity.manualGripStrengthKg?.toString().orEmpty()
        notesDraft = entity.notes.orEmpty()
        dayShapeCapturedDraft = entity.dayShapeCaptured == true
        mostlyHorizontalDraft = dayShapeCapturedDraft && entity.mostlyHorizontal == true
        leftHouseDraft = dayShapeCapturedDraft && entity.leftHouse == true
        majorTaskDraft = dayShapeCapturedDraft && entity.majorTask == true
        majorTaskTypeDraft = entity.majorTaskType?.takeIf { it in majorTaskTypes && majorTaskDraft }
        pemPaybackTodayDraft = dayShapeCapturedDraft && entity.pemPaybackToday == true
        paybackPeakTodayDraft = dayShapeCapturedDraft && entity.paybackPeakToday == true
        paybackPeakConfidenceDraft = entity.paybackPeakConfidence
    }

    private fun selectCheckInDate(date: String) {
        setSleepEpisodeReviewDates(date)
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
                clearDailyCheckInDraft()
            }
            refreshFoodImportForDate(date)
        }
    }

    private fun markDayShapeCaptured() {
        dayShapeCapturedDraft = true
    }

    private fun clearDailyCheckInDraft() {
        eveningOutcomeDraft = null
        approachToDayDraft = null
        muscleWeaknessTodayDraft = false
        manualGripStrengthKgDraft = ""
        notesDraft = ""
        clearDayShapeDraft()
    }

    private fun clearDayShapeDraft() {
        dayShapeCapturedDraft = false
        mostlyHorizontalDraft = false
        leftHouseDraft = false
        majorTaskDraft = false
        majorTaskTypeDraft = null
        pemPaybackTodayDraft = false
        paybackPeakTodayDraft = false
        paybackPeakConfidenceDraft = null
    }

    private fun paybackPeakConfidenceForSave(): String? {
        if (!dayShapeCapturedDraft) return null
        if (paybackPeakTodayDraft) {
            return paybackPeakConfidenceDraft ?: PaybackPeakConfidence.USER_SELECTED
        }
        return paybackPeakConfidenceDraft?.takeIf {
            it == PaybackPeakConfidence.NOT_SURE || it == PaybackPeakConfidence.DISMISSED
        }
    }

    private suspend fun autoMarkSingleDayPaybackPeakIfNeeded(nonPemDate: String) {
        val episode = findEndedPaybackEpisodeBefore(
            activeDate = nonPemDate,
            checkIns = dailyCheckIns.value,
            activePemMarked = false
        )
        if (episode?.pemDates?.size == 1) {
            dailyReviewRepository.updatePaybackPeakMarker(
                sourceDate = episode.pemDates.single(),
                paybackPeakToday = true,
                paybackPeakConfidence = PaybackPeakConfidence.AUTO_SINGLE
            )
        }
    }

    private fun setSleepEpisodeReviewDates(
        activeDate: String,
        reviewDates: List<String> = listOf(activeDate)
    ) {
        checkInDate = activeDate
        sleepEpisodeReviewDates.value = reviewDates.distinct().ifEmpty { listOf(activeDate) }
    }

    private fun refreshFoodImportForDate(date: String) {
        foodSummaryJob?.cancel()
        foodSummaryJob = viewModelScope.launch {
            currentFoodSummary = dailyReviewRepository.getFoodDailySummary(date)
            currentDailyWeight = dailyReviewRepository.getDailyWeight(date)
        }
    }

    fun currentLodestoneDate(): String =
        resolveLodestoneDisplayDate(
            latestMorningReadSourceDate = morningRead.value?.sourceDate,
            wakeMarkers = recentWakeMarkers.value
        ).sourceDate

    private suspend fun refreshInferredSleepEpisodeCandidates(sourceDates: List<String>): Int =
        sourceDates.distinct().sumOf { sourceDate ->
            repository.refreshInferredSleepEpisodeCandidatesForDate(sourceDate)
        }

    private fun catchUpSourceDates(today: String): List<String> {
        return catchUpRepairSourceDates(
            today = today,
            latestReadSourceDate = morningRead.value?.sourceDate
        )
    }

    private fun Int.sleepCandidateSuffix(): String =
        if (this > 0) {
            " Found $this sleep/rest candidate${if (this == 1) "" else "s"}."
        } else {
            " No sleep/rest candidates found yet."
        }

    companion object {
        private const val SETTINGS_PREFS_NAME = "lodestone_settings_tools"
        private const val SLEEP_REPORT_RETRY_COOLDOWN_UNTIL = "sleep_report_retry_cooldown_until"
        private const val SLEEP_REPORT_RETRY_COOLDOWN_MS = 30 * 60 * 1000L
        private const val CHECK_IN_INTENT_RESET_MS = 90_000L
        private val majorTaskTypes = setOf(
            JournalMajorTaskTypes.WORK_FROM_HOME,
            JournalMajorTaskTypes.SITE_VISIT,
            JournalMajorTaskTypes.ADMIN_ASSESSMENT,
            JournalMajorTaskTypes.OTHER_MAJOR_TASK
        )
        private val peakOnlyConfidences = setOf(
            PaybackPeakConfidence.USER_SELECTED,
            PaybackPeakConfidence.AUTO_SINGLE
        )
        private fun loadSleepReportRetryCooldown(context: Context): Long =
            context
                .getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(SLEEP_REPORT_RETRY_COOLDOWN_UNTIL, 0L)

        val Factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(
                modelClass: Class<T>,
                extras: CreationExtras
            ): T {
                val app = checkNotNull(extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]) as HealthMonitorApp
                if (modelClass.isAssignableFrom(ProbeViewModel::class.java)) {
                    @Suppress("UNCHECKED_CAST")
                    return ProbeViewModel(
                        app,
                        app.container.repository,
                        app.container.syncCoordinator,
                        app.container.dailyReviewRepository,
                        app.container.healthConnectAnalysisExporter
                    ) as T
                }
                error("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}

internal fun sanitizeGripStrengthInput(value: String): String {
    var decimalSeen = false
    return buildString {
        value.forEach { rawChar ->
            val char = if (rawChar == ',') '.' else rawChar
            when {
                char.isDigit() -> append(char)
                char == '.' && !decimalSeen -> {
                    decimalSeen = true
                    append(char)
                }
            }
        }
    }.take(6)
}

internal fun gripStrengthKgOrNull(value: String): Double? =
    value.toDoubleOrNull()
        ?.takeIf { it in 0.1..150.0 }

private fun String?.toNowMarkerMode(): NowMarkerMode =
    runCatching { NowMarkerMode.valueOf(this ?: "") }.getOrDefault(NowMarkerMode.BEDTIME_AND_WAKING)

fun NowMarkerMode.settingsLabel(): String =
    when (this) {
        NowMarkerMode.NO_MARKERS -> "No markers"
        NowMarkerMode.BEDTIME -> "Bedtime"
        NowMarkerMode.BEDTIME_AND_WAKING -> "Bedtime + waking"
    }

private fun NowCheckInIntent.markerSavedLabel(): String =
    when (this) {
        NowCheckInIntent.INFO -> "Check-in"
        NowCheckInIntent.BEDTIME -> "Bedtime marker"
        NowCheckInIntent.WAKING -> "Wake marker"
    }
