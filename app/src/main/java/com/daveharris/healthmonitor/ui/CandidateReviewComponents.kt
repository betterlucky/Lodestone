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
import androidx.compose.material3.TextField
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

@Composable
fun CandidateReviewSection(
    state: SleepEpisodeReviewState,
    actionsEnabled: Boolean,
    onAcceptMainSleep: (Long) -> Unit,
    onAcceptNap: (Long) -> Unit,
    onMarkRest: (Long) -> Unit,
    onRejectCandidate: (Long) -> Unit,
    onClearDecision: (Long) -> Unit,
    onEditWindow: (Long, String, String) -> Unit,
    onMarkNoMainSleep: (String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<SleepEpisodeDisplayItem?>(null) }
    SectionCard(
        title = "Sleep/rest review",
        subtitle = if (state.hasCatchUpDates) "Review missing days from oldest to newest" else "Candidate state for today"
    ) {
        DetailRow("Active date", state.activeDate)
        DetailRow("Suggested", state.totalCandidateCount.toString())
        DetailRow("Confirmed", state.totalConfirmedCount.toString())
        if (state.hasCatchUpDates) {
            DetailRow("Review dates", state.dateGroups.size.toString())
            DetailRow("Needs attention", state.attentionDateCount.toString())
        }
        SupportText(state.surfaceMessage)
        if (!state.hasAnyRows) {
            SupportText("Check in again after the Loop has more data, keep the day as TBC, or wait for the final Loop report.")
        }
        ButtonRow {
            OutlinedButton(
                onClick = { showDialog = true },
                enabled = state.dateGroups.isNotEmpty()
            ) {
                Text(if (state.hasAnyRows) "Review windows" else "Review date")
            }
        }
    }

    if (showDialog) {
        CandidateReviewDialog(
            state = state,
            actionsEnabled = actionsEnabled,
            onAcceptMainSleep = onAcceptMainSleep,
            onAcceptNap = onAcceptNap,
            onMarkRest = onMarkRest,
            onRejectCandidate = onRejectCandidate,
            onClearDecision = onClearDecision,
            onEditRequested = {
                editingItem = it
                showDialog = false
            },
            onMarkNoMainSleep = onMarkNoMainSleep,
            onDismiss = { showDialog = false }
        )
    }

    editingItem?.let { item ->
        EditSleepWindowDialog(
            item = item,
            onSave = { start, end ->
                onEditWindow(item.id, start, end)
                editingItem = null
            },
            onDismiss = { editingItem = null }
        )
    }
}

@Composable
private fun CandidateReviewDialog(
    state: SleepEpisodeReviewState,
    actionsEnabled: Boolean,
    onAcceptMainSleep: (Long) -> Unit,
    onAcceptNap: (Long) -> Unit,
    onMarkRest: (Long) -> Unit,
    onRejectCandidate: (Long) -> Unit,
    onClearDecision: (Long) -> Unit,
    onEditRequested: (SleepEpisodeDisplayItem) -> Unit,
    onMarkNoMainSleep: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep/rest review") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SupportText(state.surfaceMessage)
                state.dateGroups.forEach { group ->
                    CandidateReviewDateGroup(
                        group = group,
                        actionsEnabled = actionsEnabled,
                        onAcceptMainSleep = onAcceptMainSleep,
                        onAcceptNap = onAcceptNap,
                        onMarkRest = onMarkRest,
                        onRejectCandidate = onRejectCandidate,
                        onClearDecision = onClearDecision,
                        onEditRequested = onEditRequested,
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
    actionsEnabled: Boolean,
    onAcceptMainSleep: (Long) -> Unit,
    onAcceptNap: (Long) -> Unit,
    onMarkRest: (Long) -> Unit,
    onRejectCandidate: (Long) -> Unit,
    onClearDecision: (Long) -> Unit,
    onEditRequested: (SleepEpisodeDisplayItem) -> Unit,
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
                LabelChip("Evening review saved")
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
        ButtonRow {
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
private fun EditSleepWindowDialog(
    item: SleepEpisodeDisplayItem,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var startInput by remember(item.id) { mutableStateOf(item.startInputLabel) }
    var endInput by remember(item.id) { mutableStateOf(item.endInputLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit window") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = startInput,
                    onValueChange = { startInput = it },
                    label = { Text("Start") },
                    singleLine = true
                )
                TextField(
                    value = endInput,
                    onValueChange = { endInput = it },
                    label = { Text("End") },
                    singleLine = true
                )
                SupportText("Use yyyy-MM-dd HH:mm.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(startInput, endInput) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
            if (hasSavedReview) append(", review saved")
            if (hasPrimaryReadinessWindow) append(", readiness window selected")
        }
    }
