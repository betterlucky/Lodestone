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
import com.daveharris.healthmonitor.data.AutonomicDetailScope
import com.daveharris.healthmonitor.data.AutonomicScopeResolver
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
 * Signals. The order matches the [SignalsScreen] LazyColumn section items (the hero
 * card is item 0, so each anchor maps to its index + 1 via [lazyItemIndex]).
 */
enum class SignalsSection {
    CURRENT_SIGNAL,
    SLEEP_REST,
    SIGNAL_DETAIL;

    fun lazyItemIndex(): Int = ordinal + 1
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
    val activeScopeSummary = remember(recoveryWindow, ppi247Epochs, sleepEpisodes, lastPpiSyncEpochMs) {
        AutonomicScopeResolver.resolve(
            scope = AutonomicDetailScope.ACTIVE_SLEEP_REST,
            epochs = ppi247Epochs,
            recoveryWindow = recoveryWindow,
            sleepEpisodes = sleepEpisodes,
            lastPpiSyncEpochMs = lastPpiSyncEpochMs
        )
    }
    var showAutonomicDetail by remember { mutableStateOf(false) }
    var evidenceDetail by remember { mutableStateOf<NowEvidenceDetail?>(null) }
    var showSleepWindowEvidence by remember { mutableStateOf(false) }

    LaunchedEffect(activeScopeSummary.hasChart, recoveryWindow) {
        if (!activeScopeSummary.hasChart && recoveryWindow == null) {
            showAutonomicDetail = false
        }
    }

    if (showAutonomicDetail) {
        AutonomicDetailDialog(
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
            onDismiss = { evidenceDetail = null },
            onOpenHrvTrajectory = {
                evidenceDetail = null
                showAutonomicDetail = true
            }
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
            MorningSignalSection(
                nowState = nowState,
                onOpenEvidence = { detail ->
                    if (detail == NowEvidenceDetail.SLEEP_REST) {
                        showSleepWindowEvidence = true
                    } else {
                        evidenceDetail = detail
                    }
                }
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
                autonomicDetailAvailable = activeScopeSummary.hasChart || recoveryWindow != null,
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
