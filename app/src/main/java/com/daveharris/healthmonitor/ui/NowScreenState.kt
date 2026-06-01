package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.ACTIVE_BEDTIME_MARKER_DURATION
import com.daveharris.healthmonitor.ACTIVE_WAKE_MARKER_DURATION
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import com.daveharris.healthmonitor.data.WakeMarkerSources
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

enum class NowDataAvailability(val label: String) {
    PRESENT("Present"),
    MISSING("Missing"),
    PENDING("Pending"),
    STALE("Stale"),
    PARTIAL("Partial"),
    NOT_APPLICABLE("Not applicable")
}

enum class NowCurrentStateKind {
    WAITING_FOR_DATA,
    SYNCING,
    SLEEP_MARKED,
    PROVISIONAL_READ,
    READY,
    NO_MAIN_SLEEP
}

enum class NowMarkerMode {
    NO_MARKERS,
    BEDTIME,
    BEDTIME_AND_WAKING
}

enum class NowCheckInIntent {
    INFO,
    BEDTIME,
    WAKING
}

enum class NowMarkerState {
    NOT_APPLICABLE,
    NONE,
    ACTIVE_BEDTIME,
    RESOLVED_BEDTIME,
    STALE_BEDTIME,
    RECENT_WAKE,
    STALE_WAKE
}

enum class NowAnalysisWindowSourceType {
    MODEL_ESTIMATE,
    USER_SELECTED,
    MARKER_DERIVED,
    LOOP_REPORT,
    NO_MAIN_SLEEP,
    PENDING,
    UNKNOWN
}

data class NowDataPoint(
    val label: String,
    val availability: NowDataAvailability,
    val detail: String
)

data class NowCurrentState(
    val kind: NowCurrentStateKind,
    val availability: NowDataAvailability,
    val status: TrafficLightStatus?,
    val label: String,
    val qualifier: String,
    val message: String
)

data class NowSignalRobustness(
    val availability: NowDataAvailability,
    val label: String,
    val basisLabel: String,
    val missingInputs: List<String>,
    val supportingGaps: List<String>,
    val sleepReport: NowDataPoint,
    val ppi: NowDataPoint,
    val baseline: NowDataPoint,
    val nightlyRecharge: NowDataPoint
)

data class NowStateStability(
    val availability: NowDataAvailability,
    val label: String,
    val detail: String
)

data class NowFreshness(
    val lastUsed: NowDataPoint,
    val loopSync: NowDataPoint,
    val dailyReview: NowDataPoint,
    val catchUpPrompt: String?
)

data class NowMarkerStatus(
    val mode: NowMarkerMode,
    val state: NowMarkerState,
    val availability: NowDataAvailability,
    val label: String,
    val detail: String,
    val bedtime: NowDataPoint,
    val waking: NowDataPoint
)

data class NowActionAvailability(
    val visible: Boolean,
    val enabled: Boolean,
    val unavailableReason: String? = null
)

data class NowPrimaryActions(
    val intent: NowCheckInIntent,
    val checkIn: NowActionAvailability,
    val catchUp: NowActionAvailability,
    val bedtime: NowActionAvailability,
    val waking: NowActionAvailability
)

data class NowAnalysisWindowProvenance(
    val sourceDate: String,
    val startEpochMs: Long?,
    val endEpochMs: Long?,
    val sourceType: NowAnalysisWindowSourceType,
    val label: String,
    val timeRangeLabel: String,
    val durationLabel: String,
    val confidenceLabel: String,
    val selectedByUser: Boolean,
    val reason: String
)

data class NowScreenState(
    val today: String,
    val activeMorningRead: MorningReadSnapshot?,
    val activeAnalysisWindow: NowAnalysisWindowProvenance,
    val currentState: NowCurrentState,
    val signalRobustness: NowSignalRobustness,
    val stateStability: NowStateStability,
    val freshness: NowFreshness,
    val markerStatus: NowMarkerStatus,
    val primaryActions: NowPrimaryActions,
    val deviceConnection: NowDataPoint,
    val readinessStatus: TodayReadinessStatus
)

