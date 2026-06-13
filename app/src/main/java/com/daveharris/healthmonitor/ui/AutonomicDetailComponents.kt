package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.AutonomicDetailScope
import com.daveharris.healthmonitor.data.AutonomicPlanningInfluence
import com.daveharris.healthmonitor.data.AutonomicRecoveryWindow
import com.daveharris.healthmonitor.data.AutonomicScopeFamily
import com.daveharris.healthmonitor.data.AutonomicScopeResolver
import com.daveharris.healthmonitor.data.AutonomicScopeSummary
import com.daveharris.healthmonitor.data.ClosedEpochMsRange
import com.daveharris.healthmonitor.data.HrvTrajectoryPoint
import com.daveharris.healthmonitor.data.Ppi247EpochEntity
import com.daveharris.healthmonitor.data.SleepEpisodeEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun NowAnalysisWindowProvenance.toAutonomicRecoveryWindow(): AutonomicRecoveryWindow? {
    val start = startEpochMs ?: return null
    val end = endEpochMs ?: return null
    if (end <= start) return null
    return AutonomicRecoveryWindow(
        startEpochMs = start,
        endEpochMs = end,
        provenanceLabel = label,
        sourceDate = sourceDate
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutonomicDetailDialog(
    recoveryWindow: AutonomicRecoveryWindow?,
    epochs: List<Ppi247EpochEntity>,
    sleepEpisodes: List<SleepEpisodeEntity>,
    lastPpiSyncEpochMs: Long?,
    initialScope: AutonomicDetailScope = AutonomicDetailScope.ACTIVE_SLEEP_REST,
    onDismiss: () -> Unit
) {
    var selectedScope by remember { mutableStateOf(initialScope) }
    val summary = remember(selectedScope, epochs, recoveryWindow, sleepEpisodes, lastPpiSyncEpochMs) {
        AutonomicScopeResolver.resolve(
            scope = selectedScope,
            epochs = epochs,
            recoveryWindow = recoveryWindow,
            sleepEpisodes = sleepEpisodes,
            lastPpiSyncEpochMs = lastPpiSyncEpochMs
        )
    }
    val title = when (summary.family) {
        AutonomicScopeFamily.RECOVERY_TRAJECTORY -> "Sleep/rest HRV"
        AutonomicScopeFamily.RECENT_TREND -> "Recent autonomic trend"
    }
    val subtitle = when (summary.family) {
        AutonomicScopeFamily.RECOVERY_TRAJECTORY ->
            listOfNotNull(summary.provenanceLabel, autonomicWindowDurationLabel(summary)).joinToString(" · ")
        AutonomicScopeFamily.RECENT_TREND ->
            "Past ${selectedScope.lookbackHours} hours · descriptive only"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AutonomicScopeSelector(
                    selectedScope = selectedScope,
                    onScopeSelected = { selectedScope = it }
                )
                BannerNote(
                    text = summary.familyBanner,
                    tint = if (summary.planningInfluence == AutonomicPlanningInfluence.DESCRIPTIVE_ONLY) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    },
                    textColor = if (summary.planningInfluence == AutonomicPlanningInfluence.DESCRIPTIVE_ONLY) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
                summary.cautionLines.forEach { line ->
                    SupportText(line)
                }
                if (summary.emptyStateMessage != null) {
                    SupportText(summary.emptyStateMessage)
                } else {
                    AutonomicTrajectoryChart(
                        points = summary.trajectoryPoints,
                        sleepRestShading = summary.sleepRestShading
                    )
                    AutonomicScopeQualityPanel(summary)
                    if (summary.showShapeSummary) {
                        AutonomicShapeSummary(
                            points = summary.trajectoryPoints,
                            indicative = summary.shapeSummaryIndicative,
                            showStrainLabels = summary.showStrainLabels
                        )
                    }
                }
                summary.alternateEpisodeLink?.let { link ->
                    SupportText(link.label)
                }
                SupportText("Faint line = raw RMSSD, bold line = rolling median, straight line = linear trend. Qualitative context, not a diagnosis.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutonomicScopeSelector(
    selectedScope: AutonomicDetailScope,
    onScopeSelected: (AutonomicDetailScope) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {
        OutlinedTextField(
            value = selectedScope.userLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Scope") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            AutonomicDetailScope.selectorOrder.forEach { scope ->
                DropdownMenuItem(
                    text = { Text(scope.userLabel) },
                    onClick = {
                        onScopeSelected(scope)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AutonomicScopeQualityPanel(summary: AutonomicScopeSummary) {
    val quality = summary.quality
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DetailRow("Scope", summary.scope.userLabel)
        DetailRow("Window", autonomicWindowRangeLabel(summary))
        summary.provenanceLabel?.let { DetailRow("Provenance", it) }
        DetailRow("Summary epochs", "${quality.summaryEpochCount} (${quality.goodEpochCount} good, ${quality.usableEpochCount} usable)")
        if (quality.reviewEpochCount > 0) {
            DetailRow("Review epochs", quality.reviewEpochCount.toString())
        }
        if (quality.poorEpochCount > 0) {
            DetailRow("Poor epochs", quality.poorEpochCount.toString())
        }
        DetailRow("Coverage", "${quality.coveragePercent}%")
        DetailRow("Longest gap", "${quality.longestGapMinutes} min")
        if (summary.scope.isRolling && quality.excludedMotionContactCount > 0) {
            DetailRow("Excluded (motion/contact)", quality.excludedMotionContactCount.toString())
        }
        quality.ppiFreshnessMinutes?.let { minutes ->
            DetailRow("PPI freshness", "PPI synced $minutes min ago")
        }
        if (summary.sleepRestShading?.isNotEmpty() == true) {
            SupportText("Shaded = recorded sleep/rest · Unshaded = wake or mixed")
        }
    }
}

@Composable
private fun AutonomicShapeSummary(
    points: List<HrvTrajectoryPoint>,
    indicative: Boolean,
    showStrainLabels: Boolean
) {
    val average = points.map { it.rmssdMs }.averageOrNull()
    val early = points.take((points.size / 3).coerceAtLeast(1)).map { it.rmssdMs }.averageOrNull()
    val late = points.takeLast((points.size / 3).coerceAtLeast(1)).map { it.rmssdMs }.averageOrNull()
    val delta = if (early != null && late != null) late - early else null
    val trend = hrvTrendSummary(points)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (indicative) {
            SupportText("Early-late shape is indicative only for this short window.")
        }
        DetailRow("Average RMSSD", average?.let { "${it.toInt()} ms" } ?: "n/a")
        DetailRow("Early -> late", delta?.let { formatSignedMs(it) } ?: "n/a")
        DetailRow("Shape", trend.shapeLabel)
        DetailRow("Linear trend", formatSignedMs(trend.linearDeltaMs))
        if (showStrainLabels) {
            SupportText("Strain/momentum labels still come from the active sleep/rest scope only.")
        }
    }
}

@Composable
private fun AutonomicTrajectoryChart(
    points: List<HrvTrajectoryPoint>,
    sleepRestShading: List<ClosedEpochMsRange>?
) {
    val rawLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    val smoothLineColor = MaterialTheme.colorScheme.primary
    val trendLineColor = MaterialTheme.colorScheme.tertiary
    val guideColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val shadeColor = MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
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
    val chartStart = sleepRestShading?.minOfOrNull { it.startEpochMs } ?: firstTime
    val chartEnd = sleepRestShading?.maxOfOrNull { it.endEpochMs } ?: lastTime
    val chartSpan = (chartEnd - chartStart).coerceAtLeast(1L).toFloat()

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
        sleepRestShading.orEmpty().forEach { range ->
            val startX = left + ((range.startEpochMs - chartStart).toFloat() / chartSpan) * (right - left)
            val endX = left + ((range.endEpochMs - chartStart).toFloat() / chartSpan) * (right - left)
            drawRect(
                color = shadeColor,
                topLeft = Offset(startX.coerceIn(left, right), top),
                size = androidx.compose.ui.geometry.Size(
                    width = (endX - startX).coerceAtLeast(0f),
                    height = bottom - top
                )
            )
        }
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            drawLine(
                color = guideColor,
                start = Offset(left, y),
                end = Offset(right, y),
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
            start = Offset(left, trendStartY),
            end = Offset(right, trendEndY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        val startLabel = formatAutonomicEpochTime(firstTime)
        val endLabel = formatAutonomicEpochTime(lastTime)
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

private fun autonomicWindowRangeLabel(summary: AutonomicScopeSummary): String {
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    val zone = ZoneId.systemDefault()
    val start = formatter.format(Instant.ofEpochMilli(summary.windowStartEpochMs).atZone(zone))
    val end = formatter.format(Instant.ofEpochMilli(summary.windowEndEpochMs).atZone(zone))
    return "$start–$end"
}

private fun autonomicWindowDurationLabel(summary: AutonomicScopeSummary): String {
    val minutes = summary.quality.windowDurationMinutes
    return when {
        minutes >= 60 -> {
            val hours = minutes / 60
            val remainder = minutes % 60
            if (remainder == 0) "${hours}h" else "${hours}h ${remainder}m"
        }
        else -> "${minutes}m"
    }
}

private fun formatAutonomicEpochTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

private fun formatSignedMs(value: Double): String {
    val sign = if (value >= 0) "+" else "-"
    return "$sign${kotlin.math.abs(value).toInt()} ms"
}
