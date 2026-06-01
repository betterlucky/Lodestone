@file:OptIn(ExperimentalLayoutApi::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.data.WakeMarkerSources
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CandidateReviewSection(
    state: SleepEpisodeReviewState,
    wakeMarkers: List<WakeMarkerEntity>,
    activeAnalysisWindow: NowAnalysisWindowProvenance?,
    actionsEnabled: Boolean,
    onAcceptMainSleep: (Long) -> Unit,
    onAcceptNap: (Long) -> Unit,
    onMarkRest: (Long) -> Unit,
    onRejectCandidate: (Long) -> Unit,
    onClearDecision: (Long) -> Unit,
    onAddManualWindow: (String, Long, Long) -> Unit,
    onEditWindow: (Long, Long, Long) -> Unit,
    onEditMarker: (Long, String, Long, String) -> Unit,
    onMarkNoMainSleep: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<SleepEpisodeDisplayItem?>(null) }
    var addingWindowDate by remember { mutableStateOf<String?>(null) }
    var editingMarker by remember { mutableStateOf<MarkerEvidenceItem?>(null) }
    SectionCard(
        title = "Sleep/window evidence",
        subtitle = if (state.hasCatchUpDates) "Repair missing days from oldest to newest" else "Evidence and overrides for this read"
    ) {
        DetailRow("Active date", state.activeDate)
        activeAnalysisWindow?.let { window ->
            DetailRow("Active window", window.label)
            DetailRow("Window reason", window.reason)
        }
        DetailRow("Suggested", state.totalCandidateCount.toString())
        DetailRow("Confirmed", state.totalConfirmedCount.toString())
        if (state.hasCatchUpDates) {
            DetailRow("Review dates", state.dateGroups.size.toString())
            DetailRow("Needs attention", state.attentionDateCount.toString())
        }
        SupportText(state.surfaceMessage)
        if (!state.hasAnyRows) {
            SupportText("Check in again after the Loop has more data, add your own window, keep the day as TBC, or wait for the final Loop report.")
        }
        ButtonRow {
            OutlinedButton(
                onClick = { showDialog = true },
                enabled = state.dateGroups.isNotEmpty()
            ) {
                Text("Open evidence")
            }
        }
    }

    if (showDialog) {
        CandidateReviewDialog(
            state = state,
            wakeMarkers = wakeMarkers,
            activeAnalysisWindow = activeAnalysisWindow,
            actionsEnabled = actionsEnabled,
            onAcceptMainSleep = onAcceptMainSleep,
            onAcceptNap = onAcceptNap,
            onMarkRest = onMarkRest,
            onRejectCandidate = onRejectCandidate,
            onClearDecision = onClearDecision,
            onAddWindowRequested = {
                addingWindowDate = it
                showDialog = false
            },
            onEditRequested = {
                editingItem = it
                showDialog = false
            },
            onEditMarkerRequested = {
                editingMarker = it
                showDialog = false
            },
            onMarkNoMainSleep = onMarkNoMainSleep,
            onDismiss = { showDialog = false }
        )
    }

    addingWindowDate?.let { sourceDate ->
        ManualSleepWindowTimeEditorSheet(
            sourceDate = sourceDate,
            onSave = { start, end ->
                onAddManualWindow(sourceDateForEpoch(end), start, end)
                addingWindowDate = null
            },
            onDismiss = { addingWindowDate = null }
        )
    }

    editingItem?.let { item ->
        SleepWindowTimeEditorSheet(
            item = item,
            onSave = { start, end ->
                onEditWindow(item.id, start, end)
                editingItem = null
            },
            onDismiss = { editingItem = null }
        )
    }

    editingMarker?.let { marker ->
        MarkerTimeEditorSheet(
            kind = marker.editorKind,
            initialEpochMs = marker.markerEpochMs,
            onSave = { markerEpochMs ->
                onEditMarker(marker.id, sourceDateForEpoch(markerEpochMs), markerEpochMs, marker.markerSource)
                editingMarker = null
            },
            onDismiss = { editingMarker = null }
        )
    }
}

