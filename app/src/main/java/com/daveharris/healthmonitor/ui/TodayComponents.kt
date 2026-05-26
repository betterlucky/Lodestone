@file:OptIn(ExperimentalLayoutApi::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.HrvTrajectoryPoint
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class TodayReadinessStage {
    SLEEP_TIME,
    STARTING_SYNC,
    INITIAL_PPI,
    UPDATE_COMPLETE,
    NOT_STARTED
}

enum class TodayDataQualityState {
    READY,
    WAITING,
    PARTIAL
}

data class TodayDataQualitySummary(
    val state: TodayDataQualityState,
    val label: String,
    val missingInputs: List<String>,
    val supportingGaps: List<String>
)

data class TodayReadinessStatus(
    val stage: TodayReadinessStage,
    val title: String,
    val sleepReport: String,
    val ppiReceipt: String,
    val message: String,
    val hrvDetail: String,
    val dataQuality: TodayDataQualitySummary,
    val connectionPrompt: String? = null,
    val heroPrompt: String? = null
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
    val dataQuality = todayDataQualitySummary(
        stage = when {
            isSleeping -> TodayReadinessStage.SLEEP_TIME
            syncRunning -> TodayReadinessStage.STARTING_SYNC
            hasFinalSleep -> TodayReadinessStage.UPDATE_COMPLETE
            hasPpi -> TodayReadinessStage.INITIAL_PPI
            else -> TodayReadinessStage.NOT_STARTED
        },
        morningRead = relevantMorningRead,
        hasFinalSleep = hasFinalSleep,
        hasPpi = hasPpi
    )

    return when {
        isSleeping -> TodayReadinessStatus(
            stage = TodayReadinessStage.SLEEP_TIME,
            title = "Sleep time",
            sleepReport = "Cleared for tonight",
            ppiReceipt = "Waiting for wake sync",
            message = "Bedtime is marked. Today's old read is hidden until you wake and sync again.",
            hrvDetail = "Sleep mode is active. Overnight HRV detail will appear after you tap I'm awake and Lodestone syncs the Loop.",
            dataQuality = dataQuality
        )
        syncRunning -> TodayReadinessStatus(
            stage = TodayReadinessStage.STARTING_SYNC,
            title = "Starting sync",
            sleepReport = "Checking Loop",
            ppiReceipt = "Checking Loop",
            message = "Lodestone is connecting and pulling the morning core data.",
            hrvDetail = "Sync is running. PPI detail will appear as soon as the Loop returns enough data.",
            dataQuality = dataQuality,
            connectionPrompt = "Keep the phone close to the Loop until PPI finishes. If Bluetooth drops, Lodestone will retry instead of storing duplicate data.",
            heroPrompt = "Stay near Loop"
        )
        hasFinalSleep -> TodayReadinessStatus(
            stage = TodayReadinessStage.UPDATE_COMPLETE,
            title = "Update complete",
            sleepReport = "Final report present",
            ppiReceipt = ppiReceiptLabel(relevantMorningRead),
            message = "The final sleep report and morning signal are ready.",
            hrvDetail = "Raw PPI has been aligned to the resolved Loop sleep window.",
            dataQuality = dataQuality
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
            } else if (relevantMorningRead.overnightAutonomicSource == "raw_ppi_calibrated_window_pending_sleep_report") {
                "The interim morning signal is using Lodestone's calibrated onset estimate and your wake marker while Polar's final sleep report is pending."
            } else {
                "The interim morning signal can use manual bed/wake timing, but treat it as provisional until the final sleep report arrives."
            },
            dataQuality = dataQuality
        )
        else -> TodayReadinessStatus(
            stage = TodayReadinessStage.NOT_STARTED,
            title = "Awaiting morning sync",
            sleepReport = "Not synced yet",
            ppiReceipt = "Not received yet",
            message = "Tap I'm awake when you are ready to mark wake time and pull the morning data.",
            hrvDetail = "The raw overnight signal is stored from normal sync, but there is no current-day PPI or resolved sleep-window alignment yet.",
            dataQuality = dataQuality
        )
    }
}