fun buildNowScreenState(
    today: String,
    morningRead: MorningReadSnapshot?,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    runtime: DeviceRuntimeState,
    selectedDeviceId: String?,
    isBusy: Boolean,
    markerMode: NowMarkerMode = NowMarkerMode.BEDTIME_AND_WAKING,
    checkInIntent: NowCheckInIntent = NowCheckInIntent.INFO,
    nowEpochMs: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): NowScreenState {
    val relevantMorningRead = morningRead?.takeIf { it.sourceDate == today }
    val latestReadinessSync = syncRuns.latestReadinessSync()
    val syncRunning = isBusy || latestReadinessSync?.status == "running"
    val noMainSleep = sleepEpisodeReviewState.activeDateGroup?.hasNoSleepDecision == true
    val resolvingWindowAvailable = noMainSleep ||
        sleepEpisodeReviewState.activeDateGroup?.hasPrimaryReadinessWindow == true ||
        relevantMorningRead.hasEstablishedSleepWindow()
    val markerStatus = buildMarkerStatus(
        markerMode = markerMode,
        wakeMarkers = wakeMarkers,
        nowEpochMs = nowEpochMs,
        zoneId = zoneId,
        resolvingWindowAvailable = resolvingWindowAvailable
    )
    val activeMorningRead = relevantMorningRead
        ?.takeUnless { markerStatus.state == NowMarkerState.ACTIVE_BEDTIME }
        ?.takeUnless { noMainSleep }
    val activeAnalysisWindow = buildActiveAnalysisWindow(
        today = today,
        activeMorningRead = activeMorningRead,
        sleepEpisodeReviewState = sleepEpisodeReviewState,
        noMainSleep = noMainSleep,
        markerStatus = markerStatus
    )
    val hasFinalSleep = relevantMorningRead?.sleepDataReady == true
    val hasPpi = relevantMorningRead.hasPpiSignal()
    val catchUpPrompt = catchUpPrompt(today, morningRead)
    val freshness = buildFreshness(
        nowEpochMs = nowEpochMs,
        syncRuns = syncRuns,
        wakeMarkers = wakeMarkers,
        dailyCheckIns = dailyCheckIns,
        catchUpPrompt = catchUpPrompt
    )
    val signalRobustness = buildSignalRobustness(
        morningRead = relevantMorningRead,
        hasFinalSleep = hasFinalSleep,
        hasPpi = hasPpi,
        noMainSleep = noMainSleep,
        syncRunning = syncRunning
    )
    val stateStability = buildStateStability(relevantMorningRead, noMainSleep)
    val currentState = buildCurrentState(
        morningRead = relevantMorningRead,
        noMainSleep = noMainSleep,
        syncRunning = syncRunning,
        markerStatus = markerStatus,
        hasFinalSleep = hasFinalSleep,
        hasPpi = hasPpi
    )
    val primaryActions = buildPrimaryActions(
        markerMode = markerMode,
        checkInIntent = checkInIntent,
        selectedDeviceId = selectedDeviceId,
        isBusy = isBusy,
        catchUpPrompt = catchUpPrompt
    )
    val deviceConnection = buildDeviceConnection(runtime, selectedDeviceId)
    val readinessStatus = buildTodayReadinessStatus(
        currentState = currentState,
        signalRobustness = signalRobustness,
        markerStatus = markerStatus,
        freshness = freshness,
        morningRead = relevantMorningRead,
        hasFinalSleep = hasFinalSleep,
        hasPpi = hasPpi,
        syncRunning = syncRunning,
        noMainSleep = noMainSleep,
        analysisWindow = activeAnalysisWindow
    )
    return NowScreenState(
        today = today,
        activeMorningRead = activeMorningRead,
        activeAnalysisWindow = activeAnalysisWindow,
        currentState = currentState,
        signalRobustness = signalRobustness,
        stateStability = stateStability,
        freshness = freshness,
        markerStatus = markerStatus,
        primaryActions = primaryActions,
        deviceConnection = deviceConnection,
        readinessStatus = readinessStatus
    )
}

private fun buildCurrentState(
    morningRead: MorningReadSnapshot?,
    noMainSleep: Boolean,
    syncRunning: Boolean,
    markerStatus: NowMarkerStatus,
    hasFinalSleep: Boolean,
    hasPpi: Boolean
): NowCurrentState =
    when {
        noMainSleep -> NowCurrentState(
            kind = NowCurrentStateKind.NO_MAIN_SLEEP,
            availability = NowDataAvailability.PRESENT,
            status = null,
            label = "No main sleep recorded",
            qualifier = "Sleep window not applicable",
            message = "No main sleep is saved for this date, so Lodestone will not invent a sleep window."
        )
        markerStatus.state == NowMarkerState.ACTIVE_BEDTIME -> NowCurrentState(
            kind = NowCurrentStateKind.SLEEP_MARKED,
            availability = NowDataAvailability.PENDING,
            status = null,
            label = "Bedtime marked",
            qualifier = "Waiting for next check-in",
            message = "Bedtime is saved. A normal Check in can still sync current data when you are ready."
        )
        syncRunning -> NowCurrentState(
            kind = NowCurrentStateKind.SYNCING,
            availability = NowDataAvailability.PENDING,
            status = morningRead?.status,
            label = "Checking Loop",
            qualifier = "Sync in progress",
            message = "Lodestone is connecting and pulling the current core data."
        )
        hasFinalSleep -> NowCurrentState(
            kind = NowCurrentStateKind.READY,
            availability = NowDataAvailability.PRESENT,
            status = morningRead?.status,
            label = "Current signal: ${morningRead?.status.readinessLabel()}",
            qualifier = "Final Loop sleep context",
            message = "The current read includes final Loop sleep context. Use it as pacing context, not a verdict."
        )
        hasPpi -> NowCurrentState(
            kind = NowCurrentStateKind.PROVISIONAL_READ,
            availability = NowDataAvailability.PARTIAL,
            status = morningRead?.status,
            label = "Provisional read: ${morningRead?.status.readinessLabel()}",
            qualifier = "PPI received",
            message = if (morningRead?.overnightAutonomicSource == "raw_ppi_pending_manual_sleep_window") {
                "PPI is available, but Lodestone does not yet have a usable sleep window for a current read."
            } else {
                "PPI is available before the final Loop sleep report, so this current signal remains provisional."
            }
        )
        else -> NowCurrentState(
            kind = NowCurrentStateKind.WAITING_FOR_DATA,
            availability = NowDataAvailability.MISSING,
            status = null,
            label = "Awaiting check-in",
            qualifier = "No current read",
            message = "Tap Check in to sync current data. Missing or stale markers do not block this."
        )
    }

