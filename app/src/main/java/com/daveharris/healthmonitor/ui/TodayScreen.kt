@file:OptIn(ExperimentalLayoutApi::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
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
import com.daveharris.healthmonitor.data.TrafficLightStatus
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
        isBusy = viewModel.isBusy
    )
    val todayStatus = nowState.readinessStatus
    val activeMorningRead = nowState.activeMorningRead
    var showHrvTrajectory by remember { mutableStateOf(false) }
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
            MorningSignalSection(nowState)
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
        item {
            SectionCard(title = "Check in", subtitle = "Loop readiness checklist") {
                SupportText("Use Check in to sync and assess the current situation without marking wake time. Use the sleep/wake buttons only when you want to record those events.")
                DetailRow("Status", nowState.currentState.label)
                DetailRow("Robustness", nowState.signalRobustness.label)
                DetailRow("Stability", nowState.stateStability.label)
                DetailRow("Marker", nowState.markerStatus.label)
                DetailRow("Last used", nowState.freshness.lastUsed.detail)
                DetailRow("Loop sync", nowState.freshness.loopSync.detail)
                DetailRow("Device", viewModel.selectedDeviceId ?: "None selected")
                DetailRow(
                    "Connection",
                    runtime.connectedDevice?.name?.let { "Connected to $it" }
                        ?: runtime.connectionPhase.replaceFirstChar { it.titlecase() }
                )
                ButtonRow {
                    StatusBadge(
                        label = morningConnectionBadgeLabel(runtime, todayStatus),
                        status = morningConnectionBadgeStatus(runtime, todayStatus)
                    )
                }
                DetailRow("Final Loop sleep report", todayStatus.sleepReport)
                DetailRow("PPI data from Loop", todayStatus.ppiReceipt)
                SupportText(todayStatus.message)
                todayStatus.catchUpPrompt?.let { SupportText(it) }
                todayStatus.connectionPrompt?.let { SupportText(it) }
                SupportText("PPI may arrive before the final Loop sleep report. Lodestone can show a provisional read from markers or inferred windows, then keep the vendor sleep report as context when it resolves.")
                ButtonRow {
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
                            onClick = { if (actionsEnabled) viewModel.markGoingToBed() },
                            enabled = actionsEnabled && nowState.primaryActions.bedtime.enabled
                        ) {
                            Text("I'm going to bed")
                        }
                    }
                    if (nowState.primaryActions.waking.visible) {
                        Button(
                            onClick = { if (actionsEnabled) viewModel.markAwakeAndSync() },
                            enabled = actionsEnabled && nowState.primaryActions.waking.enabled
                        ) {
                            Text("I'm awake")
                        }
                    }
                }
            }
        }
        item {
            CandidateReviewSection(
                state = sleepEpisodeReviewState,
                actionsEnabled = actionsEnabled && !viewModel.isBusy,
                onAcceptMainSleep = viewModel::acceptSleepEpisodeAsMain,
                onAcceptNap = viewModel::acceptSleepEpisodeAsNap,
                onMarkRest = viewModel::markSleepEpisodeAsRest,
                onRejectCandidate = viewModel::rejectSleepEpisodeCandidate,
                onClearDecision = viewModel::clearSleepEpisodeDecision,
                onEditWindow = viewModel::editSleepEpisodeWindow,
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
                    OutlinedButton(
                        onClick = { if (actionsEnabled) viewModel.runCheckInSync() },
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Check in")
                    }
                }
            }
        }
    }
}

private fun morningConnectionBadgeLabel(
    runtime: DeviceRuntimeState,
    todayStatus: TodayReadinessStatus
): String =
    when {
        !runtime.bluetoothPowered -> "Bluetooth off"
        runtime.connectionPhase == "connected" -> "Loop link solid"
        todayStatus.stage == TodayReadinessStage.STARTING_SYNC -> "Recovering link"
        runtime.connectionPhase == "connecting" -> "Connecting"
        else -> "Loop not connected"
    }

private fun morningConnectionBadgeStatus(
    runtime: DeviceRuntimeState,
    todayStatus: TodayReadinessStatus
): TrafficLightStatus? =
    when {
        !runtime.bluetoothPowered -> TrafficLightStatus.CRASH
        runtime.connectionPhase == "connected" -> TrafficLightStatus.GOOD
        todayStatus.stage == TodayReadinessStage.STARTING_SYNC -> TrafficLightStatus.UNSTEADY
        runtime.connectionPhase == "connecting" -> TrafficLightStatus.OK
        else -> null
    }