fun todayDataQualitySummary(
    stage: TodayReadinessStage,
    morningRead: MorningReadSnapshot?,
    hasFinalSleep: Boolean = morningRead?.sleepDataReady == true,
    hasPpi: Boolean = morningRead?.rawPpiGoodEpochCount != null ||
        morningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true
): TodayDataQualitySummary {
    val coreMissing = buildList {
        if (!hasFinalSleep) add("Final Loop sleep report")
        if (!hasPpi) add("24/7 PPI epochs")
    }
    val supportingGaps = buildList {
        if (morningRead == null) {
            add("Morning-read snapshot")
        } else {
            if (morningRead.nightlyRmssd == null) add("Nightly Recharge RMSSD")
            if ((morningRead.rawPpiCoverageHours ?: 0.0) < 4.0 && hasPpi) add("Long PPI coverage window")
            if (!morningRead.baselineReady) add("Personal baseline")
        }
    }
    return when {
        stage == TodayReadinessStage.SLEEP_TIME || stage == TodayReadinessStage.STARTING_SYNC ->
            TodayDataQualitySummary(TodayDataQualityState.WAITING, "Waiting", coreMissing, supportingGaps)
        coreMissing.isEmpty() ->
            TodayDataQualitySummary(
                state = if (supportingGaps.isEmpty()) TodayDataQualityState.READY else TodayDataQualityState.PARTIAL,
                label = if (supportingGaps.isEmpty()) "Ready" else "Ready, supporting gaps",
                missingInputs = emptyList(),
                supportingGaps = supportingGaps
            )
        hasPpi || hasFinalSleep ->
            TodayDataQualitySummary(TodayDataQualityState.PARTIAL, "Partial", coreMissing, supportingGaps)
        else ->
            TodayDataQualitySummary(TodayDataQualityState.WAITING, "Waiting", coreMissing, supportingGaps)
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
                    todayStatus.heroPrompt?.let { HeroPill(it) }
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Report state", todayStatus.sleepReport)
                    DetailRow("PPI", todayStatus.ppiReceipt)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Prediction", morningRead.status?.let { labelForStatus(it.name) } ?: "TBC")
                    DetailRow("Report state", morningReadReportStateLabel(morningRead))
                    DetailRow("Basis", morningReadBasisLabel(morningRead, todayStatus))
                    DetailRow("Confidence", morningRead.confidence.replaceFirstChar { it.titlecase() })
                }
                morningRead.reasons.take(3).forEach { reason ->
                    Text("* $reason", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Date", morningRead.sourceDate ?: "unknown")
                    DetailRow("Autonomic source", autonomicSourceDisplayLabel(morningRead.overnightAutonomicSource))
                    DetailRow("Sleep", formatDurationMinutes(morningRead.sleepDurationMinutes))
                    DetailRow("RMSSD", morningRead.nightlyRmssd?.toInt()?.toString() ?: "n/a")
                    DetailRow("Raw PPI", "${morningRead.rawPpiGoodEpochCount ?: 0} good epochs")
                }
            }
        }
    }
}