private fun buildSignalRobustness(
    morningRead: MorningReadSnapshot?,
    hasFinalSleep: Boolean,
    hasPpi: Boolean,
    noMainSleep: Boolean,
    syncRunning: Boolean
): NowSignalRobustness {
    val sleepReport = when {
        noMainSleep -> NowDataPoint("Sleep report", NowDataAvailability.NOT_APPLICABLE, "No main sleep saved")
        hasFinalSleep -> NowDataPoint("Sleep report", NowDataAvailability.PRESENT, "Final Loop sleep context present")
        syncRunning -> NowDataPoint("Sleep report", NowDataAvailability.PENDING, "Checking Loop")
        hasPpi -> NowDataPoint("Sleep report", NowDataAvailability.PENDING, "Awaiting final Loop report")
        else -> NowDataPoint("Sleep report", NowDataAvailability.MISSING, "Not synced yet")
    }
    val ppi = when {
        hasPpi -> NowDataPoint("24/7 PPI", NowDataAvailability.PRESENT, ppiReceiptLabelForState(morningRead))
        syncRunning -> NowDataPoint("24/7 PPI", NowDataAvailability.PENDING, "Checking Loop")
        else -> NowDataPoint("24/7 PPI", NowDataAvailability.MISSING, "No current PPI yet")
    }
    val baseline = when {
        morningRead == null -> NowDataPoint("Baseline", NowDataAvailability.MISSING, "No morning read yet")
        morningRead.baselineReady -> NowDataPoint("Baseline", NowDataAvailability.PRESENT, "Personal baseline ready")
        else -> NowDataPoint("Baseline", NowDataAvailability.PARTIAL, "Personal baseline still forming")
    }
    val nightlyRecharge = when {
        morningRead?.nightlyRmssd != null -> NowDataPoint("Nightly Recharge", NowDataAvailability.PRESENT, "RMSSD available")
        noMainSleep -> NowDataPoint("Nightly Recharge", NowDataAvailability.NOT_APPLICABLE, "No main sleep saved")
        morningRead == null -> NowDataPoint("Nightly Recharge", NowDataAvailability.MISSING, "No morning read yet")
        else -> NowDataPoint("Nightly Recharge", NowDataAvailability.PARTIAL, "RMSSD unavailable")
    }
    val missingInputs = listOf(sleepReport, ppi)
        .filter { it.availability == NowDataAvailability.MISSING || it.availability == NowDataAvailability.PENDING }
        .map { it.label }
    val supportingGaps = listOf(baseline, nightlyRecharge)
        .filter { it.availability == NowDataAvailability.MISSING || it.availability == NowDataAvailability.PARTIAL }
        .map { it.label }
    val availability = when {
        syncRunning -> NowDataAvailability.PENDING
        missingInputs.isEmpty() && supportingGaps.isEmpty() -> NowDataAvailability.PRESENT
        hasPpi || hasFinalSleep || noMainSleep -> NowDataAvailability.PARTIAL
        else -> NowDataAvailability.MISSING
    }
    val label = when (availability) {
        NowDataAvailability.PRESENT -> "Well supported"
        NowDataAvailability.PARTIAL -> "Partial"
        NowDataAvailability.PENDING -> "Checking"
        NowDataAvailability.MISSING -> "Missing data"
        NowDataAvailability.STALE -> "Stale"
        NowDataAvailability.NOT_APPLICABLE -> "Not applicable"
    }
    return NowSignalRobustness(
        availability = availability,
        label = label,
        basisLabel = basisLabel(morningRead, noMainSleep),
        missingInputs = missingInputs,
        supportingGaps = supportingGaps,
        sleepReport = sleepReport,
        ppi = ppi,
        baseline = baseline,
        nightlyRecharge = nightlyRecharge
    )
}

private fun buildStateStability(
    morningRead: MorningReadSnapshot?,
    noMainSleep: Boolean
): NowStateStability {
    if (noMainSleep) {
        return NowStateStability(
            availability = NowDataAvailability.NOT_APPLICABLE,
            label = "Not applicable",
            detail = "No main sleep is saved for this date."
        )
    }
    val goodEpochs = morningRead?.rawPpiGoodEpochCount
    val coverageHours = morningRead?.rawPpiCoverageHours
    if (goodEpochs == null || coverageHours == null) {
        return NowStateStability(
            availability = NowDataAvailability.MISSING,
            label = "TBC",
            detail = "Need enough PPI coverage before describing stability."
        )
    }
    val poorEpochs = morningRead.rawPpiPoorEpochCount ?: 0
    val label = when {
        goodEpochs < 12 || coverageHours < 3.0 -> "Brittle"
        poorEpochs > goodEpochs / 3 -> "Brittle"
        poorEpochs > 0 || coverageHours < 5.0 -> "Mixed"
        else -> "Stable"
    }
    return NowStateStability(
        availability = NowDataAvailability.PRESENT,
        label = label,
        detail = "$goodEpochs usable PPI windows across ${String.format(java.util.Locale.UK, "%.1fh", coverageHours)}."
    )
}

