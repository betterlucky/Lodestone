@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daveharris.healthmonitor.data.CurrentStateRead
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.AnalysisWindowEvidence
import com.daveharris.healthmonitor.data.Ppi247EpochEntity
import com.daveharris.healthmonitor.data.SleepEpisodeEntity
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import com.daveharris.healthmonitor.resolveLodestoneDisplayDate

/**
 * Anchors the Now hero pills can target so a tap acts as a table of contents into
 * Signals. [lazyItemIndex] maps each anchor to its [SignalsScreen] LazyColumn item
 * (the hero card is item 0). Keep this mapping in sync if the section order changes.
 */
enum class SignalsSection {
    CURRENT_SIGNAL,
    SLEEP_REST,
    SIGNAL_DETAIL;

    // Explicit, not ordinal-derived, so reordering the enum can't silently
    // misroute a pill to the wrong section.
    fun lazyItemIndex(): Int = when (this) {
        CURRENT_SIGNAL -> 1
        SLEEP_REST -> 2
        SIGNAL_DETAIL -> 3
    }
}

@Composable
fun SignalsScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    morningRead: AnalysisWindowEvidence?,
    currentState: CurrentStateRead?,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    ppi247Epochs: List<Ppi247EpochEntity>,
    sleepEpisodes: List<SleepEpisodeEntity>,
    viewModel: ProbeViewModel,
    actionsEnabled: Boolean,
    onOpenSettings: () -> Unit,
    scrollToSection: SignalsSection? = null,
    onSectionConsumed: () -> Unit = {}
) {
    val today = resolveLodestoneDisplayDate(
        latestAnalysisWindowSourceDate = morningRead?.sourceDate,
        wakeMarkers = wakeMarkers
    ).sourceDate
    val nowState = buildNowScreenState(
        today = today,
        morningRead = morningRead,
        currentStateRead = currentState,
        syncRuns = syncRuns,
        wakeMarkers = wakeMarkers,
        dailyCheckIns = dailyCheckIns,
        sleepEpisodeReviewState = sleepEpisodeReviewState,
        runtime = runtime,
        selectedDeviceId = viewModel.selectedDeviceId,
        isBusy = viewModel.isBusy,
        markerMode = viewModel.markerMode,
        checkInIntent = viewModel.checkInIntent,
        journalFocusMode = viewModel.journalFocusMode,
        journalFocusFixedTimeMinutes = viewModel.journalFocusFixedTimeMinutes
    )
    val recoveryWindow = nowState.activeAnalysisWindow.toAutonomicRecoveryWindow()
    val lastPpiSyncEpochMs = ppi247Epochs.maxOfOrNull { it.updatedAtEpochMs }
    // Reachable whenever there is something to plot: an active sleep/rest recovery
    // window, or any 24/7 PPI for the rolling/recent-trend scopes the sheet's selector
    // can switch to. Don't gate it off the recovery scope alone.
    val autonomicDetailAvailable = recoveryWindow != null || ppi247Epochs.isNotEmpty()
    var showAutonomicDetail by remember { mutableStateOf(false) }
    var evidenceDetail by remember { mutableStateOf<NowEvidenceDetail?>(null) }
    var showSleepWindowEvidence by remember { mutableStateOf(false) }

    LaunchedEffect(autonomicDetailAvailable) {
        if (!autonomicDetailAvailable) {
            showAutonomicDetail = false
        }
    }

    if (showAutonomicDetail) {
        AutonomicDetailSheet(
            recoveryWindow = recoveryWindow,
            epochs = ppi247Epochs,
            sleepEpisodes = sleepEpisodes,
            lastPpiSyncEpochMs = lastPpiSyncEpochMs,
            onDismiss = { showAutonomicDetail = false }
        )
    }
    evidenceDetail?.let { detail ->
        NowEvidenceDetailSheet(
            detail = detail,
            nowState = nowState,
            onDismiss = { evidenceDetail = null }
        )
    }
    if (showSleepWindowEvidence) {
        CandidateReviewSheet(
            state = sleepEpisodeReviewState,
            wakeMarkers = wakeMarkers,
            activeAnalysisWindow = nowState.activeAnalysisWindow,
            actionsEnabled = actionsEnabled && !viewModel.isBusy,
            onAcceptMainSleep = viewModel::acceptSleepEpisodeAsMain,
            onAcceptNap = viewModel::acceptSleepEpisodeAsNap,
            onMarkRest = viewModel::markSleepEpisodeAsRest,
            onRejectCandidate = viewModel::rejectSleepEpisodeCandidate,
            onClearDecision = viewModel::clearSleepEpisodeDecision,
            onAddManualWindow = viewModel::addManualSleepWindow,
            onEditWindow = viewModel::editSleepEpisodeWindow,
            onEditMarker = viewModel::editWakeMarker,
            onMarkNoMainSleep = viewModel::markNoMainSleep,
            onDismiss = { showSleepWindowEvidence = false }
        )
    }

    val listState = rememberLazyListState()
    val consumeSection by rememberUpdatedState(onSectionConsumed)
    LaunchedEffect(scrollToSection) {
        scrollToSection?.let { section ->
            listState.animateScrollToItem(section.lazyItemIndex())
            consumeSection()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroCard(
                eyebrow = "Live detail",
                title = "Signals",
                subtitle = signalsHeroSubtitle(nowState),
                actionLabel = "Settings",
                onAction = onOpenSettings
            )
        }
        item {
            SignalsCurrentSection(
                nowState = nowState,
                onOpenForecastEvidence = { evidenceDetail = NowEvidenceDetail.SIGNAL }
            )
        }
        item {
            SignalsSleepRestSection(
                nowState = nowState,
                sleepEpisodeReviewState = sleepEpisodeReviewState,
                actionsEnabled = actionsEnabled,
                onReviewWindows = { showSleepWindowEvidence = true }
            )
        }
        item {
            SignalsDataSection(
                nowState = nowState,
                autonomicDetailAvailable = autonomicDetailAvailable,
                onOpenDataQuality = { evidenceDetail = NowEvidenceDetail.DATA_QUALITY },
                onOpenAutonomicDetail = { showAutonomicDetail = true }
            )
        }
    }
}

