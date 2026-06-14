@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.CurrentStateRead
import com.daveharris.healthmonitor.data.AnalysisWindowEvidence
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import com.daveharris.healthmonitor.resolveLodestoneDisplayDate

@Composable
fun DataScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    morningRead: AnalysisWindowEvidence?,
    currentState: CurrentStateRead?,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    viewModel: ProbeViewModel,
    actionsEnabled: Boolean,
    onOpenJournal: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSignalsSection: (SignalsSection) -> Unit = {}
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
    val todayStatus = nowState.readinessStatus
    var markerEditor by remember { mutableStateOf<MarkerTimeEditorKind?>(null) }
    val openJournalForToday = {
        if (actionsEnabled) {
            viewModel.updateCheckInDate(nowState.today)
            onOpenJournal()
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
                onOpenSettings = onOpenSettings,
                onOpenSignalsSection = onOpenSignalsSection
            )
        }
        item {
            val journalFocused = nowState.journalFocus.shouldFocusJournal
            SectionCard(
                title = if (journalFocused) "How has today gone?" else "Check in",
                subtitle = if (journalFocused) "A quick journal keeps the forecast honest" else "Sync current data"
            ) {
                SupportText(
                    if (journalFocused) nowState.journalFocus.detail else dailyForecastCheckInMessage(nowState)
                )
                todayStatus.catchUpPrompt?.let { SupportText(it) }
                if (shouldShowLoopAttention(nowState, runtime, viewModel.isBusy)) {
                    BannerNote(
                        text = loopAttentionText(nowState),
                        tint = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
                        textColor = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                // One obvious action. The evening journal gate promotes Journal to the
                // primary; otherwise Check in (sync) leads. Markers and the secondary
                // action stay available but demoted (redesign: hero = at most one action).
                if (journalFocused) {
                    Button(
                        onClick = openJournalForToday,
                        enabled = actionsEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Open today's journal")
                    }
                } else {
                    Button(
                        onClick = { if (actionsEnabled) viewModel.runCheckInSync() },
                        enabled = actionsEnabled && nowState.primaryActions.checkIn.enabled,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Check in")
                    }
                }
                ButtonRow {
                    if (journalFocused) {
                        OutlinedButton(
                            onClick = { if (actionsEnabled) viewModel.runCheckInSync() },
                            enabled = actionsEnabled && nowState.primaryActions.checkIn.enabled
                        ) {
                            Text("Sync now")
                        }
                    } else {
                        OutlinedButton(
                            onClick = openJournalForToday,
                            enabled = actionsEnabled
                        ) {
                            Text("Journal")
                        }
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
                        OutlinedButton(
                            onClick = { if (actionsEnabled) markerEditor = MarkerTimeEditorKind.WAKING },
                            enabled = actionsEnabled && nowState.primaryActions.waking.enabled
                        ) {
                            Text("Waking marker")
                        }
                    }
                }
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