private fun buildFreshness(
    nowEpochMs: Long,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    catchUpPrompt: String?
): NowFreshness {
    val latestSyncEpochMs = syncRuns.maxOfOrNull { it.endedAtEpochMs ?: it.startedAtEpochMs }
    val latestMarkerEpochMs = wakeMarkers
        .filterNot { it.notes == "manual awake command" }
        .maxOfOrNull { it.markerEpochMs }
    val latestReviewEpochMs = dailyCheckIns.maxOfOrNull { it.updatedAtEpochMs }
    val latestUsedEpochMs = listOfNotNull(latestSyncEpochMs, latestMarkerEpochMs, latestReviewEpochMs).maxOrNull()
    return NowFreshness(
        lastUsed = latestUsedEpochMs.toFreshnessPoint(
            label = "Last used",
            nowEpochMs = nowEpochMs,
            missingDetail = "No recent input"
        ),
        loopSync = latestSyncEpochMs.toFreshnessPoint(
            label = "Loop sync",
            nowEpochMs = nowEpochMs,
            missingDetail = "Not synced yet"
        ),
        dailyReview = latestReviewEpochMs.toFreshnessPoint(
            label = "Daily review",
            nowEpochMs = nowEpochMs,
            missingDetail = "No journal entry yet"
        ),
        catchUpPrompt = catchUpPrompt
    )
}

private fun buildMarkerStatus(
    markerMode: NowMarkerMode,
    wakeMarkers: List<WakeMarkerEntity>,
    nowEpochMs: Long,
    zoneId: ZoneId,
    resolvingWindowAvailable: Boolean
): NowMarkerStatus {
    if (markerMode == NowMarkerMode.NO_MARKERS) {
        val hidden = NowDataPoint("Marker", NowDataAvailability.NOT_APPLICABLE, "Front-page markers hidden")
        return NowMarkerStatus(
            mode = markerMode,
            state = NowMarkerState.NOT_APPLICABLE,
            availability = NowDataAvailability.NOT_APPLICABLE,
            label = "Check-in only",
            detail = "Marker actions are hidden on the Now screen.",
            bedtime = hidden,
            waking = hidden
        )
    }
    val realMarkers = wakeMarkers.filterNot { it.notes == "manual awake command" }
    val latestBedtime = realMarkers
        .filter { it.markerSource == WakeMarkerSources.GOING_TO_BED }
        .maxByOrNull { it.markerEpochMs }
    val latestWake = realMarkers
        .filter { it.markerSource == WakeMarkerSources.IM_AWAKE }
        .maxByOrNull { it.markerEpochMs }
    val latestMarker = realMarkers.maxByOrNull { it.markerEpochMs }
    val bedtimePoint = latestBedtime.toMarkerPoint(
        label = "Bedtime marker",
        nowEpochMs = nowEpochMs,
        zoneId = zoneId,
        activeDuration = ACTIVE_BEDTIME_MARKER_DURATION
    )
    val wakePoint = if (markerMode == NowMarkerMode.BEDTIME_AND_WAKING) {
        latestWake.toMarkerPoint(
            label = "Wake marker",
            nowEpochMs = nowEpochMs,
            zoneId = zoneId,
            activeDuration = ACTIVE_WAKE_MARKER_DURATION
        )
    } else {
        NowDataPoint("Wake marker", NowDataAvailability.NOT_APPLICABLE, "Wake marker hidden in bedtime-only mode")
    }
    val state = when {
        latestMarker?.markerSource == WakeMarkerSources.GOING_TO_BED && resolvingWindowAvailable ->
            NowMarkerState.RESOLVED_BEDTIME
        latestMarker?.markerSource == WakeMarkerSources.GOING_TO_BED &&
            latestMarker.ageAt(nowEpochMs) <= ACTIVE_BEDTIME_MARKER_DURATION -> NowMarkerState.ACTIVE_BEDTIME
        latestMarker?.markerSource == WakeMarkerSources.GOING_TO_BED -> NowMarkerState.STALE_BEDTIME
        markerMode == NowMarkerMode.BEDTIME_AND_WAKING &&
            latestMarker?.markerSource == WakeMarkerSources.IM_AWAKE &&
            latestMarker.ageAt(nowEpochMs) <= ACTIVE_WAKE_MARKER_DURATION -> NowMarkerState.RECENT_WAKE
        markerMode == NowMarkerMode.BEDTIME_AND_WAKING &&
            latestMarker?.markerSource == WakeMarkerSources.IM_AWAKE -> NowMarkerState.STALE_WAKE
        else -> NowMarkerState.NONE
    }
    val (availability, label, detail) = when (state) {
        NowMarkerState.ACTIVE_BEDTIME -> Triple(
            NowDataAvailability.PRESENT,
            "Bedtime active",
            "Bedtime marker saved: ${latestMarker!!.timeLabel(zoneId)}"
        )
        NowMarkerState.RESOLVED_BEDTIME -> Triple(
            NowDataAvailability.PRESENT,
            "Bedtime resolved",
            "Bedtime marker is saved as sleep-latency evidence."
        )
        NowMarkerState.STALE_BEDTIME -> Triple(
            NowDataAvailability.STALE,
            "Bedtime stale",
            "Last bedtime marker: ${latestMarker!!.timeLabel(zoneId)}. Check in still works normally."
        )
        NowMarkerState.RECENT_WAKE -> Triple(
            NowDataAvailability.PRESENT,
            "Wake recorded",
            "Wake marker saved: ${latestMarker!!.timeLabel(zoneId)}"
        )
        NowMarkerState.STALE_WAKE -> Triple(
            NowDataAvailability.STALE,
            "Wake marker stale",
            "Last wake marker: ${latestMarker!!.timeLabel(zoneId)}"
        )
        NowMarkerState.NONE -> Triple(
            NowDataAvailability.MISSING,
            "No marker saved",
            if (markerMode == NowMarkerMode.BEDTIME) {
                "Bedtime marker is optional."
            } else {
                "Bedtime and wake markers are optional."
            }
        )
        NowMarkerState.NOT_APPLICABLE -> Triple(
            NowDataAvailability.NOT_APPLICABLE,
            "Check-in only",
            "Marker actions are hidden on the Now screen."
        )
    }
    return NowMarkerStatus(
        mode = markerMode,
        state = state,
        availability = availability,
        label = label,
        detail = detail,
        bedtime = bedtimePoint,
        waking = wakePoint
    )
}

