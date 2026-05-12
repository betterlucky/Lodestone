@file:OptIn(ExperimentalLayoutApi::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class TodayReadinessStage {
    SLEEP_TIME,
    STARTING_SYNC,
    INITIAL_PPI,
    UPDATE_COMPLETE,
    NOT_STARTED
}

data class TodayReadinessStatus(
    val stage: TodayReadinessStage,
    val title: String,
    val sleepReport: String,
    val ppiReceipt: String,
    val message: String,
    val hrvDetail: String
)

fun todayReadinessStatus(
    today: String,
    morningRead: MorningReadSnapshot?,
    syncRuns: List<SyncRunEntity>,
    wakeMarkers: List<WakeMarkerEntity>,
    isBusy: Boolean
): TodayReadinessStatus {
    val relevantMorningRead = morningRead?.takeIf { it.sourceDate == today }
    val latestRealMarker = wakeMarkers
        .filterNot { it.notes == "manual awake command" }
        .maxByOrNull { it.markerEpochMs }
    val latestMorningSync = syncRuns
        .filter { it.notes?.contains("morning", ignoreCase = true) == true }
        .maxByOrNull { it.startedAtEpochMs }
    val isSleeping = latestRealMarker?.markerSource == "manual_going_to_bed"
    val syncRunning = isBusy || latestMorningSync?.status == "running"
    val hasFinalSleep = relevantMorningRead?.sleepDataReady == true
    val hasPpi = relevantMorningRead?.rawPpiGoodEpochCount != null ||
        relevantMorningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true

    return when {
        isSleeping -> TodayReadinessStatus(
            stage = TodayReadinessStage.SLEEP_TIME,
            title = "Sleep time",
            sleepReport = "Cleared for tonight",
            ppiReceipt = "Waiting for wake sync",
            message = "Bedtime is marked. Today's old read is hidden until you wake and sync again.",
            hrvDetail = "Sleep mode is active. Overnight HRV detail will appear after you tap I'm awake and Lodestone syncs the Loop."
        )
        syncRunning -> TodayReadinessStatus(
            stage = TodayReadinessStage.STARTING_SYNC,
            title = "Starting sync",
            sleepReport = "Checking Loop",
            ppiReceipt = "Checking Loop",
            message = "Lodestone is connecting and pulling the morning core data.",
            hrvDetail = "Sync is running. PPI detail will appear as soon as the Loop returns enough data."
        )
        hasFinalSleep -> TodayReadinessStatus(
            stage = TodayReadinessStage.UPDATE_COMPLETE,
            title = "Update complete",
            sleepReport = "Final report present",
            ppiReceipt = ppiReceiptLabel(relevantMorningRead),
            message = "The final sleep report and morning signal are ready.",
            hrvDetail = "Raw PPI has been aligned to the resolved Loop sleep window."
        )
        hasPpi -> TodayReadinessStatus(
            stage = TodayReadinessStage.INITIAL_PPI,
            title = "Initial PPI data received",
            sleepReport = "Awaiting final report",
            ppiReceipt = ppiReceiptLabel(relevantMorningRead),
            message = if (relevantMorningRead.overnightAutonomicSource == "raw_ppi_pending_manual_sleep_window") {
                "PPI is available, but Lodestone has no bedtime marker for a provisional sleep window."
            } else {
                "This is an interim read. PPI is available, but Polar's final sleep report has not resolved yet."
            },
            hrvDetail = if (relevantMorningRead.overnightAutonomicSource == "raw_ppi_pending_manual_sleep_window") {
                "Tap I'm going to bed before sleep so Lodestone can calculate an interim signal before Polar's final sleep report arrives."
            } else {
                "The interim morning signal can use manual bed/wake timing, but treat it as provisional until the final sleep report arrives."
            }
        )
        else -> TodayReadinessStatus(
            stage = TodayReadinessStage.NOT_STARTED,
            title = "Awaiting morning sync",
            sleepReport = "Not synced yet",
            ppiReceipt = "Not received yet",
            message = "Tap I'm awake when you are ready to mark wake time and pull the morning data.",
            hrvDetail = "The raw overnight signal is stored from normal sync, but there is no current-day PPI or resolved sleep-window alignment yet."
        )
    }
}

@Composable
fun TodayHeroCard(
    today: String,
    todayStatus: TodayReadinessStatus,
    morningRead: MorningReadSnapshot?,
    onOpenSettings: () -> Unit
) {
    val statusLabel = morningRead?.status?.let { labelForStatus(it.name) } ?: "TBC"
    val qualifier = when {
        morningRead?.sleepDataReady == true -> "Confirmed"
        morningRead?.status != null -> "Provisional"
        todayStatus.stage == TodayReadinessStage.SLEEP_TIME -> "Sleep time"
        todayStatus.stage == TodayReadinessStage.STARTING_SYNC -> "Starting sync"
        else -> "TBC"
    }
    val confidence = morningRead?.confidence
        ?.takeUnless { it.equals("pending", ignoreCase = true) }
        ?.replaceFirstChar { it.titlecase() }

    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatHeroDate(today).uppercase(),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    TextButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Text(
                    "Today: $statusLabel",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeroPill(qualifier)
                    confidence?.let { HeroPill("$it confidence") }
                }
                Text(
                    todayStatus.message,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.90f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun HeroPill(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f))
            .border(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f), RoundedCornerShape(100.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatHeroDate(value: String): String =
    runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", java.util.Locale.UK))
    }.getOrDefault(value)

private fun ppiReceiptLabel(morningRead: MorningReadSnapshot?): String = when {
    morningRead?.rawPpiGoodEpochCount != null -> {
        val coverage = morningRead.rawPpiCoverageHours?.let {
            String.format(java.util.Locale.UK, ", %.1fh aligned", it)
        }.orEmpty()
        "Received (${morningRead.rawPpiGoodEpochCount} usable windows$coverage)"
    }
    morningRead?.overnightAutonomicSource == "raw_ppi_pending_manual_sleep_window" -> "Received, missing bedtime marker"
    morningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true -> "Received, awaiting final sleep report"
    else -> "Not received yet"
}

@Composable
fun MorningSignalSection(
    morningRead: MorningReadSnapshot?,
    todayStatus: TodayReadinessStatus
) {
    val tone = statusTone(morningRead?.status)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = tone.copy(alpha = if (morningRead?.status == null) 0.06f else 0.10f)
        ),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Morning signal", fontWeight = FontWeight.SemiBold)
            if (morningRead == null) {
                Text(todayStatus.hrvDetail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                morningRead.reasons.take(3).forEach { reason ->
                    Text("* $reason", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Date", morningRead.sourceDate ?: "unknown")
                    DetailRow("Source", morningRead.overnightAutonomicSource)
                    DetailRow("Sleep", formatDurationMinutes(morningRead.sleepDurationMinutes))
                    DetailRow("RMSSD", morningRead.nightlyRmssd?.toInt()?.toString() ?: "n/a")
                    DetailRow("Raw PPI", "${morningRead.rawPpiGoodEpochCount ?: 0} good epochs")
                }
            }
        }
    }
}
