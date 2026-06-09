package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.ACTIVE_BEDTIME_MARKER_DURATION
import com.daveharris.healthmonitor.ACTIVE_WAKE_MARKER_DURATION
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.HrvTrajectoryPoint
import com.daveharris.healthmonitor.data.MorningReadSource
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import com.daveharris.healthmonitor.data.WakeMarkerSources
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

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
    NEEDS_WINDOW,
    LOW_CONFIDENCE_READ,
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

data class NowFunctionalContext(
    val availability: NowDataAvailability,
    val status: TrafficLightStatus?,
    val label: String,
    val detail: String
)

private data class FunctionalRecoveryGate(
    val status: TrafficLightStatus?,
    val worstRecentStatus: TrafficLightStatus?,
    val recoveringFromLowerFunction: Boolean
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

data class NowAutonomicContext(
    val availability: NowDataAvailability,
    val label: String,
    val detail: String,
    val strainFlag: Boolean,
    val recoveryMomentum: Boolean
)

data class NowFreshness(
    val lastUsed: NowDataPoint,
    val loopSync: NowDataPoint,
    val journalEntry: NowDataPoint,
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
    val functionalContext: NowFunctionalContext,
    val signalRobustness: NowSignalRobustness,
    val stateStability: NowStateStability,
    val autonomicContext: NowAutonomicContext,
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
    val functionalContext = buildFunctionalContext(
        today = today,
        dailyCheckIns = dailyCheckIns
    )
    val stateStability = buildStateStability(relevantMorningRead, noMainSleep)
    val autonomicContext = buildAutonomicContext(relevantMorningRead, noMainSleep)
    val currentState = buildCurrentState(
        morningRead = relevantMorningRead,
        noMainSleep = noMainSleep,
        syncRunning = syncRunning,
        markerStatus = markerStatus,
        hasFinalSleep = hasFinalSleep,
        hasPpi = hasPpi,
        functionalContext = functionalContext
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
        functionalContext = functionalContext,
        signalRobustness = signalRobustness,
        stateStability = stateStability,
        autonomicContext = autonomicContext,
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
    hasPpi: Boolean,
    functionalContext: NowFunctionalContext
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
        hasFinalSleep -> readyCurrentState(morningRead, functionalContext, hasFinalSleep = true)
        hasPpi && !morningRead.hasEstablishedSleepWindow() -> needsWindowCurrentState(morningRead, functionalContext)
        hasPpi && !morningRead.hasSufficientReadyPpiCoverage() -> lowConfidenceCurrentState(morningRead, functionalContext)
        hasPpi -> readyCurrentState(morningRead, functionalContext, hasFinalSleep = false)
        else -> NowCurrentState(
            kind = NowCurrentStateKind.WAITING_FOR_DATA,
            availability = NowDataAvailability.MISSING,
            status = null,
            label = "Awaiting check-in",
            qualifier = "No current read",
            message = "Tap Check in to sync current data. Missing or stale markers do not block this."
        )
    }

private fun readyCurrentState(
    morningRead: MorningReadSnapshot?,
    functionalContext: NowFunctionalContext,
    hasFinalSleep: Boolean
): NowCurrentState {
    val planningStatus = planningStatus(morningRead?.status, functionalContext.status)
    val finalSleepMessage = if (morningRead.hasPpiSignal()) {
        "PPI is aligned to the resolved Loop sleep context. Use it as pacing context, not a verdict."
    } else {
        "Loop sleep context is attached. Use it as pacing context, not a verdict."
    }
    return NowCurrentState(
        kind = NowCurrentStateKind.READY,
        availability = NowDataAvailability.PRESENT,
        status = planningStatus,
        label = planningStatus.forecastLabel(),
        qualifier = if (functionalContext.disagreesWithAutonomic(morningRead?.status)) {
            "Mixed autonomic/function evidence"
        } else if (hasFinalSleep) {
            "Ready"
        } else {
            "Ready"
        },
        message = currentStateMessage(
            autonomicStatus = morningRead?.status,
            functionalContext = functionalContext,
            defaultMessage = if (hasFinalSleep) {
                finalSleepMessage
            } else {
                "PPI is aligned to a usable sleep/rest window. Loop sleep report is pending for comparison."
            }
        )
    )
}

private fun needsWindowCurrentState(
    morningRead: MorningReadSnapshot?,
    functionalContext: NowFunctionalContext
): NowCurrentState {
    val planningStatus = planningStatus(morningRead?.status, functionalContext.status)
    return NowCurrentState(
        kind = NowCurrentStateKind.NEEDS_WINDOW,
        availability = NowDataAvailability.PARTIAL,
        status = planningStatus,
        label = "Needs sleep/rest window",
        qualifier = if (functionalContext.disagreesWithAutonomic(morningRead?.status)) {
            "Mixed autonomic/function evidence"
        } else {
            "PPI received"
        },
        message = currentStateMessage(
            autonomicStatus = morningRead?.status,
            functionalContext = functionalContext,
            defaultMessage = "PPI is available, but Lodestone needs a usable sleep/rest window before the current signal is ready."
        )
    )
}

private fun lowConfidenceCurrentState(
    morningRead: MorningReadSnapshot?,
    functionalContext: NowFunctionalContext
): NowCurrentState {
    val planningStatus = planningStatus(morningRead?.status, functionalContext.status)
    return NowCurrentState(
        kind = NowCurrentStateKind.LOW_CONFIDENCE_READ,
        availability = NowDataAvailability.PARTIAL,
        status = planningStatus,
        label = planningStatus.forecastLabel(),
        qualifier = if (functionalContext.disagreesWithAutonomic(morningRead?.status)) {
            "Mixed autonomic/function evidence"
        } else {
            "Limited confidence"
        },
        message = currentStateMessage(
            autonomicStatus = morningRead?.status,
            functionalContext = functionalContext,
            defaultMessage = "PPI/window evidence is present, but coverage is thin. Treat this current signal as tentative context."
        )
    )
}

private fun currentStateMessage(
    autonomicStatus: TrafficLightStatus?,
    functionalContext: NowFunctionalContext,
    defaultMessage: String
): String =
    if (functionalContext.disagreesWithAutonomic(autonomicStatus)) {
        "Autonomic signal looks steady, but recent outcomes suggest a lower-function spell. Use this as pacing context, not proof you are recovered."
    } else {
        defaultMessage
    }

private fun buildFunctionalContext(
    today: String,
    dailyCheckIns: List<DailyCheckInEntity>
): NowFunctionalContext {
    val todayDate = runCatching { LocalDate.parse(today) }.getOrNull()
        ?: return noFunctionalContext("Cannot compare Journal rows with this date.")
    val datedRows = dailyCheckIns.mapNotNull { row ->
        runCatching { LocalDate.parse(row.sourceDate) }.getOrNull()?.let { date -> date to row }
    }
        .filter { (date, _) -> !date.isAfter(todayDate) }
        .sortedByDescending { (date, _) -> date }
    if (datedRows.isEmpty()) {
        return noFunctionalContext("No Journal outcome has been saved yet.")
    }

    val recentRows = datedRows.filter { (date, _) -> ChronoUnit.DAYS.between(date, todayDate) <= 5 }
    if (recentRows.isEmpty()) {
        val latestDate = datedRows.first().first
        val ageDays = ChronoUnit.DAYS.between(latestDate, todayDate)
        return NowFunctionalContext(
            availability = NowDataAvailability.STALE,
            status = null,
            label = "No recent Journal context",
            detail = "Latest Journal outcome is ${ageDays}d old."
        )
    }

    val recoveryGate = recentRows.recoveryGate()
    val status = recoveryGate.status
    val latest = recentRows.first().second
    val lowerFunction = status != null && status.severityRank() >= TrafficLightStatus.UNSTEADY.severityRank()
    val anchors = recentRows.flatMap { (_, row) -> row.functionalAnchors() }.distinct()
    val rowCount = recentRows.size
    val detailParts = buildList {
        add("$rowCount Journal outcome${if (rowCount == 1) "" else "s"} in the last 5 days")
        latest.functionalStatus()?.let { add("latest ${labelForStatus(it.name)} on ${latest.sourceDate}") }
        recoveryGate.worstRecentStatus
            ?.takeIf { recoveryGate.recoveringFromLowerFunction }
            ?.let { add("recovering after recent ${labelForStatus(it.name)}") }
        if (anchors.isNotEmpty()) add(anchors.joinToString(", "))
        if (recentRows.any { (_, row) -> row.dayShapeCaptured != true }) add("some day-shape unknown")
    }
    return NowFunctionalContext(
        availability = NowDataAvailability.PRESENT,
        status = status,
        label = if (status == null) {
            "Recent function unknown"
        } else {
            "Recent function: ${labelForStatus(status.name)}"
        },
        detail = if (lowerFunction) {
            "Recent outcomes suggest a lower-function spell: ${detailParts.joinToString("; ")}."
        } else if (recoveryGate.recoveringFromLowerFunction) {
            "Recent outcomes suggest cautious recovery: ${detailParts.joinToString("; ")}."
        } else {
            "Recent functional context: ${detailParts.joinToString("; ")}."
        }
    )
}

private fun noFunctionalContext(detail: String): NowFunctionalContext =
    NowFunctionalContext(
        availability = NowDataAvailability.MISSING,
        status = null,
        label = "Functional context unknown",
        detail = detail
    )

private fun DailyCheckInEntity.functionalStatus(): TrafficLightStatus? {
    val outcomeStatus = runCatching { TrafficLightStatus.valueOf(eveningOutcome) }.getOrNull()
    val anchorStatus = if (dayShapeCaptured == true && (
        mostlyHorizontal == true ||
            pemPaybackToday == true ||
            paybackPeakToday == true ||
            muscleWeaknessToday
        )
    ) {
        TrafficLightStatus.UNSTEADY
    } else {
        null
    }
    return planningStatus(outcomeStatus, anchorStatus)
}

private fun DailyCheckInEntity.functionalAnchors(): List<String> {
    if (dayShapeCaptured != true) return emptyList()
    return buildList {
        if (mostlyHorizontal == true) add("mostly horizontal")
        if (pemPaybackToday == true) add("PEM/payback")
        if (paybackPeakToday == true) add("payback peak")
        if (muscleWeaknessToday) add("muscle weakness")
    }
}

private fun List<Pair<LocalDate, DailyCheckInEntity>>.recoveryGate(): FunctionalRecoveryGate {
    val statuses = mapNotNull { (_, row) -> row.functionalStatus() }
    val worstRecent = statuses.maxByOrNull { it.severityRank() }
    val latest = firstOrNull()?.second?.functionalStatus()
    if (worstRecent == null) {
        return FunctionalRecoveryGate(
            status = null,
            worstRecentStatus = null,
            recoveringFromLowerFunction = false
        )
    }
    if (worstRecent.severityRank() < TrafficLightStatus.UNSTEADY.severityRank()) {
        return FunctionalRecoveryGate(
            status = latest ?: worstRecent,
            worstRecentStatus = worstRecent,
            recoveringFromLowerFunction = false
        )
    }

    val consecutiveStableRows = takeWhile { (_, row) ->
        row.functionalStatus()?.let { it.severityRank() <= TrafficLightStatus.OK.severityRank() } == true
    }.size
    if (consecutiveStableRows == 0) {
        return FunctionalRecoveryGate(
            status = worstRecent,
            worstRecentStatus = worstRecent,
            recoveringFromLowerFunction = false
        )
    }
    val recoveredStatus = if (worstRecent == TrafficLightStatus.CRASH) {
        TrafficLightStatus.UNSTEADY
    } else {
        TrafficLightStatus.OK
    }
    return FunctionalRecoveryGate(
        status = recoveredStatus,
        worstRecentStatus = worstRecent,
        recoveringFromLowerFunction = true
    )
}

private fun NowFunctionalContext.disagreesWithAutonomic(autonomicStatus: TrafficLightStatus?): Boolean =
    autonomicStatus != null &&
        status != null &&
        autonomicStatus.severityRank() <= TrafficLightStatus.OK.severityRank() &&
        status.severityRank() >= TrafficLightStatus.UNSTEADY.severityRank()

private fun buildSignalRobustness(
    morningRead: MorningReadSnapshot?,
    hasFinalSleep: Boolean,
    hasPpi: Boolean,
    noMainSleep: Boolean,
    syncRunning: Boolean
): NowSignalRobustness {
    val sleepReport = when {
        noMainSleep -> NowDataPoint("Sleep report", NowDataAvailability.NOT_APPLICABLE, "No main sleep saved")
        hasFinalSleep -> NowDataPoint("Sleep report", NowDataAvailability.PRESENT, "Loop sleep report attached")
        syncRunning -> NowDataPoint("Sleep report", NowDataAvailability.PENDING, "Checking Loop")
        hasPpi -> NowDataPoint("Sleep report", NowDataAvailability.PARTIAL, "Loop sleep report pending for comparison")
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

private fun buildAutonomicContext(
    morningRead: MorningReadSnapshot?,
    noMainSleep: Boolean
): NowAutonomicContext {
    if (noMainSleep) {
        return NowAutonomicContext(
            availability = NowDataAvailability.NOT_APPLICABLE,
            label = "Not applicable",
            detail = "No main sleep is saved for this date, so Lodestone is not anchoring an autonomic trajectory.",
            strainFlag = false,
            recoveryMomentum = false
        )
    }
    if (morningRead == null) {
        return NowAutonomicContext(
            availability = NowDataAvailability.MISSING,
            label = "Autonomic context unavailable",
            detail = "Sync current PPI before reading autonomic strain or recovery momentum.",
            strainFlag = false,
            recoveryMomentum = false
        )
    }

    val points = morningRead.hrvTrajectory
        .filter { it.epochQuality.equals("good", ignoreCase = true) }
        .sortedBy { it.epochStartEpochMs }
    if (points.size < 12) {
        return NowAutonomicContext(
            availability = if (morningRead.nightlyRmssd != null) NowDataAvailability.PARTIAL else NowDataAvailability.MISSING,
            label = if (morningRead.nightlyRmssd != null) "Autonomic average only" else "Autonomic context unavailable",
            detail = morningRead.nightlyRmssd?.let {
                "RMSSD average is ${it.toInt()} ms, but there are not enough PPI windows to describe strain or momentum."
            } ?: "Need at least 12 clean PPI windows before describing strain or momentum.",
            strainFlag = false,
            recoveryMomentum = false
        )
    }

    val summary = autonomicTrajectorySummary(points)
    val p25 = summary.p25RmssdMs
    val average = summary.averageRmssdMs
    val strain = p25 <= 60.0 || average <= 60.0
    val watch = strain || p25 <= 64.0 || average <= 70.0
    val rising = summary.shapeLabel == "Rising" || summary.shapeLabel == "Dip then recovery"
    val falling = summary.shapeLabel == "Falling"
    val label = when {
        strain && rising -> "Strained, recovering"
        strain -> "Autonomic strain"
        watch && rising -> "Recovery momentum"
        watch -> "Autonomic watch"
        falling -> "Autonomic drift down"
        rising -> "Recovery momentum"
        else -> "Autonomic steady"
    }
    val interpretation = when {
        strain && rising -> "Low-tail HRV is still strained, but the curve rose later in the window."
        strain -> "Low-tail HRV is low enough to treat the autonomic signal cautiously."
        watch && rising -> "The HRV curve rose later in the window, but this is context rather than an upgrade."
        watch -> "Low-tail HRV is a little subdued, so use the current signal cautiously."
        falling -> "The HRV curve drifted down later in the window."
        rising -> "The HRV curve rose later in the window, suggesting possible recovery momentum."
        else -> "The HRV distribution and curve do not add an obvious strain flag."
    }
    return NowAutonomicContext(
        availability = NowDataAvailability.PRESENT,
        label = label,
        detail = "P25 RMSSD ${p25.toInt()} ms, average ${average.toInt()} ms, early-late ${formatSignedMs(summary.earlyLateDeltaMs)}. $interpretation",
        strainFlag = strain,
        recoveryMomentum = rising
    )
}

private data class AutonomicTrajectorySummary(
    val averageRmssdMs: Double,
    val p25RmssdMs: Double,
    val earlyLateDeltaMs: Double,
    val shapeLabel: String
)

private fun autonomicTrajectorySummary(points: List<HrvTrajectoryPoint>): AutonomicTrajectorySummary {
    val values = points.map { it.rmssdMs }
    val smooth = rollingMedianRmssd(values, windowSize = 5)
    val thirdSize = (smooth.size / 3).coerceAtLeast(1)
    val early = smooth.take(thirdSize).average()
    val middleStart = ((smooth.size - thirdSize) / 2).coerceAtLeast(0)
    val middle = smooth.drop(middleStart).take(thirdSize).average()
    val late = smooth.takeLast(thirdSize).average()
    val delta = late - early
    val shape = autonomicShapeLabel(
        early = early,
        middle = middle,
        late = late,
        linearDelta = delta
    )
    return AutonomicTrajectorySummary(
        averageRmssdMs = values.average(),
        p25RmssdMs = values.quantile(0.25),
        earlyLateDeltaMs = delta,
        shapeLabel = shape
    )
}

private fun rollingMedianRmssd(values: List<Double>, windowSize: Int): List<Double> {
    if (values.size <= 2) return values
    val halfWindow = windowSize / 2
    return values.mapIndexed { index, value ->
        val start = (index - halfWindow).coerceAtLeast(0)
        val endExclusive = (index + halfWindow + 1).coerceAtMost(values.size)
        values.subList(start, endExclusive).medianOrNull() ?: value
    }
}

private fun autonomicShapeLabel(
    early: Double,
    middle: Double,
    late: Double,
    linearDelta: Double
): String {
    val minEdge = minOf(early, late)
    val maxEdge = maxOf(early, late)
    val threshold = 8.0
    return when {
        middle <= minEdge - threshold -> "Dip then recovery"
        middle >= maxEdge + threshold -> "Mid-window peak"
        linearDelta >= threshold -> "Rising"
        linearDelta <= -threshold -> "Falling"
        else -> "Mostly flat / mixed"
    }
}

private fun List<Double>.quantile(percentile: Double): Double {
    val sorted = sorted()
    if (sorted.isEmpty()) return 0.0
    val index = (sorted.size - 1) * percentile.coerceIn(0.0, 1.0)
    val lower = kotlin.math.floor(index).toInt()
    val upper = kotlin.math.ceil(index).toInt()
    if (lower == upper) return sorted[lower]
    val upperWeight = index - lower
    return sorted[lower] * (1.0 - upperWeight) + sorted[upper] * upperWeight
}

private fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun formatSignedMs(value: Double): String {
    val sign = if (value >= 0) "+" else "-"
    return "$sign${kotlin.math.abs(value).toInt()} ms"
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
        journalEntry = latestReviewEpochMs.toFreshnessPoint(
            label = "Journal",
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
        hasFinalSleep || noMainSleep || currentState.kind == NowCurrentStateKind.READY -> TodayReadinessStage.UPDATE_COMPLETE
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
            hasPpi = hasPpi,
            hasUsableWindow = noMainSleep || morningRead.hasEstablishedSleepWindow()
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
        morningRead.hasPpiSignal() && !morningRead.hasEstablishedSleepWindow() ->
            "PPI is available, but Lodestone has no usable sleep/rest window for alignment yet."
        morningRead?.rawPpiGoodEpochCount != null -> {
            val reportContext = if (morningRead.sleepDataReady) {
                ""
            } else {
                " Loop sleep report is pending for comparison."
            }
            "Raw PPI is aligned to ${analysisWindow.label}; use the signal as pacing context.$reportContext"
        }
        analysisWindow.sourceType == NowAnalysisWindowSourceType.PENDING -> analysisWindow.reason
        analysisWindow.sourceType == NowAnalysisWindowSourceType.UNKNOWN ->
            "The active analysis source is unclassified; review provenance before relying on HRV detail."
        analysisWindow.sourceType == NowAnalysisWindowSourceType.MARKER_DERIVED ->
            "Marker-derived timing is selected; PPI detail will appear when enough aligned data is available."
        morningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true ->
            "The current signal is using ${analysisWindow.label} while Loop sleep report is pending for comparison."
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

private fun TrafficLightStatus?.forecastLabel(): String =
    when (this) {
        TrafficLightStatus.GOOD -> "Likely Good"
        TrafficLightStatus.OK -> "Likely OK"
        TrafficLightStatus.UNSTEADY -> "Likely Unsteady"
        TrafficLightStatus.CRASH -> "Likely Crash"
        null -> "Forecast TBC"
    }

private fun planningStatus(
    autonomicStatus: TrafficLightStatus?,
    functionalStatus: TrafficLightStatus?
): TrafficLightStatus? =
    listOfNotNull(autonomicStatus, functionalStatus).maxByOrNull { it.severityRank() }

private fun TrafficLightStatus.severityRank(): Int =
    when (this) {
        TrafficLightStatus.GOOD -> 0
        TrafficLightStatus.OK -> 1
        TrafficLightStatus.UNSTEADY -> 2
        TrafficLightStatus.CRASH -> 3
    }

private fun basisLabel(morningRead: MorningReadSnapshot?, noMainSleep: Boolean): String =
    when {
        noMainSleep -> "No main sleep decision"
        morningRead?.morningReadSource() == MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT ->
            "Calibrated sleep window + PPI, Loop report pending"
        morningRead?.morningReadSource() == MorningReadSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT ->
            "Manual sleep window + PPI, Loop report pending"
        morningRead?.morningReadSource() == MorningReadSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT ->
            "PPI-inferred sleep window, Loop report pending"
        morningRead?.morningReadSource() == MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "Calibrated sleep window + PPI, Loop report as context"
        morningRead?.morningReadSource() == MorningReadSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "Manual sleep window + PPI, Loop report as context"
        morningRead?.morningReadSource() == MorningReadSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "PPI-inferred sleep window, Loop report as context"
        morningRead?.sleepDataReady == true && morningRead.hasPpiSignal() ->
            "PPI aligned to Loop sleep report"
        morningRead?.sleepDataReady == true ->
            "Loop sleep context only"
        morningRead?.isInterim == true ->
            "Current signal, Loop report pending"
        else -> "Waiting for morning data"
    }

private fun MorningReadSnapshot.morningReadSource(): MorningReadSource? =
    MorningReadSource.fromKey(overnightAutonomicSource)

private fun String.analysisWindowSourceType(): NowAnalysisWindowSourceType {
    val source = MorningReadSource.fromKey(this)
    return when (source) {
        MorningReadSource.USER_CONFIRMED_NO_SLEEP -> NowAnalysisWindowSourceType.NO_MAIN_SLEEP
        MorningReadSource.EDITED_SLEEP_EPISODE_PRIMARY,
        MorningReadSource.MIXED_SLEEP_EPISODE_PRIMARY,
        MorningReadSource.MANUAL_SLEEP_EPISODE_PRIMARY,
        MorningReadSource.CONFIRMED_SLEEP_EPISODE_PRIMARY -> NowAnalysisWindowSourceType.USER_SELECTED
        MorningReadSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT,
        MorningReadSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> NowAnalysisWindowSourceType.MARKER_DERIVED
        MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT,
        MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT,
        MorningReadSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT,
        MorningReadSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> NowAnalysisWindowSourceType.MODEL_ESTIMATE
        MorningReadSource.PPI247_SLEEP_WINDOW,
        MorningReadSource.NIGHTLY_RECHARGE_SUMMARY,
        MorningReadSource.SLEEP_CONTEXT_ONLY -> NowAnalysisWindowSourceType.LOOP_REPORT
        MorningReadSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW,
        MorningReadSource.RAW_PPI_PENDING_SLEEP_WINDOW,
        MorningReadSource.AWAITING_SLEEP_DATA -> NowAnalysisWindowSourceType.PENDING
        null -> if (contains("pending", ignoreCase = true)) {
            NowAnalysisWindowSourceType.PENDING
        } else {
            NowAnalysisWindowSourceType.UNKNOWN
        }
    }
}

private fun String.analysisWindowLabel(sleepDataReady: Boolean): String {
    val source = MorningReadSource.fromKey(this)
    return when (source) {
        MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT -> "calibrated sleep window"
        MorningReadSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT -> "manual marker-derived sleep window"
        MorningReadSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT -> "PPI-inferred sleep window"
        MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "calibrated primary window"
        MorningReadSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "manual primary window"
        MorningReadSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "PPI-inferred primary window"
        MorningReadSource.EDITED_SLEEP_EPISODE_PRIMARY -> "edited user window"
        MorningReadSource.MIXED_SLEEP_EPISODE_PRIMARY,
        MorningReadSource.MANUAL_SLEEP_EPISODE_PRIMARY,
        MorningReadSource.CONFIRMED_SLEEP_EPISODE_PRIMARY -> "confirmed user window"
        MorningReadSource.PPI247_SLEEP_WINDOW -> "Loop sleep report window"
        MorningReadSource.NIGHTLY_RECHARGE_SUMMARY -> "Nightly Recharge sleep context"
        MorningReadSource.SLEEP_CONTEXT_ONLY -> "Loop sleep context"
        MorningReadSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW,
        MorningReadSource.RAW_PPI_PENDING_SLEEP_WINDOW -> "sleep window pending"
        else -> if (sleepDataReady) "resolved sleep window" else "analysis window pending"
    }
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
    morningRead?.morningReadSource() == MorningReadSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW -> "Received, missing sleep window"
    morningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true -> "Received, Loop report pending"
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
