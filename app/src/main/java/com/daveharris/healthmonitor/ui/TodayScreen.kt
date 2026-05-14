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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import java.time.LocalDate

@Composable
fun DataScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    morningRead: MorningReadSnapshot?,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    viewModel: ProbeViewModel,
    actionsEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    val today = LocalDate.now().toString()
    val todayStatus = todayReadinessStatus(
        today = today,
        morningRead = morningRead,
        syncRuns = syncRuns,
        wakeMarkers = wakeMarkers,
        isBusy = viewModel.isBusy
    )
    val activeMorningRead = morningRead
        ?.takeIf { it.sourceDate == today }
        ?.takeUnless { todayStatus.stage == TodayReadinessStage.SLEEP_TIME }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TodayHeroCard(
                today = today,
                todayStatus = todayStatus,
                morningRead = activeMorningRead,
                onOpenSettings = onOpenSettings
            )
        }
        item {
            MorningSignalSection(activeMorningRead, todayStatus)
        }
        item {
            SectionCard(title = "Morning sync", subtitle = "Loop readiness checklist") {
                SupportText("Use I'm going to bed to mark intended sleep time, then I'm awake when you are ready to mark the day and run the normal Lodestone sync.")
                DetailRow("Status", todayStatus.title)
                DetailRow("Device", viewModel.selectedDeviceId ?: "None selected")
                DetailRow(
                    "Connection",
                    runtime.connectedDevice?.name?.let { "Connected to $it" }
                        ?: runtime.connectionPhase.replaceFirstChar { it.titlecase() }
                )
                DetailRow("Final Loop sleep report", todayStatus.sleepReport)
                DetailRow("PPI data from Loop", todayStatus.ppiReceipt)
                SupportText(todayStatus.message)
                SupportText("PPI may arrive before the final Loop sleep report. Lodestone can show a provisional read from your bed/wake markers and calibrated PPI, then replace it with the confirmed read when the final report resolves.")
                ButtonRow {
                    OutlinedButton(
                        onClick = { if (actionsEnabled) viewModel.markGoingToBed() },
                        enabled = !viewModel.isBusy
                    ) {
                        Text("I'm going to bed")
                    }
                    Button(
                        onClick = { if (actionsEnabled) viewModel.markAwakeAndSync() },
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("I'm awake")
                    }
                }
            }
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
                        onClick = { if (actionsEnabled) viewModel.runManualSync() },
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Run sync")
                    }
                }
            }
        }
    }
}