private fun buildPrimaryActions(
    markerMode: NowMarkerMode,
    checkInIntent: NowCheckInIntent,
    selectedDeviceId: String?,
    isBusy: Boolean,
    catchUpPrompt: String?
): NowPrimaryActions {
    val syncUnavailable = when {
        isBusy -> "Sync already running"
        selectedDeviceId == null -> "No Loop selected"
        else -> null
    }
    val syncEnabled = syncUnavailable == null
    return NowPrimaryActions(
        intent = normalizeCheckInIntent(checkInIntent, markerMode),
        checkIn = NowActionAvailability(
            visible = true,
            enabled = syncEnabled,
            unavailableReason = syncUnavailable
        ),
        catchUp = NowActionAvailability(
            visible = catchUpPrompt != null,
            enabled = syncEnabled,
            unavailableReason = syncUnavailable
        ),
        bedtime = NowActionAvailability(
            visible = markerMode != NowMarkerMode.NO_MARKERS,
            enabled = syncEnabled,
            unavailableReason = syncUnavailable
        ),
        waking = NowActionAvailability(
            visible = markerMode == NowMarkerMode.BEDTIME_AND_WAKING,
            enabled = syncEnabled,
            unavailableReason = syncUnavailable
        )
    )
}

fun normalizeCheckInIntent(intent: NowCheckInIntent, markerMode: NowMarkerMode): NowCheckInIntent =
    when (intent) {
        NowCheckInIntent.INFO -> NowCheckInIntent.INFO
        NowCheckInIntent.BEDTIME ->
            if (markerMode == NowMarkerMode.NO_MARKERS) NowCheckInIntent.INFO else NowCheckInIntent.BEDTIME
        NowCheckInIntent.WAKING ->
            if (markerMode == NowMarkerMode.BEDTIME_AND_WAKING) NowCheckInIntent.WAKING else NowCheckInIntent.INFO
    }

private fun buildDeviceConnection(
    runtime: DeviceRuntimeState,
    selectedDeviceId: String?
): NowDataPoint =
    when {
        selectedDeviceId == null -> NowDataPoint("Device", NowDataAvailability.MISSING, "No Loop selected")
        !runtime.bluetoothPowered -> NowDataPoint("Device", NowDataAvailability.MISSING, "Bluetooth off")
        runtime.connectedDevice != null -> NowDataPoint("Device", NowDataAvailability.PRESENT, "Connected to ${runtime.connectedDevice.name}")
        runtime.connectionPhase == "connecting" -> NowDataPoint("Device", NowDataAvailability.PENDING, "Connecting")
        else -> NowDataPoint("Device", NowDataAvailability.PARTIAL, runtime.connectionPhase.replaceFirstChar { it.titlecase() })
    }

private fun buildTodayReadinessStatus(
    currentState: NowCurrentState,
    signalRobustness: NowSignalRobustness,
    markerStatus: NowMarkerStatus,
    freshness: NowFreshness,
    morningRead: MorningReadSnapshot?,
    hasFinalSleep: Boolean,
    hasPpi: Boolean,
    syncRunning: Boolean,
    noMainSleep: Boolean,
    analysisWindow: NowAnalysisWindowProvenance
): TodayReadinessStatus {
    val stage = when {
        markerStatus.state == NowMarkerState.ACTIVE_BEDTIME -> TodayReadinessStage.SLEEP_TIME
        syncRunning -> TodayReadinessStage.STARTING_SYNC
        hasFinalSleep || noMainSleep -> TodayReadinessStage.UPDATE_COMPLETE
        hasPpi -> TodayReadinessStage.INITIAL_PPI
        else -> TodayReadinessStage.NOT_STARTED
    }
    val dataQuality = if (noMainSleep) {
        TodayDataQualitySummary(
            state = TodayDataQualityState.PARTIAL,
            label = "Sleep not applicable",
            missingInputs = signalRobustness.missingInputs,
            supportingGaps = signalRobustness.supportingGaps
        )
    } else {
        todayDataQualitySummary(
            stage = stage,
            morningRead = morningRead,
            hasFinalSleep = hasFinalSleep,
            hasPpi = hasPpi
        )
    }
    return TodayReadinessStatus(
        stage = stage,
        title = currentState.label,
        sleepReport = signalRobustness.sleepReport.detail,
        ppiReceipt = signalRobustness.ppi.detail,
        message = currentState.message,
        hrvDetail = hrvDetailForState(morningRead, noMainSleep, analysisWindow),
        dataQuality = dataQuality,
        connectionPrompt = if (syncRunning) {
            "Keep the phone close to the Loop until PPI finishes."
        } else {
            null
        },
        heroPrompt = when {
            syncRunning -> "Stay near Loop"
            markerStatus.state == NowMarkerState.STALE_BEDTIME -> "Marker stale"
            else -> null
        },
        catchUpPrompt = freshness.catchUpPrompt,
        lastUsedLabel = freshness.lastUsed.detail.takeUnless { freshness.lastUsed.availability == NowDataAvailability.MISSING },
        lastLoopSyncLabel = freshness.loopSync.detail.takeUnless { freshness.loopSync.availability == NowDataAvailability.MISSING }
    )
}