private fun signalsHeroSubtitle(nowState: NowScreenState): String =
    when {
        nowState.signalRobustness.availability == NowDataAvailability.MISSING ->
            "No Loop signal is available for this read. Recent check-ins remain usable, but body-signal detail is unavailable."
        nowState.signalRobustness.availability == NowDataAvailability.PENDING ->
            "Sync is running. Keep the phone and Loop close until the live detail refreshes."
        else ->
            "Review the evidence behind the forecast, repair sleep/rest windows, and inspect signal quality."
    }

@Composable
private fun SignalsCurrentSection(
    nowState: NowScreenState,
    onOpenForecastEvidence: () -> Unit
) {
    // Evidence behind the forecast, not a re-print of the Now hero status. The "why"
    // (caution reasons) plus the contextual lanes the hero doesn't surface.
    SectionCard(title = "Current signal", subtitle = "Evidence behind today's forecast") {
        val reasons = nowState.currentStateRead?.reasons.orEmpty().take(3)
        if (reasons.isEmpty()) {
            SupportText(nowState.currentState.message)
        } else {
            reasons.forEach { reason -> SupportText("• $reason") }
        }
        if (nowState.functionalContext.availability != NowDataAvailability.MISSING) {
            DetailRow("Functional context", nowState.functionalContext.label)
        }
        if (nowState.autonomicContext.availability != NowDataAvailability.MISSING) {
            DetailRow("Autonomic context", nowState.autonomicContext.label)
        }
        ButtonRow {
            OutlinedButton(onClick = onOpenForecastEvidence) {
                Text("Forecast evidence")
            }
        }
    }
}

@Composable
private fun SignalsSleepRestSection(
    nowState: NowScreenState,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    actionsEnabled: Boolean,
    onReviewWindows: () -> Unit
) {
    SectionCard(title = "Sleep and rest", subtitle = "Windows, candidates, and manual repair") {
        DetailRow("Recent rest", nowState.recentRest.detail)
        DetailRow("Selected window", nowState.activeAnalysisWindow.label)
        DetailRow("Decision", sleepEpisodeReviewState.activeDateGroup?.repairStatusLabel ?: "No candidates")
        SupportText(sleepEpisodeReviewState.surfaceMessage)
        ButtonRow {
            Button(
                onClick = onReviewWindows,
                enabled = actionsEnabled
            ) {
                Text("Review windows")
            }
        }
    }
}

@Composable
private fun SignalsDataSection(
    nowState: NowScreenState,
    autonomicDetailAvailable: Boolean,
    onOpenDataQuality: () -> Unit,
    onOpenAutonomicDetail: () -> Unit
) {
    SectionCard(title = "Signal detail", subtitle = "Coverage, freshness, and autonomic scopes") {
        if (nowState.signalRobustness.availability == NowDataAvailability.MISSING) {
            BannerNote(
                text = "Loop data is unavailable for this read. Lodestone will not claim body-signal support until sync succeeds.",
                tint = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                textColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        DetailRow("Confidence", nowState.signalRobustness.label)
        DetailRow("Last sync", nowState.freshness.loopSync.detail)
        DetailRow("Freshness", nowState.freshness.lastUsed.detail)
        ButtonRow {
            OutlinedButton(onClick = onOpenDataQuality) {
                Text("Data quality")
            }
            OutlinedButton(
                onClick = onOpenAutonomicDetail,
                enabled = autonomicDetailAvailable
            ) {
                Text("Autonomic detail")
            }
        }
    }
}
