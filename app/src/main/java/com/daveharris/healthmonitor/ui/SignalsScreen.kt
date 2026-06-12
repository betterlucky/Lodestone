@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import com.daveharris.healthmonitor.resolveLodestoneDisplayDate

@Composable
fun SignalsScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    morningRead: MorningReadSnapshot?,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    viewModel: ProbeViewModel,
    actionsEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    val today = resolveLodestoneDisplayDate(
        latestMorningReadSourceDate = morningRead?.sourceDate,
        wakeMarkers = wakeMarkers
    ).sourceDate
    val nowState = buildNowScreenState(
        today = today,
        morningRead = morningRead,
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
    val activeMorningRead = nowState.activeMorningRead
    var showHrvTrajectory by remember { mutableStateOf(false) }
    var evidenceDetail by remember { mutableStateOf<NowEvidenceDetail?>(null) }
    var showSleepWindowEvidence by remember { mutableStateOf(false) }

    LaunchedEffect(activeMorningRead == null) {
        if (activeMorningRead == null) {
            showHrvTrajectory = false
        }
    }

    if (showHrvTrajectory && activeMorningRead != null) {
        HrvTrajectoryDialog(
            morningRead = activeMorningRead,
            onDismiss = { showHrvTrajectory = false }
        )
    }
    evidenceDetail?.let { detail ->
        NowEvidenceDetailSheet(
            detail = detail,
            nowState = nowState,
            onDismiss = { evidenceDetail = null },
            onOpenHrvTrajectory = {
                evidenceDetail = null
                showHrvTrajectory = true
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

    LazyColumn(
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
                onOpenDataQuality = { evidenceDetail = NowEvidenceDetail.DATA_QUALITY },
                onOpenHrv = { evidenceDetail = NowEvidenceDetail.HRV }
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
    onOpenDataQuality: () -> Unit,
    onOpenHrv: () -> Unit
) {
    SectionCard(title = "Signal detail", subtitle = "Coverage, freshness, and trajectory") {
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
                onClick = onOpenHrv,
                enabled = nowState.activeMorningRead?.hrvTrajectory?.isNotEmpty() == true
            ) {
                Text("HRV detail")
            }
        }
    }
}