private fun hrvDetailForState(
    morningRead: MorningReadSnapshot?,
    noMainSleep: Boolean,
    analysisWindow: NowAnalysisWindowProvenance
): String =
    when {
        noMainSleep -> "No main sleep is saved, so overnight HRV alignment is not applicable for this date."
        morningRead?.rawPpiGoodEpochCount != null -> "Raw PPI is aligned to ${analysisWindow.label}; use the signal as pacing context."
        analysisWindow.sourceType == NowAnalysisWindowSourceType.PENDING -> analysisWindow.reason
        analysisWindow.sourceType == NowAnalysisWindowSourceType.UNKNOWN ->
            "The active analysis source is unclassified; review provenance before relying on HRV detail."
        analysisWindow.sourceType == NowAnalysisWindowSourceType.MARKER_DERIVED ->
            "Marker-derived timing is selected; PPI detail will appear when enough aligned data is available."
        morningRead?.overnightAutonomicSource == "raw_ppi_pending_manual_sleep_window" ->
            "PPI is available, but Lodestone has no usable sleep window for alignment yet."
        morningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true ->
            "The provisional morning signal is using ${analysisWindow.label} while final sleep context is pending."
        else -> "PPI detail will appear after Lodestone syncs enough current Loop data."
    }

private fun buildActiveAnalysisWindow(
    today: String,
    activeMorningRead: MorningReadSnapshot?,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    noMainSleep: Boolean,
    markerStatus: NowMarkerStatus
): NowAnalysisWindowProvenance {
    val group = sleepEpisodeReviewState.activeDateGroup
    if (noMainSleep) {
        return NowAnalysisWindowProvenance(
            sourceDate = today,
            startEpochMs = null,
            endEpochMs = null,
            sourceType = NowAnalysisWindowSourceType.NO_MAIN_SLEEP,
            label = "No main sleep",
            timeRangeLabel = "No timed window",
            durationLabel = "Not applicable",
            confidenceLabel = "User confirmed",
            selectedByUser = true,
            reason = "You marked this day as having no main sleep window."
        )
    }

    val primaryItem = group
        ?.items
        ?.firstOrNull { it.isPrimaryForReadiness && it.startEpochMs != null && it.endEpochMs != null }
    if (primaryItem != null) {
        return NowAnalysisWindowProvenance(
            sourceDate = primaryItem.sourceDate,
            startEpochMs = primaryItem.startEpochMs,
            endEpochMs = primaryItem.endEpochMs,
            sourceType = NowAnalysisWindowSourceType.USER_SELECTED,
            label = primaryItem.kindLabel.windowLabelForUserSelection(),
            timeRangeLabel = primaryItem.timeRangeLabel,
            durationLabel = primaryItem.durationLabel,
            confidenceLabel = primaryItem.confidenceLabel,
            selectedByUser = true,
            reason = "User-selected current-signal window."
        )
    }

    if (activeMorningRead == null) {
        return NowAnalysisWindowProvenance(
            sourceDate = today,
            startEpochMs = null,
            endEpochMs = null,
            sourceType = NowAnalysisWindowSourceType.PENDING,
            label = "Analysis window pending",
            timeRangeLabel = "Timing not available yet",
            durationLabel = "Duration unknown",
            confidenceLabel = "Pending",
            selectedByUser = false,
            reason = if (markerStatus.state == NowMarkerState.ACTIVE_BEDTIME) {
                "Bedtime is marked; a later window has not been established yet."
            } else {
                "Lodestone needs sleep/rest evidence before choosing an analysis window."
            }
        )
    }

    val source = activeMorningRead.overnightAutonomicSource
    val sourceType = source.analysisWindowSourceType()
    val label = source.analysisWindowLabel(activeMorningRead.sleepDataReady)
    return NowAnalysisWindowProvenance(
        sourceDate = activeMorningRead.sourceDate ?: today,
        startEpochMs = null,
        endEpochMs = null,
        sourceType = sourceType,
        label = label,
        timeRangeLabel = "Timing not available yet",
        durationLabel = activeMorningRead.sleepDurationMinutes?.let(::durationMinutesLabel) ?: "Duration unknown",
        confidenceLabel = activeMorningRead.confidence.replace('_', ' ').replaceFirstChar { it.titlecase() },
        selectedByUser = sourceType == NowAnalysisWindowSourceType.USER_SELECTED,
        reason = source.analysisWindowReason(sourceType)
    )
}