@Composable
private fun CandidateReviewDialog(
    state: SleepEpisodeReviewState,
    wakeMarkers: List<WakeMarkerEntity>,
    activeAnalysisWindow: NowAnalysisWindowProvenance?,
    actionsEnabled: Boolean,
    onAcceptMainSleep: (Long) -> Unit,
    onAcceptNap: (Long) -> Unit,
    onMarkRest: (Long) -> Unit,
    onRejectCandidate: (Long) -> Unit,
    onClearDecision: (Long) -> Unit,
    onAddWindowRequested: (String) -> Unit,
    onEditRequested: (SleepEpisodeDisplayItem) -> Unit,
    onEditMarkerRequested: (MarkerEvidenceItem) -> Unit,
    onMarkNoMainSleep: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep/window evidence") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SupportText(state.surfaceMessage)
                state.dateGroups.forEach { group ->
                    CandidateReviewDateGroup(
                        group = group,
                        markers = wakeMarkers.markerEvidenceFor(group.sourceDate),
                        activeAnalysisWindow = activeAnalysisWindow?.takeIf { it.sourceDate == group.sourceDate },
                        actionsEnabled = actionsEnabled,
                        onAcceptMainSleep = onAcceptMainSleep,
                        onAcceptNap = onAcceptNap,
                        onMarkRest = onMarkRest,
                        onRejectCandidate = onRejectCandidate,
                        onClearDecision = onClearDecision,
                        onAddWindowRequested = onAddWindowRequested,
                        onEditRequested = onEditRequested,
                        onEditMarkerRequested = onEditMarkerRequested,
                        onMarkNoMainSleep = onMarkNoMainSleep
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

@Composable
private fun CandidateReviewDateGroup(
    group: SleepEpisodeDateGroup,
    markers: List<MarkerEvidenceItem>,
    activeAnalysisWindow: NowAnalysisWindowProvenance?,
    actionsEnabled: Boolean,
    onAcceptMainSleep: (Long) -> Unit,
    onAcceptNap: (Long) -> Unit,
    onMarkRest: (Long) -> Unit,
    onRejectCandidate: (Long) -> Unit,
    onClearDecision: (Long) -> Unit,
    onAddWindowRequested: (String) -> Unit,
    onEditRequested: (SleepEpisodeDisplayItem) -> Unit,
    onEditMarkerRequested: (MarkerEvidenceItem) -> Unit,
    onMarkNoMainSleep: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            group.sourceDate,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        ButtonRow {
            LabelChip(group.repairStatusLabel)
            if (group.hasSavedReview) {
                LabelChip("Journal saved")
            }
        }
        SupportText(group.summaryLabel())
        if (group.isEmpty) {
            SupportText(group.emptyStateMessage)
        } else {
            group.items.forEach { item ->
                CandidateReviewWindow(
                    item = item,
                    actionsEnabled = actionsEnabled,
                    onAcceptMainSleep = onAcceptMainSleep,
                    onAcceptNap = onAcceptNap,
                    onMarkRest = onMarkRest,
                    onRejectCandidate = onRejectCandidate,
                    onClearDecision = onClearDecision,
                    onEditRequested = onEditRequested
                )
            }
        }
        if (activeAnalysisWindow != null) {
            EvidenceSummaryCard(
                title = "Active analysis window",
                primary = activeAnalysisWindow.label,
                secondary = activeAnalysisWindow.reason,
                chips = listOf(
                    activeAnalysisWindow.sourceType.name.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.UK) },
                    activeAnalysisWindow.timeRangeLabel,
                    activeAnalysisWindow.durationLabel
                )
            )
        }
        if (markers.isNotEmpty()) {
            Text("Manual markers", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            markers.forEach { marker ->
                MarkerEvidenceCard(
                    marker = marker,
                    actionsEnabled = actionsEnabled,
                    onEditMarkerRequested = onEditMarkerRequested
                )
            }
        }
        ButtonRow {
            TextButton(
                onClick = { onAddWindowRequested(group.sourceDate) },
                enabled = actionsEnabled
            ) {
                Text("Add window")
            }
            TextButton(
                onClick = { onMarkNoMainSleep(group.sourceDate) },
                enabled = actionsEnabled
            ) {
                Text("No main sleep")
            }
        }
    }
}

@Composable
private fun EvidenceSummaryCard(
    title: String,
    primary: String,
    secondary: String,
    chips: List<String>
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(primary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ButtonRow {
                chips.filter { it.isNotBlank() }.forEach { LabelChip(it) }
            }
            SupportText(secondary)
        }
    }
}

@Composable
private fun MarkerEvidenceCard(
    marker: MarkerEvidenceItem,
    actionsEnabled: Boolean,
    onEditMarkerRequested: (MarkerEvidenceItem) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.48f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(marker.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                LabelChip(marker.timeLabel)
            }
            ButtonRow {
                LabelChip(marker.sourceLabel)
                marker.notes?.takeIf { it.isNotBlank() }?.let { LabelChip(it) }
            }
            TextButton(
                onClick = { onEditMarkerRequested(marker) },
                enabled = actionsEnabled
            ) {
                Text("Edit marker")
            }
        }
    }
}

