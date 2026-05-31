@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

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
            SheetTitle(kind.title, "Set an exact 24-hour time before syncing.")
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
            SheetTitle("Edit window", "Use explicit dates and 24-hour time.")
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        DateChipRow(
            selectedDate = value.date,
            onDateSelected = { onValueChange(value.copy(date = it)) }
        )
        Text("Hour", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            (0 until 24).forEach { hour ->
                FilterChip(
                    selected = value.hour == hour,
                    onClick = { onValueChange(value.copy(hour = hour)) },
                    label = { Text(hour.twoDigits()) }
                )
            }
        }
        MinuteField(
            minute = value.minute,
            onMinuteChange = { onValueChange(value.copy(minute = it)) }
        )
    }
}

@Composable
private fun MinuteField(
    minute: Int,
    onMinuteChange: (Int) -> Unit
) {
    var minuteText by remember { mutableStateOf(minute.twoDigits()) }
    LaunchedEffect(minute) {
        if (minuteText.toIntOrNull() != minute) {
            minuteText = minute.twoDigits()
        }
    }
    OutlinedTextField(
        value = minuteText,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(2)
            minuteText = digits
            val parsed = digits.toIntOrNull()
            if (parsed != null && parsed in 0..59) {
                onMinuteChange(parsed)
            }
        },
        label = { Text("Minute") },
        supportingText = { Text("00-59") },
        singleLine = true,
        isError = minuteText.toIntOrNull()?.let { it !in 0..59 } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
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