private fun SyncRunEntity.isReadinessSync(): Boolean =
    notes?.contains("morning", ignoreCase = true) == true ||
        notes?.contains("check-in", ignoreCase = true) == true

private fun List<SyncRunEntity>.latestReadinessSync(): SyncRunEntity? =
    filter { it.isReadinessSync() }.maxByOrNull { it.startedAtEpochMs }

private fun MorningReadSnapshot?.hasPpiSignal(): Boolean =
    this?.rawPpiGoodEpochCount != null ||
        this?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true

private fun MorningReadSnapshot?.hasEstablishedSleepWindow(): Boolean =
    this?.sleepDataReady == true ||
        this?.overnightAutonomicSource in establishedSleepWindowSources

private val establishedSleepWindowSources = setOf(
    "ppi247_sleep_window",
    "raw_ppi_calibrated_window_pending_sleep_report",
    "raw_ppi_manual_window_pending_sleep_report",
    "raw_ppi_inferred_window_pending_sleep_report",
    "raw_ppi_calibrated_window_primary_with_sleep_report",
    "raw_ppi_manual_window_primary_with_sleep_report",
    "raw_ppi_inferred_window_primary_with_sleep_report",
    "sleep_context_only"
)

private fun TrafficLightStatus?.readinessLabel(): String =
    this?.name?.lowercase()?.replaceFirstChar { it.titlecase() } ?: "TBC"

private fun basisLabel(morningRead: MorningReadSnapshot?, noMainSleep: Boolean): String =
    when {
        noMainSleep -> "No main sleep decision"
        morningRead?.overnightAutonomicSource == "raw_ppi_calibrated_window_pending_sleep_report" ->
            "Provisional calibrated sleep window + PPI"
        morningRead?.overnightAutonomicSource == "raw_ppi_manual_window_pending_sleep_report" ->
            "Provisional manual sleep window + PPI"
        morningRead?.overnightAutonomicSource == "raw_ppi_inferred_window_pending_sleep_report" ->
            "Provisional PPI-inferred sleep window"
        morningRead?.overnightAutonomicSource == "raw_ppi_calibrated_window_primary_with_sleep_report" ->
            "Calibrated sleep window + PPI, Loop report as context"
        morningRead?.overnightAutonomicSource == "raw_ppi_manual_window_primary_with_sleep_report" ->
            "Manual sleep window + PPI, Loop report as context"
        morningRead?.overnightAutonomicSource == "raw_ppi_inferred_window_primary_with_sleep_report" ->
            "PPI-inferred sleep window, Loop report as context"
        morningRead?.sleepDataReady == true && morningRead.hasPpiSignal() ->
            "PPI aligned to final Loop sleep context"
        morningRead?.sleepDataReady == true ->
            "Loop sleep context only"
        morningRead?.isInterim == true ->
            "Provisional current signal"
        else -> "Waiting for morning data"
    }

private fun String.analysisWindowSourceType(): NowAnalysisWindowSourceType =
    when (this) {
        "user_confirmed_no_sleep" -> NowAnalysisWindowSourceType.NO_MAIN_SLEEP
        "edited_sleep_episode_primary",
        "mixed_sleep_episode_primary",
        "manual_sleep_episode_primary",
        "confirmed_sleep_episode_primary" -> NowAnalysisWindowSourceType.USER_SELECTED
        "raw_ppi_manual_window_pending_sleep_report",
        "raw_ppi_manual_window_primary_with_sleep_report" -> NowAnalysisWindowSourceType.MARKER_DERIVED
        "raw_ppi_calibrated_window_pending_sleep_report",
        "raw_ppi_calibrated_window_primary_with_sleep_report",
        "raw_ppi_inferred_window_pending_sleep_report",
        "raw_ppi_inferred_window_primary_with_sleep_report" -> NowAnalysisWindowSourceType.MODEL_ESTIMATE
        "ppi247_sleep_window",
        "nightly_recharge_summary",
        "sleep_context_only" -> NowAnalysisWindowSourceType.LOOP_REPORT
        else -> if (contains("pending", ignoreCase = true)) {
            NowAnalysisWindowSourceType.PENDING
        } else {
            NowAnalysisWindowSourceType.UNKNOWN
        }
    }

private fun String.analysisWindowLabel(sleepDataReady: Boolean): String =
    when (this) {
        "raw_ppi_calibrated_window_pending_sleep_report" -> "calibrated sleep window"
        "raw_ppi_manual_window_pending_sleep_report" -> "manual marker-derived sleep window"
        "raw_ppi_inferred_window_pending_sleep_report" -> "PPI-inferred sleep window"
        "raw_ppi_calibrated_window_primary_with_sleep_report" -> "calibrated primary window"
        "raw_ppi_manual_window_primary_with_sleep_report" -> "manual primary window"
        "raw_ppi_inferred_window_primary_with_sleep_report" -> "PPI-inferred primary window"
        "edited_sleep_episode_primary" -> "edited user window"
        "mixed_sleep_episode_primary",
        "manual_sleep_episode_primary",
        "confirmed_sleep_episode_primary" -> "confirmed user window"
        "ppi247_sleep_window" -> "Loop sleep report window"
        "nightly_recharge_summary" -> "Nightly Recharge sleep context"
        "sleep_context_only" -> "Loop sleep context"
        "raw_ppi_pending_manual_sleep_window" -> "sleep window pending"
        "raw_ppi_pending_sleep_window" -> "sleep window pending"
        else -> if (sleepDataReady) "resolved sleep window" else "analysis window pending"
    }