fun morningReadBasisLabel(
    morningRead: MorningReadSnapshot?,
    todayStatus: TodayReadinessStatus
): String =
    when {
        morningRead?.overnightAutonomicSource == "raw_ppi_calibrated_window_pending_sleep_report" ->
            "Provisional calibrated sleep window + PPI"
        morningRead?.overnightAutonomicSource == "raw_ppi_manual_window_pending_sleep_report" ->
            "Provisional manual sleep window + PPI"
        morningRead?.overnightAutonomicSource == "raw_ppi_inferred_window_pending_sleep_report" ->
            "Provisional PPI-inferred sleep window"
        morningRead?.overnightAutonomicSource == "raw_ppi_calibrated_window_primary_with_sleep_report" ->
            "Calibrated sleep window + PPI, Loop report as context"
        morningRead?.overnightAutonomicSource == "raw_ppi_manual_window_primary_with_sleep_report" ->
            "Manual sleep window + PPI, Loop report as context"
        morningRead?.overnightAutonomicSource == "raw_ppi_inferred_window_primary_with_sleep_report" ->
            "PPI-inferred sleep window, Loop report as context"
        morningRead?.sleepDataReady == true && morningRead.hasPpiSignal() ->
            "Confirmed Loop sleep report + aligned PPI"
        morningRead?.sleepDataReady == true ->
            "Confirmed Loop sleep report"
        morningRead?.isInterim == true ->
            "Provisional morning data"
        todayStatus.stage == TodayReadinessStage.SLEEP_TIME ->
            "Waiting for wake sync"
        else ->
            "Waiting for morning data"
    }

private fun MorningReadSnapshot.hasPpiSignal(): Boolean =
    overnightAutonomicSource.contains("ppi", ignoreCase = true) ||
        (rawPpiGoodEpochCount ?: 0) > 0

private fun morningReadReportStateLabel(morningRead: MorningReadSnapshot): String =
    when {
        morningRead.sleepDataReady -> "Confirmed final Loop report"
        morningRead.isInterim -> "Provisional estimate"
        else -> "Pending"
    }

private fun autonomicSourceDisplayLabel(source: String): String =
    when (source) {
        "ppi247_sleep_window" -> "24/7 PPI aligned to sleep"
        "raw_ppi_calibrated_window_pending_sleep_report" -> "24/7 PPI, calibrated provisional window"
        "raw_ppi_manual_window_pending_sleep_report" -> "24/7 PPI, manual provisional window"
        "raw_ppi_inferred_window_pending_sleep_report" -> "24/7 PPI, inferred provisional window"
        "raw_ppi_calibrated_window_primary_with_sleep_report" -> "24/7 PPI, calibrated primary window"
        "raw_ppi_manual_window_primary_with_sleep_report" -> "24/7 PPI, manual primary window"
        "raw_ppi_inferred_window_primary_with_sleep_report" -> "24/7 PPI, inferred primary window"
        "raw_ppi_pending_manual_sleep_window" -> "24/7 PPI, waiting for bedtime marker"
        "raw_ppi_pending_sleep_window" -> "24/7 PPI, waiting for final sleep window"
        "nightly_recharge_summary" -> "Nightly Recharge summary"
        "sleep_context_only" -> "Sleep/context only"
        "awaiting_sleep_data" -> "Awaiting sleep data"
        else -> source
    }

