@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

private enum class TimeEditorMode {
    DIAL,
    TYPE
}

@Composable
fun MarkerTimeEditorSheet(
    kind: MarkerTimeEditorKind,
    initialEpochMs: Long,
    onSave: (Long) -> Unit,
    onDismiss: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    nowEpochMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var value by remember(initialEpochMs) {
        mutableStateOf(TimeEditorValue.fromEpochMs(initialEpochMs, zoneId))
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SheetTitle(kind.title, "Set an exact time before syncing.")
            QuickActionRow(
                onNow = {
                    value = timeEditorValueForQuickAction(nowEpochMsProvider(), minutesAgo = 0, zoneId)
                },
                onFifteenAgo = {
                    value = timeEditorValueForQuickAction(nowEpochMsProvider(), minutesAgo = 15, zoneId)
                },
                onThirtyAgo = {
                    value = timeEditorValueForQuickAction(nowEpochMsProvider(), minutesAgo = 30, zoneId)
                }
            )
            InstantEditorControls(
                label = kind.instantLabel,
                value = value,
                onValueChange = { value = it }
            )
            DetailRow("Preview", markerPreviewLabel(value))
            ButtonRow {
                Button(onClick = { onSave(value.toEpochMs(zoneId)) }) {
                    Text(kind.saveLabel)
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun SleepWindowTimeEditorSheet(
    item: SleepEpisodeDisplayItem,
    onSave: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault(),
    nowEpochMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    val startEpochMs = item.startEpochMs ?: return
    val endEpochMs = item.endEpochMs ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var start by remember(item.id, startEpochMs) {
        mutableStateOf(TimeEditorValue.fromEpochMs(startEpochMs, zoneId))
    }
    var end by remember(item.id, endEpochMs) {
        mutableStateOf(TimeEditorValue.fromEpochMs(endEpochMs, zoneId))
    }
    val validation = validateWindowEditor(start, end, zoneId)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SheetTitle("Edit window", "Use explicit dates and exact times.")
            QuickActionRow(
                onNow = { end = timeEditorValueForQuickAction(nowEpochMsProvider(), minutesAgo = 0, zoneId) },
                onFifteenAgo = { end = timeEditorValueForQuickAction(nowEpochMsProvider(), minutesAgo = 15, zoneId) },
                onThirtyAgo = { end = timeEditorValueForQuickAction(nowEpochMsProvider(), minutesAgo = 30, zoneId) },
                prefix = "End"
            )
            InstantEditorControls(
                label = "Start",
                value = start,
                onValueChange = { start = it }
            )
            InstantEditorControls(
                label = "End",
                value = end,
                onValueChange = { end = it }
            )
            DetailRow("Duration", windowDurationLabel(start, end, zoneId))
            DetailRow("Preview", windowPreviewLabel(start, end, zoneId))
            validation.message?.let { message ->
                Text(
                    text = message,
                    color = if (validation.isWarning) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            ButtonRow {
                Button(
                    onClick = { onSave(start.toEpochMs(zoneId), end.toEpochMs(zoneId)) },
                    enabled = validation.canSave
                ) {
                    Text("Save window")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun ManualSleepWindowTimeEditorSheet(
    sourceDate: String,
    onSave: (Long, Long) -> Unit,
    onDismiss: () -> Unit,
    zoneId: ZoneId = ZoneId.systemDefault()
) {
    val parsedDate = remember(sourceDate) {
        runCatching { LocalDate.parse(sourceDate) }.getOrDefault(LocalDate.now(zoneId))
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var start by remember(sourceDate) {
        mutableStateOf(TimeEditorValue(date = parsedDate.minusDays(1), hour = 23, minute = 0))
    }
    var end by remember(sourceDate) {
        mutableStateOf(TimeEditorValue(date = parsedDate, hour = 7, minute = 0))
    }
    val validation = validateWindowEditor(start, end, zoneId)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SheetTitle("Add sleep window", "Create a user-confirmed main sleep window.")
            InstantEditorControls(
                label = "Start",
                value = start,
                onValueChange = { start = it }
            )
            InstantEditorControls(
                label = "End",
                value = end,
                onValueChange = { end = it }
            )
            DetailRow("Duration", windowDurationLabel(start, end, zoneId))
            DetailRow("Preview", windowPreviewLabel(start, end, zoneId))
            validation.message?.let { message ->
                Text(
                    text = message,
                    color = if (validation.isWarning) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
            ButtonRow {
                Button(
                    onClick = { onSave(start.toEpochMs(zoneId), end.toEpochMs(zoneId)) },
                    enabled = validation.canSave
                ) {
                    Text("Save sleep window")
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        SupportText(subtitle)
    }
}

@Composable
private fun QuickActionRow(
    onNow: () -> Unit,
    onFifteenAgo: () -> Unit,
    onThirtyAgo: () -> Unit,
    prefix: String? = null
) {
    val labelPrefix = prefix?.let { "$it " }.orEmpty()
    ButtonRow {
        OutlinedButton(onClick = onNow, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("${labelPrefix}now")
        }
        OutlinedButton(onClick = onFifteenAgo, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("${labelPrefix}15m ago")
        }
        OutlinedButton(onClick = onThirtyAgo, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            Text("${labelPrefix}30m ago")
        }
    }
}

@Composable
private fun InstantEditorControls(
    label: String,
    value: TimeEditorValue,
    onValueChange: (TimeEditorValue) -> Unit
) {
    var mode by remember { mutableStateOf(TimeEditorMode.DIAL) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        DateChipRow(
            selectedDate = value.date,
            onDateSelected = { onValueChange(value.copy(date = it)) }
        )
        TimeEditorModeRow(
            mode = mode,
            onModeChange = { mode = it }
        )
        MaterialTimeEditor(
            mode = mode,
            value = value,
            onValueChange = onValueChange
        )
    }
}

@Composable
private fun TimeEditorModeRow(
    mode: TimeEditorMode,
    onModeChange: (TimeEditorMode) -> Unit
) {
    ButtonRow {
        FilterChip(
            selected = mode == TimeEditorMode.DIAL,
            onClick = { onModeChange(TimeEditorMode.DIAL) },
            label = { Text("Dial") }
        )
        FilterChip(
            selected = mode == TimeEditorMode.TYPE,
            onClick = { onModeChange(TimeEditorMode.TYPE) },
            label = { Text("Type") }
        )
    }
}

@Composable
private fun MaterialTimeEditor(
    mode: TimeEditorMode,
    value: TimeEditorValue,
    onValueChange: (TimeEditorValue) -> Unit
) {
    val pickerState = rememberTimePickerState(
        initialHour = value.hour,
        initialMinute = value.minute,
        is24Hour = false
    )
    LaunchedEffect(value.hour, value.minute) {
        if (pickerState.hour != value.hour) {
            pickerState.hour = value.hour
        }
        if (pickerState.minute != value.minute) {
            pickerState.minute = value.minute
        }
    }
    LaunchedEffect(pickerState.hour, pickerState.minute) {
        if (pickerState.hour != value.hour || pickerState.minute != value.minute) {
            onValueChange(value.copy(hour = pickerState.hour, minute = pickerState.minute))
        }
    }
    when (mode) {
        TimeEditorMode.DIAL -> TimePicker(state = pickerState)
        TimeEditorMode.TYPE -> TimeInput(state = pickerState)
    }
}

@Composable
private fun DateChipRow(
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit
) {
    ButtonRow {
        listOf(selectedDate.minusDays(1), selectedDate, selectedDate.plusDays(1)).forEach { date ->
            FilterChip(
                selected = date == selectedDate,
                onClick = { onDateSelected(date) },
                label = { Text(formatDateChipLabel(date)) }
            )
        }
    }
}
