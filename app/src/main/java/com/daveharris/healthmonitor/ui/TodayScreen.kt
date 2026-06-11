@file:OptIn(ExperimentalLayoutApi::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
fun DataScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    morningRead: MorningReadSnapshot?,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    viewModel: ProbeViewModel,
    actionsEnabled: Boolean,
    onOpenJournal: () -> Unit,
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
    val todayStatus = nowState.readinessStatus
    val activeMorningRead = nowState.activeMorningRead
    var showHrvTrajectory by remember { mutableStateOf(false) }
    var markerEditor by remember { mutableStateOf<MarkerTimeEditorKind?>(null) }
    LaunchedEffect(activeMorningRead == null) {
        if (activeMorningRead == null) {
            showHrvTrajectory = false
        }
    }
    markerEditor?.let { kind ->
        MarkerTimeEditorSheet(
            kind = kind,
            initialEpochMs = System.currentTimeMillis(),
            onSave = { markerEpochMs ->
                when (kind) {
                    MarkerTimeEditorKind.BEDTIME -> viewModel.markGoingToBed(markerEpochMs)
                    MarkerTimeEditorKind.WAKING -> viewModel.markAwake(markerEpochMs)
                }
                markerEditor = null
            },
            onDismiss = { markerEditor = null }
        )
    }
    if (showHrvTrajectory && activeMorningRead != null) {
        HrvTrajectoryDialog(
            morningRead = activeMorningRead,
            onDismiss = { showHrvTrajectory = false }
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
            TodayHeroCard(
                nowState = nowState,
                onOpenSettings = onOpenSettings
            )
        }
        item {
            SectionCard(title = "Check in", subtitle = "Sync current data") {
                SupportText(todayStatus.message)
                todayStatus.catchUpPrompt?.let { SupportText(it) }
                if (shouldShowLoopAttention(nowState, runtime, viewModel.isBusy)) {
                    BannerNote(
                        text = loopAttentionText(nowState),
                        tint = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                ButtonRow {
                    if (nowState.journalFocus.shouldFocusJournal) {
                        OutlinedButton(
                            onClick = { if (actionsEnabled) onOpenJournal() },
                            enabled = actionsEnabled
                        ) {
                            Text("Open Journal")
                        }
                    }
                    Button(
                        onClick = { if (actionsEnabled) viewModel.runCheckInSync() },
                        enabled = actionsEnabled && nowState.primaryActions.checkIn.enabled
                    ) {
                        Text("Check in")
                    }
                    if (nowState.primaryActions.catchUp.visible) {
                        OutlinedButton(
                            onClick = { if (actionsEnabled) viewModel.runCatchUpSync() },
                            enabled = actionsEnabled && nowState.primaryActions.catchUp.enabled
                        ) {
                            Text("Catch up")
                        }
                    }
                    if (nowState.primaryActions.bedtime.visible) {
                        OutlinedButton(
                            onClick = { if (actionsEnabled) markerEditor = MarkerTimeEditorKind.BEDTIME },
                            enabled = actionsEnabled && nowState.primaryActions.bedtime.enabled
                        ) {
                            Text("Bedtime marker")
                        }
                    }
                    if (nowState.primaryActions.waking.visible) {
                        Button(
                            onClick = { if (actionsEnabled) markerEditor = MarkerTimeEditorKind.WAKING },
                            enabled = actionsEnabled && nowState.primaryActions.waking.enabled
                        ) {
                            Text("Waking marker")
                        }
                    }
                }
                if (nowState.journalFocus.shouldFocusJournal) {
                    SupportText(nowState.journalFocus.detail)
                }
            }
        }
        item {
            MorningSignalSection(nowState)
        }
        item {
            CandidateReviewSection(
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
                onMarkNoMainSleep = viewModel::markNoMainSleep
            )
        }
        item {
            SectionCard(title = "Overnight HRV detail", subtitle = "Signal coverage from normal Loop sync") {
                val goodEpochs = activeMorningRead?.rawPpiGoodEpochCount
                if (goodEpochs == null) {
                    SupportText(todayStatus.hrvDetail)
                } else {
                    DetailRow("Signal basis", morningReadBasisLabel(activeMorningRead, todayStatus))
                    DetailRow("Window", activeMorningRead.analysisWindowLabel())
                    DetailRow("Usable windows", goodEpochs.toString())
                    DetailRow("Coverage", activeMorningRead.rawPpiCoverageHours?.let { String.format(java.util.Locale.UK, "%.1fh", it) } ?: "n/a")
                    if ((activeMorningRead.rawPpiPoorEpochCount ?: 0) > 0) {
                        DetailRow("Flagged windows", activeMorningRead.rawPpiPoorEpochCount.toString())
                    }
                }
                ButtonRow {
                    OutlinedButton(
                        onClick = { showHrvTrajectory = true },
                        enabled = activeMorningRead?.hrvTrajectory?.isNotEmpty() == true
                    ) {
                        Text("View HRV trajectory")
                    }
                }
            }
        }
        item {
            SectionCard(title = "Data quality", subtitle = "Morning-read inputs") {
                DetailRow("State", nowState.signalRobustness.label)
                DetailRow("Basis", nowState.signalRobustness.basisLabel)
                DetailRow("Sleep report", nowState.signalRobustness.sleepReport.detail)
                DetailRow("PPI", nowState.signalRobustness.ppi.detail)
                DetailRow("Baseline", nowState.signalRobustness.baseline.detail)
                DetailRow("Nightly Recharge", nowState.signalRobustness.nightlyRecharge.detail)
            }
        }
    }
}

private fun shouldShowLoopAttention(
    nowState: NowScreenState,
    runtime: DeviceRuntimeState,
    isBusy: Boolean
): Boolean =
    when {
        nowState.deviceConnection.availability == NowDataAvailability.MISSING -> true
        nowState.deviceConnection.availability == NowDataAvailability.PENDING -> true
        nowState.readinessStatus.connectionPrompt != null -> true
        isBusy -> true
        runtime.connectionPhase == "connecting" -> true
        else -> false
    }

private fun loopAttentionText(nowState: NowScreenState): String =
    when {
        nowState.readinessStatus.connectionPrompt != null -> nowState.readinessStatus.connectionPrompt
        nowState.deviceConnection.detail == "Bluetooth off" -> "Bluetooth is off. Turn it on before syncing with the Loop."
        nowState.deviceConnection.detail == "No Loop selected" -> "No Loop selected. Choose a Loop in Settings before syncing."
        nowState.deviceConnection.availability == NowDataAvailability.MISSING -> nowState.deviceConnection.detail
        nowState.deviceConnection.availability == NowDataAvailability.PENDING -> "Connecting to Loop. Keep the phone and Loop close."
        else -> "Keep the phone and Loop close until sync finishes."
    }
