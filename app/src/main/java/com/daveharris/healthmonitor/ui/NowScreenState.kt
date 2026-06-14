package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.ACTIVE_BEDTIME_MARKER_DURATION
import com.daveharris.healthmonitor.ACTIVE_WAKE_MARKER_DURATION
import com.daveharris.healthmonitor.data.CautionLevel
import com.daveharris.healthmonitor.data.ConfidenceLevel
import com.daveharris.healthmonitor.data.CurrentStateRead
import com.daveharris.healthmonitor.data.ForecastBasis
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.HrvTrajectoryPoint
import com.daveharris.healthmonitor.data.AnalysisWindowSource
import com.daveharris.healthmonitor.data.AnalysisWindowEvidence
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import com.daveharris.healthmonitor.data.WakeMarkerSources
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
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

enum class NowJournalFocusMode {
    AUTO_FROM_WAKE,
    FIXED_TIME
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
    val message: String,
    // Signal-acquisition context, folded in from the former TodayReadinessStatus.
    // buildCurrentState produces the forecast core with these at neutral defaults;
    // withSignalContext always overwrites them before the state reaches the UI.
    val stage: NowSignalStage = NowSignalStage.NOT_STARTED,
    val hrvDetail: String = "",
    val signalConfidence: SignalConfidenceSummary = SignalConfidenceSummary(
        state = SignalConfidenceState.WAITING,
        label = "Waiting",
        missingInputs = emptyList(),
        supportingGaps = emptyList()
    ),
    val connectionPrompt: String? = null,
    val heroPrompt: String? = null
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

data class NowJournalFocus(
    val mode: NowJournalFocusMode,
    val shouldFocusJournal: Boolean,
    val gateEpochMs: Long,
    val label: String,
    val detail: String
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
    val currentStateRead: CurrentStateRead?,
    val activeMorningRead: AnalysisWindowEvidence?,
    val activeAnalysisWindow: NowAnalysisWindowProvenance,
    val recentRest: NowDataPoint,
    val currentState: NowCurrentState,
    val functionalContext: NowFunctionalContext,
    val signalRobustness: NowSignalRobustness,
    val stateStability: NowStateStability,
    val autonomicContext: NowAutonomicContext,
    val freshness: NowFreshness,
    val markerStatus: NowMarkerStatus,
    val primaryActions: NowPrimaryActions,
    val deviceConnection: NowDataPoint,
    val journalFocus: NowJournalFocus
)

private const val RECENT_REST_WINDOW_HOURS = 18L

fun buildNowScreenState(
    today: String,
    morningRead: AnalysisWindowEvidence?,
    currentStateRead: CurrentStateRead? = null,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    runtime: DeviceRuntimeState,
    selectedDeviceId: String?,
    isBusy: Boolean,
    markerMode: NowMarkerMode = NowMarkerMode.BEDTIME_AND_WAKING,
    checkInIntent: NowCheckInIntent = NowCheckInIntent.INFO,
    journalFocusMode: NowJournalFocusMode = NowJournalFocusMode.AUTO_FROM_WAKE,
    journalFocusFixedTimeMinutes: Int = 18 * 60,
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
    val recentRest = buildRecentRestSummary(
        activeAnalysisWindow = activeAnalysisWindow,
        sleepEpisodeReviewState = sleepEpisodeReviewState,
        noMainSleep = noMainSleep,
        nowEpochMs = nowEpochMs
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
    // Gate by today like morningRead, so a stale read from another day never
    // surfaces a forecast/caution labelled as today's.
    val relevantCurrentState = currentStateRead?.takeIf { it.sourceDate == today }
    val stateStability = buildStateStability(relevantCurrentState)
    val autonomicContext = buildAutonomicContext(relevantMorningRead, noMainSleep)
    val currentState = buildCurrentState(
        currentStateRead = relevantCurrentState,
        syncRunning = syncRunning
    ).withSignalContext(
        signalRobustness = signalRobustness,
        markerStatus = markerStatus,
        morningRead = relevantMorningRead,
        hasFinalSleep = hasFinalSleep,
        hasPpi = hasPpi,
        syncRunning = syncRunning,
        noMainSleep = noMainSleep,
        analysisWindow = activeAnalysisWindow
    )
    val primaryActions = buildPrimaryActions(
        markerMode = markerMode,
        checkInIntent = checkInIntent,
        selectedDeviceId = selectedDeviceId,
        isBusy = isBusy,
        catchUpPrompt = catchUpPrompt
    )
    val deviceConnection = buildDeviceConnection(runtime, selectedDeviceId)
    val journalFocus = buildJournalFocus(
        today = today,
        analysisWindow = activeAnalysisWindow,
        mode = journalFocusMode,
        fixedTimeMinutes = journalFocusFixedTimeMinutes,
        nowEpochMs = nowEpochMs,
        zoneId = zoneId
    )
    return NowScreenState(
        today = today,
        currentStateRead = relevantCurrentState,
        activeMorningRead = activeMorningRead,
        activeAnalysisWindow = activeAnalysisWindow,
        recentRest = recentRest,
        currentState = currentState,
        functionalContext = functionalContext,
        signalRobustness = signalRobustness,
        stateStability = stateStability,
        autonomicContext = autonomicContext,
        freshness = freshness,
        markerStatus = markerStatus,
        primaryActions = primaryActions,
        deviceConnection = deviceConnection,
        journalFocus = journalFocus
    )
}

private data class RestSummaryInterval(
    val startEpochMs: Long,
    val endEpochMs: Long
)

private fun buildRecentRestSummary(
    activeAnalysisWindow: NowAnalysisWindowProvenance,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    noMainSleep: Boolean,
    nowEpochMs: Long
): NowDataPoint {
    if (noMainSleep || activeAnalysisWindow.sourceType == NowAnalysisWindowSourceType.NO_MAIN_SLEEP) {
        return NowDataPoint(
            label = "Recent rest",
            availability = NowDataAvailability.NOT_APPLICABLE,
            detail = "No saved sleep/rest"
        )
    }
    val windowStart = nowEpochMs - Duration.ofHours(RECENT_REST_WINDOW_HOURS).toMillis()
    val intervals = buildList {
        activeAnalysisWindow.toRestSummaryInterval()?.let { add(it) }
        sleepEpisodeReviewState.activeDateGroup
            ?.items
            .orEmpty()
            .filter { item -> !item.isNoSleep && !item.isCandidate }
            .mapNotNull { item ->
                val start = item.startEpochMs
                val end = item.endEpochMs
                if (start != null && end != null && end > start) {
                    RestSummaryInterval(start, end)
                } else {
                    null
                }
            }
            .forEach { add(it) }
    }
        .distinct()

    val totalMs = intervals
        .mapNotNull { interval ->
            val clippedStart = interval.startEpochMs.coerceAtLeast(windowStart)
            val clippedEnd = interval.endEpochMs.coerceAtMost(nowEpochMs)
            if (clippedEnd > clippedStart) {
                RestSummaryInterval(clippedStart, clippedEnd)
            } else {
                null
            }
        }
        .mergeRestSummaryIntervals()
        .sumOf { interval -> interval.endEpochMs - interval.startEpochMs }
    val minutes = (totalMs / 60_000L).toInt()
    if (minutes > 0) {
        return NowDataPoint(
            label = "Recent rest",
            availability = NowDataAvailability.PRESENT,
            detail = "${durationMinutesLabel(minutes)} (last ${RECENT_REST_WINDOW_HOURS}h)"
        )
    }
    if (totalMs > 0L) {
        return NowDataPoint(
            label = "Recent rest",
            availability = NowDataAvailability.PRESENT,
            detail = "Under 1m (last ${RECENT_REST_WINDOW_HOURS}h)"
        )
    }
    val fallbackDuration = activeAnalysisWindow.durationLabel
        .takeUnless { it == "Duration unknown" || it == "Not applicable" }
    return if (fallbackDuration != null) {
        NowDataPoint(
            label = "Recent rest",
            availability = NowDataAvailability.PARTIAL,
            detail = "$fallbackDuration (last ${RECENT_REST_WINDOW_HOURS}h)"
        )
    } else {
        NowDataPoint(
            label = "Recent rest",
            availability = NowDataAvailability.MISSING,
            detail = "TBC"
        )
    }
}

private fun NowAnalysisWindowProvenance.toRestSummaryInterval(): RestSummaryInterval? {
    val start = startEpochMs
    val end = endEpochMs
    return if (start != null && end != null && end > start) {
        RestSummaryInterval(start, end)
    } else {
        null
    }
}

private fun List<RestSummaryInterval>.mergeRestSummaryIntervals(): List<RestSummaryInterval> {
    if (isEmpty()) return emptyList()
    val sorted = sortedBy { it.startEpochMs }
    val merged = mutableListOf<RestSummaryInterval>()
    sorted.forEach { interval ->
        val previous = merged.lastOrNull()
        if (previous == null || interval.startEpochMs > previous.endEpochMs) {
            merged += interval
        } else if (interval.endEpochMs > previous.endEpochMs) {
            merged[merged.lastIndex] = previous.copy(endEpochMs = interval.endEpochMs)
        }
    }
    return merged
}

/**
 * Forecast-first: the Now read reflects the model ([CurrentStateRead]) directly. The
 * forecast is lived-function persistence and does NOT depend on sleep/PPI, so a
 * no-main-sleep day, a bedtime marker, or a running sync no longer suppress it — those
 * are window/freshness notes elsewhere. Caution sits beside the forecast (ELEVATED
 * leads); a STALE_CONTEXT basis is shown but caveated; no usable outcome -> awaiting.
 * Anti-Visible is encoded in the model, so functional context is supporting detail
 * here, never a forecast modifier.
 */
private fun buildCurrentState(
    currentStateRead: CurrentStateRead?,
    syncRunning: Boolean
): NowCurrentState {
    val forecast = currentStateRead?.forecastLevel
    val basis = currentStateRead?.forecastBasis ?: ForecastBasis.NONE
    val elevated = currentStateRead?.caution?.level == CautionLevel.ELEVATED

    if (forecast == null || basis == ForecastBasis.NONE) {
        return NowCurrentState(
            kind = NowCurrentStateKind.WAITING_FOR_DATA,
            availability = if (syncRunning) NowDataAvailability.PENDING else NowDataAvailability.MISSING,
            status = null,
            label = "Awaiting check-in",
            qualifier = "No current read",
            message = "Log a check-in to build today's forecast. Missing or stale markers do not block this."
        )
    }

    val stale = basis == ForecastBasis.STALE_CONTEXT
    return NowCurrentState(
        kind = if (stale) NowCurrentStateKind.LOW_CONFIDENCE_READ else NowCurrentStateKind.READY,
        availability = if (stale) NowDataAvailability.PARTIAL else NowDataAvailability.PRESENT,
        status = forecast,
        label = forecast.forecastLabel(),
        qualifier = when {
            elevated -> "Caution"
            stale -> "From recent context"
            else -> "Ready"
        },
        message = when {
            elevated -> currentStateRead.caution.reasons.firstOrNull()
                ?: "Recent load or instability is up — easing may be wise; payback can land a couple of days out."
            stale -> "Carried from your most recent logged day — no fresh outcome yet, so hold it lightly."
            else -> "Carried from recent lived function. Use it as pacing guidance, not a verdict."
        }
    )
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

private fun buildSignalRobustness(
    morningRead: AnalysisWindowEvidence?,
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

/**
 * Model-v1 replacement for the deleted fake `buildStateStability` (which
 * mislabelled PPI data coverage as "stability"). Stability is now the honest
 * pairing the naming contract calls for: the caution signal (brittleness /
 * push-risk) sitting BESIDE the forecast, qualified by confidence (data support).
 * See docs/lodestone-naming-contract.md §3 and docs/lodestone-model-v1.md §2-3.
 */
private fun buildStateStability(currentState: CurrentStateRead?): NowStateStability {
    if (currentState == null) {
        return NowStateStability(
            availability = NowDataAvailability.MISSING,
            label = "TBC",
            detail = "Not enough recent data to describe stability yet."
        )
    }
    val caution = currentState.caution
    if (caution.level == CautionLevel.ELEVATED) {
        return NowStateStability(
            availability = NowDataAvailability.PRESENT,
            label = "Caution",
            detail = caution.reasons.firstOrNull()
                ?: "Recent load or instability is up — easing may be wise."
        )
    }
    val confidenceNote = when (currentState.confidence) {
        ConfidenceLevel.LOW -> "Limited supporting data — read this lightly."
        ConfidenceLevel.MEDIUM -> "Reasonably supported by recent data."
        ConfidenceLevel.HIGH -> "Well supported by recent data."
    }
    return NowStateStability(
        availability = NowDataAvailability.PRESENT,
        label = "No caution flag",
        detail = confidenceNote
    )
}

private fun buildAutonomicContext(
    morningRead: AnalysisWindowEvidence?,
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
            enabled = !isBusy,
            unavailableReason = if (isBusy) "Action already running" else null
        ),
        waking = NowActionAvailability(
            visible = markerMode == NowMarkerMode.BEDTIME_AND_WAKING,
            enabled = !isBusy,
            unavailableReason = if (isBusy) "Action already running" else null
        )
    )
}

private fun buildJournalFocus(
    today: String,
    analysisWindow: NowAnalysisWindowProvenance,
    mode: NowJournalFocusMode,
    fixedTimeMinutes: Int,
    nowEpochMs: Long,
    zoneId: ZoneId
): NowJournalFocus {
    val todayDate = runCatching { LocalDate.parse(today) }.getOrElse {
        Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
    }
    val clampedFixedMinutes = fixedTimeMinutes.coerceIn(0, 23 * 60 + 59)
    val fixedGate = todayDate
        .atTime(LocalTime.of(clampedFixedMinutes / 60, clampedFixedMinutes % 60))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
    val autoFallbackGate = todayDate
        .atTime(LocalTime.of(18, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()
    val wakeEpochMs = analysisWindow.endEpochMs?.takeIf {
        analysisWindow.sourceType != NowAnalysisWindowSourceType.NO_MAIN_SLEEP &&
            analysisWindow.sourceType != NowAnalysisWindowSourceType.PENDING
    }
    val recentWakeEpochMs = wakeEpochMs?.takeIf {
        nowEpochMs >= it && nowEpochMs - it <= JOURNAL_AUTO_WAKE_USABLE_WINDOW_MS
    }
    val gate = if (mode == NowJournalFocusMode.FIXED_TIME) {
        fixedGate
    } else {
        recentWakeEpochMs?.plus(JOURNAL_AUTO_DELAY_AFTER_WAKE_MS) ?: autoFallbackGate
    }
    val label = if (mode == NowJournalFocusMode.FIXED_TIME) {
        "Journal focus at ${clampedFixedMinutes.timeOfDayLabel()}"
    } else if (recentWakeEpochMs != null) {
        "Journal focus about 6h after wake"
    } else {
        "Journal focus at 18:00"
    }
    val detail = "Journal is ready when you want to capture how the day is going."
    return NowJournalFocus(
        mode = mode,
        shouldFocusJournal = nowEpochMs >= gate,
        gateEpochMs = gate,
        label = label,
        detail = detail
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

/**
 * Folds the former TodayReadinessStatus into the forecast core: the signal-acquisition
 * stage, signal-confidence summary, HRV detail, and the device/hero attention prompts now
 * ride on [NowCurrentState] itself rather than a parallel status object. Catch-up/freshness
 * labels are read directly from [NowFreshness] at the call site, so they are not duplicated
 * here.
 */
private fun NowCurrentState.withSignalContext(
    signalRobustness: NowSignalRobustness,
    markerStatus: NowMarkerStatus,
    morningRead: AnalysisWindowEvidence?,
    hasFinalSleep: Boolean,
    hasPpi: Boolean,
    syncRunning: Boolean,
    noMainSleep: Boolean,
    analysisWindow: NowAnalysisWindowProvenance
): NowCurrentState {
    val stage = when {
        markerStatus.state == NowMarkerState.ACTIVE_BEDTIME -> NowSignalStage.SLEEP_TIME
        syncRunning -> NowSignalStage.STARTING_SYNC
        hasFinalSleep || noMainSleep || kind == NowCurrentStateKind.READY -> NowSignalStage.UPDATE_COMPLETE
        hasPpi -> NowSignalStage.INITIAL_PPI
        else -> NowSignalStage.NOT_STARTED
    }
    val signalConfidence = if (noMainSleep) {
        SignalConfidenceSummary(
            state = SignalConfidenceState.PARTIAL,
            label = "Sleep not applicable",
            missingInputs = signalRobustness.missingInputs,
            supportingGaps = signalRobustness.supportingGaps
        )
    } else {
        signalConfidenceSummary(
            stage = stage,
            morningRead = morningRead,
            hasFinalSleep = hasFinalSleep,
            hasPpi = hasPpi,
            hasUsableWindow = noMainSleep || morningRead.hasEstablishedSleepWindow()
        )
    }
    return copy(
        stage = stage,
        hrvDetail = hrvDetailForState(morningRead, noMainSleep, analysisWindow),
        signalConfidence = signalConfidence,
        connectionPrompt = if (syncRunning) {
            "Keep the phone close to the Loop until sync finishes."
        } else {
            null
        },
        heroPrompt = when {
            syncRunning -> "Stay near Loop"
            markerStatus.state == NowMarkerState.STALE_BEDTIME -> "Marker stale"
            else -> null
        }
    )
}

private fun hrvDetailForState(
    morningRead: AnalysisWindowEvidence?,
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
    activeMorningRead: AnalysisWindowEvidence?,
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
        confidenceLabel = if (activeMorningRead.sleepDataReady) "Resolved window" else "Provisional window",
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

private fun basisLabel(morningRead: AnalysisWindowEvidence?, noMainSleep: Boolean): String =
    when {
        noMainSleep -> "No main sleep decision"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT ->
            "Calibrated sleep window + PPI, Loop report pending"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT ->
            "Manual sleep window + PPI, Loop report pending"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT ->
            "PPI-inferred sleep window, Loop report pending"
        morningRead?.morningReadSource() == AnalysisWindowSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT ->
            "Manual sleep window, autonomic unavailable"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "Calibrated sleep window + PPI, Loop report as context"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "Manual sleep window + PPI, Loop report as context"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "PPI-inferred sleep window, Loop report as context"
        morningRead?.sleepDataReady == true && morningRead.hasPpiSignal() ->
            "PPI aligned to Loop sleep report"
        morningRead?.sleepDataReady == true ->
            "Loop sleep context only"
        morningRead?.isInterim == true ->
            "Current signal, Loop report pending"
        else -> "Waiting for morning data"
    }

private fun AnalysisWindowEvidence.morningReadSource(): AnalysisWindowSource? =
    AnalysisWindowSource.fromKey(overnightAutonomicSource)

private fun String.analysisWindowSourceType(): NowAnalysisWindowSourceType {
    val source = AnalysisWindowSource.fromKey(this)
    return when (source) {
        AnalysisWindowSource.USER_CONFIRMED_NO_SLEEP -> NowAnalysisWindowSourceType.NO_MAIN_SLEEP
        AnalysisWindowSource.EDITED_SLEEP_EPISODE_PRIMARY,
        AnalysisWindowSource.MIXED_SLEEP_EPISODE_PRIMARY,
        AnalysisWindowSource.MANUAL_SLEEP_EPISODE_PRIMARY,
        AnalysisWindowSource.CONFIRMED_SLEEP_EPISODE_PRIMARY -> NowAnalysisWindowSourceType.USER_SELECTED
        AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT,
        AnalysisWindowSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT,
        AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> NowAnalysisWindowSourceType.MARKER_DERIVED
        AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT,
        AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT,
        AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT,
        AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> NowAnalysisWindowSourceType.MODEL_ESTIMATE
        AnalysisWindowSource.PPI247_SLEEP_WINDOW,
        AnalysisWindowSource.NIGHTLY_RECHARGE_SUMMARY,
        AnalysisWindowSource.SLEEP_CONTEXT_ONLY -> NowAnalysisWindowSourceType.LOOP_REPORT
        AnalysisWindowSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW,
        AnalysisWindowSource.RAW_PPI_PENDING_SLEEP_WINDOW,
        AnalysisWindowSource.AWAITING_SLEEP_DATA -> NowAnalysisWindowSourceType.PENDING
        null -> if (contains("pending", ignoreCase = true)) {
            NowAnalysisWindowSourceType.PENDING
        } else {
            NowAnalysisWindowSourceType.UNKNOWN
        }
    }
}

private fun String.analysisWindowLabel(sleepDataReady: Boolean): String {
    val source = AnalysisWindowSource.fromKey(this)
    return when (source) {
        AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT -> "calibrated sleep window"
        AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT -> "manual marker-derived sleep window"
        AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT -> "PPI-inferred sleep window"
        AnalysisWindowSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT -> "manual marker-derived sleep window"
        AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "calibrated primary window"
        AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "manual primary window"
        AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "PPI-inferred primary window"
        AnalysisWindowSource.EDITED_SLEEP_EPISODE_PRIMARY -> "edited user window"
        AnalysisWindowSource.MIXED_SLEEP_EPISODE_PRIMARY,
        AnalysisWindowSource.MANUAL_SLEEP_EPISODE_PRIMARY,
        AnalysisWindowSource.CONFIRMED_SLEEP_EPISODE_PRIMARY -> "confirmed user window"
        AnalysisWindowSource.PPI247_SLEEP_WINDOW -> "Loop sleep report window"
        AnalysisWindowSource.NIGHTLY_RECHARGE_SUMMARY -> "Nightly Recharge sleep context"
        AnalysisWindowSource.SLEEP_CONTEXT_ONLY -> "Loop sleep context"
        AnalysisWindowSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW,
        AnalysisWindowSource.RAW_PPI_PENDING_SLEEP_WINDOW -> "sleep window pending"
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

private fun ppiReceiptLabelForState(morningRead: AnalysisWindowEvidence?): String = when {
    morningRead?.rawPpiGoodEpochCount != null -> {
        val coverage = morningRead.rawPpiCoverageHours?.let {
            String.format(java.util.Locale.UK, ", %.1fh aligned", it)
        }.orEmpty()
        "Received (${morningRead.rawPpiGoodEpochCount} usable windows$coverage)"
    }
    morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW -> "Received, missing sleep window"
    morningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true -> "Received, Loop report pending"
    else -> "Not received yet"
}

private fun catchUpPrompt(today: String, morningRead: AnalysisWindowEvidence?): String? {
    val latestReadDate = morningRead?.sourceDate
        ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?: return null
    val todayDate = runCatching { java.time.LocalDate.parse(today) }.getOrNull() ?: return null
    val missingDays = java.time.temporal.ChronoUnit.DAYS.between(latestReadDate, todayDate)
    return if (missingDays > 0) {
        "Last forecast refresh was $missingDays day${if (missingDays == 1L) "" else "s"} ago."
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
private const val JOURNAL_AUTO_DELAY_AFTER_WAKE_MS = 6 * 60 * 60 * 1000L
private const val JOURNAL_AUTO_WAKE_FALLBACK_WINDOW_MS = 12 * 60 * 60 * 1000L
private const val JOURNAL_AUTO_WAKE_USABLE_WINDOW_MS =
    JOURNAL_AUTO_DELAY_AFTER_WAKE_MS + JOURNAL_AUTO_WAKE_FALLBACK_WINDOW_MS