@Composable
fun HrvTrajectoryDialog(
    morningRead: MorningReadSnapshot,
    onDismiss: () -> Unit
) {
    val points = morningRead.hrvTrajectory.sortedBy { it.epochStartEpochMs }
    val average = points.map { it.rmssdMs }.averageOrNull()
    val early = points.take((points.size / 3).coerceAtLeast(1)).map { it.rmssdMs }.averageOrNull()
    val late = points.takeLast((points.size / 3).coerceAtLeast(1)).map { it.rmssdMs }.averageOrNull()
    val delta = if (early != null && late != null) late - early else null
    val trend = hrvTrendSummary(points)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Overnight HRV trajectory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    morningReadBasisLabel(
                        morningRead = morningRead,
                        todayStatus = TodayReadinessStatus(
                            stage = TodayReadinessStage.UPDATE_COMPLETE,
                            title = "",
                            sleepReport = "",
                            ppiReceipt = "",
                            message = "",
                            hrvDetail = "",
                            dataQuality = todayDataQualitySummary(TodayReadinessStage.UPDATE_COMPLETE, morningRead)
                        )
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HrvTrajectoryChart(points = points)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Windows", points.size.toString())
                    DetailRow("Average RMSSD", average?.let { "${it.toInt()} ms" } ?: "n/a")
                    DetailRow("Early -> late", delta?.let { formatSignedMs(it) } ?: "n/a")
                    DetailRow("Shape", trend.shapeLabel)
                    DetailRow("Linear trend", formatSignedMs(trend.linearDeltaMs))
                    DetailRow("Range", hrvRangeLabel(points))
                    DetailRow("Time span", hrvTimeSpanLabel(points))
                }
                SupportText("Faint line = raw RMSSD, bold line = rolling median, straight line = linear trend. This is qualitative for now, not a diagnosis.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun HrvTrajectoryChart(points: List<HrvTrajectoryPoint>) {
    val rawLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    val smoothLineColor = MaterialTheme.colorScheme.primary
    val trendLineColor = MaterialTheme.colorScheme.tertiary
    val guideColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val values = points.map { it.rmssdMs }
    val minValue = values.minOrNull() ?: 0.0
    val maxValue = values.maxOrNull() ?: 1.0
    val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val paddedMin = (minValue - span * 0.12).coerceAtLeast(0.0)
    val paddedMax = maxValue + span * 0.12
    val firstTime = points.firstOrNull()?.epochStartEpochMs ?: 0L
    val lastTime = points.lastOrNull()?.epochStartEpochMs ?: firstTime + 1L
    val timeSpan = (lastTime - firstTime).coerceAtLeast(1L).toFloat()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fillColor)
            .padding(8.dp)
    ) {
        val left = 18.dp.toPx()
        val right = size.width - 10.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            drawLine(
                color = guideColor,
                start = androidx.compose.ui.geometry.Offset(left, y),
                end = androidx.compose.ui.geometry.Offset(right, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        if (points.size < 2) return@Canvas
        val rawPath = hrvPath(points, paddedMin, paddedMax, firstTime, timeSpan, left, right, top, bottom)
        drawPath(
            path = rawPath,
            color = rawLineColor,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )
        val smoothPoints = rollingMedianPoints(points, windowSize = 5)
        val smoothPath = hrvPath(smoothPoints, paddedMin, paddedMax, firstTime, timeSpan, left, right, top, bottom)
        drawPath(
            path = smoothPath,
            color = smoothLineColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        val trend = hrvTrendSummary(points)
        val trendStartY = valueToChartY(trend.startValueMs, paddedMin, paddedMax, top, bottom)
        val trendEndY = valueToChartY(trend.endValueMs, paddedMin, paddedMax, top, bottom)
        drawLine(
            color = trendLineColor.copy(alpha = 0.74f),
            start = androidx.compose.ui.geometry.Offset(left, trendStartY),
            end = androidx.compose.ui.geometry.Offset(right, trendEndY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        val startLabel = formatEpochTime(firstTime)
        val endLabel = formatEpochTime(lastTime)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = 11.sp.toPx()
                isAntiAlias = true
            }
            drawText(startLabel, left, size.height - 8.dp.toPx(), paint)
            drawText(endLabel, right - measureText(endLabel, paint), size.height - 8.dp.toPx(), paint)
        }
    }
}

private fun measureText(text: String, paint: android.graphics.Paint): Float =
    paint.measureText(text)

private data class HrvTrendSummary(
    val startValueMs: Double,
    val endValueMs: Double,
    val linearDeltaMs: Double,
    val shapeLabel: String
)

private fun hrvPath(
    points: List<HrvTrajectoryPoint>,
    minValue: Double,
    maxValue: Double,
    firstTime: Long,
    timeSpan: Float,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float
): Path {
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = left + (right - left) * ((point.epochStartEpochMs - firstTime).toFloat() / timeSpan)
        val y = valueToChartY(point.rmssdMs, minValue, maxValue, top, bottom)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

private fun valueToChartY(
    value: Double,
    minValue: Double,
    maxValue: Double,
    top: Float,
    bottom: Float
): Float {
    val yRatio = ((value - minValue) / (maxValue - minValue)).toFloat().coerceIn(0f, 1f)
    return bottom - (bottom - top) * yRatio
}

private fun rollingMedianPoints(
    points: List<HrvTrajectoryPoint>,
    windowSize: Int
): List<HrvTrajectoryPoint> {
    if (points.size <= 2) return points
    val halfWindow = windowSize / 2
    return points.mapIndexed { index, point ->
        val start = (index - halfWindow).coerceAtLeast(0)
        val endExclusive = (index + halfWindow + 1).coerceAtMost(points.size)
        val median = points.subList(start, endExclusive).map { it.rmssdMs }.medianOrNull() ?: point.rmssdMs
        point.copy(rmssdMs = median)
    }
}

private fun hrvTrendSummary(points: List<HrvTrajectoryPoint>): HrvTrendSummary {
    if (points.size < 2) {
        val value = points.firstOrNull()?.rmssdMs ?: 0.0
        return HrvTrendSummary(value, value, 0.0, "Not enough data")
    }
    val firstTime = points.first().epochStartEpochMs.toDouble()
    val lastTime = points.last().epochStartEpochMs.toDouble()
    val timeSpan = (lastTime - firstTime).takeIf { it > 0.0 } ?: 1.0
    val xValues = points.map { (it.epochStartEpochMs.toDouble() - firstTime) / timeSpan }
    val yValues = points.map { it.rmssdMs }
    val meanX = xValues.average()
    val meanY = yValues.average()
    val denominator = xValues.sumOf { (it - meanX) * (it - meanX) }
    val slope = if (denominator == 0.0) {
        0.0
    } else {
        xValues.zip(yValues).sumOf { (x, y) -> (x - meanX) * (y - meanY) } / denominator
    }
    val intercept = meanY - slope * meanX
    val startValue = intercept
    val endValue = intercept + slope
    val smooth = rollingMedianPoints(points, windowSize = 5)
    val thirdSize = (smooth.size / 3).coerceAtLeast(1)
    val early = smooth.take(thirdSize).map { it.rmssdMs }.averageOrNull()
    val middleStart = ((smooth.size - thirdSize) / 2).coerceAtLeast(0)
    val middle = smooth.drop(middleStart).take(thirdSize).map { it.rmssdMs }.averageOrNull()
    val late = smooth.takeLast(thirdSize).map { it.rmssdMs }.averageOrNull()
    val shape = hrvShapeLabel(early, middle, late, endValue - startValue)
    return HrvTrendSummary(startValue, endValue, endValue - startValue, shape)
}

private fun hrvShapeLabel(
    early: Double?,
    middle: Double?,
    late: Double?,
    linearDelta: Double
): String {
    if (early == null || middle == null || late == null) return "Mixed"
    val minEdge = minOf(early, late)
    val maxEdge = maxOf(early, late)
    val threshold = 8.0
    return when {
        middle <= minEdge - threshold -> "Dip then recovery"
        middle >= maxEdge + threshold -> "Mid-night peak"
        linearDelta >= threshold -> "Rising"
        linearDelta <= -threshold -> "Falling"
        else -> "Mostly flat / mixed"
    }
}

private fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else average()

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

private fun hrvRangeLabel(points: List<HrvTrajectoryPoint>): String {
    val values = points.map { it.rmssdMs }
    val min = values.minOrNull()?.toInt() ?: return "n/a"
    val max = values.maxOrNull()?.toInt() ?: return "n/a"
    return "$min-$max ms"
}

private fun hrvTimeSpanLabel(points: List<HrvTrajectoryPoint>): String {
    val first = points.firstOrNull()?.epochStartEpochMs ?: return "n/a"
    val last = points.lastOrNull()?.epochStartEpochMs ?: return "n/a"
    return "${formatEpochTime(first)}-${formatEpochTime(last)}"
}

private fun formatEpochTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.UK)
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