@Composable
private fun CandidateReviewWindow(
    item: SleepEpisodeDisplayItem,
    actionsEnabled: Boolean,
    onAcceptMainSleep: (Long) -> Unit,
    onAcceptNap: (Long) -> Unit,
    onMarkRest: (Long) -> Unit,
    onRejectCandidate: (Long) -> Unit,
    onClearDecision: (Long) -> Unit,
    onEditRequested: (SleepEpisodeDisplayItem) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.64f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                item.primaryLabel?.let { LabelChip(it) }
            }
            Text(
                "${item.timeRangeLabel} · ${item.durationLabel}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ButtonRow {
                LabelChip(item.kindLabel)
                LabelChip(item.sourceLabel)
                LabelChip(item.confidenceLabel)
            }
            SupportText(item.evidenceSummary)
            ButtonRow {
                if (item.isCandidate) {
                    TextButton(
                        onClick = { onAcceptMainSleep(item.id) },
                        enabled = actionsEnabled
                    ) {
                        Text("Use sleep")
                    }
                    TextButton(
                        onClick = { onAcceptNap(item.id) },
                        enabled = actionsEnabled
                    ) {
                        Text("Nap")
                    }
                    TextButton(
                        onClick = { onMarkRest(item.id) },
                        enabled = actionsEnabled
                    ) {
                        Text("Rest")
                    }
                    TextButton(
                        onClick = { onRejectCandidate(item.id) },
                        enabled = actionsEnabled
                    ) {
                        Text("Dismiss")
                    }
                }
                if (item.canClearDecision) {
                    TextButton(
                        onClick = { onClearDecision(item.id) },
                        enabled = actionsEnabled
                    ) {
                        Text(if (item.isNoSleep) "Undo no sleep" else "Reset")
                    }
                }
                if (!item.isNoSleep && item.startEpochMs != null && item.endEpochMs != null) {
                    TextButton(
                        onClick = { onEditRequested(item) },
                        enabled = actionsEnabled
                    ) {
                        Text("Edit")
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f), RoundedCornerShape(100.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun SleepEpisodeDateGroup.summaryLabel(): String =
    if (isEmpty) {
        "No sleep/rest candidates found yet"
    } else {
        buildString {
            append(candidateCount)
            append(" suggested, ")
            append(confirmedCount)
            append(" confirmed")
            if (hasSavedReview) append(", journal saved")
            if (hasPrimaryReadinessWindow) append(", current-signal window selected")
        }
    }

data class MarkerEvidenceItem(
    val id: Long,
    val sourceDate: String,
    val markerEpochMs: Long,
    val markerSource: String,
    val title: String,
    val timeLabel: String,
    val sourceLabel: String,
    val notes: String?,
    val editorKind: MarkerTimeEditorKind
)

private fun List<WakeMarkerEntity>.markerEvidenceFor(
    sourceDate: String,
    zoneId: ZoneId = ZoneId.systemDefault()
): List<MarkerEvidenceItem> =
    filter { marker ->
        marker.sourceDate == sourceDate &&
            marker.notes != "manual awake command" &&
            marker.markerSource in setOf(WakeMarkerSources.GOING_TO_BED, WakeMarkerSources.IM_AWAKE)
    }
        .sortedBy { it.markerEpochMs }
        .map { marker ->
            val isBedtime = marker.markerSource == WakeMarkerSources.GOING_TO_BED
            MarkerEvidenceItem(
                id = marker.id,
                sourceDate = marker.sourceDate,
                markerEpochMs = marker.markerEpochMs,
                markerSource = marker.markerSource,
                title = if (isBedtime) "Bedtime marker" else "Wake marker",
                timeLabel = marker.markerEpochMs.timeLabel(zoneId),
                sourceLabel = if (isBedtime) "Manual bedtime" else "Manual wake",
                notes = marker.notes,
                editorKind = if (isBedtime) MarkerTimeEditorKind.BEDTIME else MarkerTimeEditorKind.WAKING
            )
        }

private fun Long.timeLabel(zoneId: ZoneId): String =
    Instant.ofEpochMilli(this).atZone(zoneId).format(markerTimeFormatter)

private fun sourceDateForEpoch(epochMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zoneId).toLocalDate().toString()

private val markerTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.UK)