private fun String.analysisWindowReason(sourceType: NowAnalysisWindowSourceType): String =
    when (sourceType) {
        NowAnalysisWindowSourceType.USER_SELECTED -> "Using your selected window for the current signal."
        NowAnalysisWindowSourceType.MODEL_ESTIMATE -> "Using Lodestone's estimate. Review it if it looks wrong."
        NowAnalysisWindowSourceType.MARKER_DERIVED -> "Using marker-derived timing for this current read."
        NowAnalysisWindowSourceType.LOOP_REPORT -> "Using Loop sleep context for this current read."
        NowAnalysisWindowSourceType.NO_MAIN_SLEEP -> "No main sleep window applies to this date."
        NowAnalysisWindowSourceType.PENDING -> "Waiting for a usable sleep/rest window."
        NowAnalysisWindowSourceType.UNKNOWN -> "Using an unclassified analysis source; review provenance if this persists."
    }

private fun String.windowLabelForUserSelection(): String =
    when (this) {
        "Main sleep" -> "confirmed sleep window"
        "Nap" -> "selected nap window"
        "Rest" -> "selected rest window"
        else -> lowercase().replaceFirstChar { it.titlecase() }
    }

private fun durationMinutesLabel(minutes: Int): String {
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours > 0 && remainder > 0 -> "${hours}h ${remainder}m"
        hours > 0 -> "${hours}h"
        else -> "${remainder}m"
    }
}

private fun ppiReceiptLabelForState(morningRead: MorningReadSnapshot?): String = when {
    morningRead?.rawPpiGoodEpochCount != null -> {
        val coverage = morningRead.rawPpiCoverageHours?.let {
            String.format(java.util.Locale.UK, ", %.1fh aligned", it)
        }.orEmpty()
        "Received (${morningRead.rawPpiGoodEpochCount} usable windows$coverage)"
    }
    morningRead?.overnightAutonomicSource == "raw_ppi_pending_manual_sleep_window" -> "Received, missing sleep window"
    morningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true -> "Received, awaiting final sleep report"
    else -> "Not received yet"
}

private fun catchUpPrompt(today: String, morningRead: MorningReadSnapshot?): String? {
    val latestReadDate = morningRead?.sourceDate
        ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?: return null
    val todayDate = runCatching { java.time.LocalDate.parse(today) }.getOrNull() ?: return null
    val missingDays = java.time.temporal.ChronoUnit.DAYS.between(latestReadDate, todayDate)
    return if (missingDays > 0) {
        "Last morning signal was $missingDays day${if (missingDays == 1L) "" else "s"} ago."
    } else {
        null
    }
}

private fun Long?.toFreshnessPoint(
    label: String,
    nowEpochMs: Long,
    missingDetail: String
): NowDataPoint =
    if (this == null) {
        NowDataPoint(label, NowDataAvailability.MISSING, missingDetail)
    } else {
        val age = Duration.between(Instant.ofEpochMilli(this), Instant.ofEpochMilli(nowEpochMs))
            .coerceAtLeast(Duration.ZERO)
        val availability = if (age > STALE_FRESHNESS_DURATION) {
            NowDataAvailability.STALE
        } else {
            NowDataAvailability.PRESENT
        }
        NowDataPoint(label, availability, relativeAgeLabel(age))
    }

private fun WakeMarkerEntity?.toMarkerPoint(
    label: String,
    nowEpochMs: Long,
    zoneId: ZoneId,
    activeDuration: Duration
): NowDataPoint =
    if (this == null) {
        NowDataPoint(label, NowDataAvailability.MISSING, "Not saved")
    } else {
        val age = ageAt(nowEpochMs)
        NowDataPoint(
            label = label,
            availability = if (age > activeDuration) NowDataAvailability.STALE else NowDataAvailability.PRESENT,
            detail = "${timeLabel(zoneId)} (${relativeAgeLabel(age)})"
        )
    }

private fun WakeMarkerEntity.ageAt(nowEpochMs: Long): Duration =
    Duration.between(Instant.ofEpochMilli(markerEpochMs), Instant.ofEpochMilli(nowEpochMs))
        .coerceAtLeast(Duration.ZERO)

private fun WakeMarkerEntity.timeLabel(zoneId: ZoneId): String =
    Instant.ofEpochMilli(markerEpochMs)
        .atZone(zoneId)
        .toLocalTime()
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.UK))

private fun relativeAgeLabel(age: Duration): String {
    val ageMs = age.toMillis()
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    val week = 7 * day
    val month = 30 * day
    val year = 365 * day
    val (value, unit) = when {
        ageMs < hour -> ((ageMs / minute).coerceAtLeast(1L)) to "min"
        ageMs < day -> (ageMs / hour) to "h"
        ageMs < week -> (ageMs / day) to "d"
        ageMs < month -> (ageMs / week) to "wk"
        ageMs < year -> (ageMs / month) to "mo"
        else -> (ageMs / year) to "yr"
    }
    return "$value$unit ago"
}

private val STALE_FRESHNESS_DURATION: Duration = Duration.ofHours(36)
