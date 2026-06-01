@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.JournalMajorTaskTypes
import com.daveharris.healthmonitor.data.TrafficLightStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun reviewFoodImportSummary(
    summary: FoodDailySummaryEntity?,
    weight: DailyWeightEntity?
): String {
    if (summary == null && weight == null) return "Not synced"
    val parts = buildList {
        if (summary != null) {
            val calories = summary.totalCaloriesKcal?.let { "$it kcal" }
            val events = summary.eventCount?.let { "$it items" }
            add(listOfNotNull(calories, events).joinToString(", ").ifBlank { "food synced" })
        }
        if (weight != null) {
            add(String.format(java.util.Locale.UK, "%.1f kg", weight.weightKg))
        }
    }
    return "Synced: ${parts.joinToString("; ")}"
}

private fun majorTaskTypeLabel(value: String?): String? =
    when (value) {
        JournalMajorTaskTypes.WORK_FROM_HOME -> "Work from home"
        JournalMajorTaskTypes.SITE_VISIT -> "Site visit"
        JournalMajorTaskTypes.ADMIN_ASSESSMENT -> "Admin / assessment"
        JournalMajorTaskTypes.OTHER_MAJOR_TASK -> "Other major task"
        else -> null
    }

private fun reviewDayShapeSummary(checkIn: DailyCheckInEntity): String? {
    if (checkIn.dayShapeCaptured != true) return null
    val parts = buildList {
        if (checkIn.mostlyHorizontal == true) add("Mostly horizontal")
        if (checkIn.leftHouse == true) add("Left the house")
        if (checkIn.majorTask == true) add(majorTaskTypeLabel(checkIn.majorTaskType) ?: "Work / major task")
        if (checkIn.pemPaybackToday == true) add("PEM / payback")
        if (checkIn.paybackPeakToday == true) add("Payback peak")
    }
    return if (parts.isEmpty()) "None marked" else parts.joinToString(" · ")
}

@Composable
fun FoodSection(
    foodSummary: FoodDailySummaryEntity?,
    weight: DailyWeightEntity?,
    onSyncFood: () -> Unit,
    onChooseFile: () -> Unit,
    isBusy: Boolean
) {
    var expanded by remember { mutableStateOf(false) }
    val summaryText = when {
        foodSummary != null && weight != null -> "${foodSummary.totalCaloriesKcal ?: "n/a"} kcal · ${foodSummary.eventCount ?: "n/a"} items · ${
            String.format(java.util.Locale.UK, "%.1f kg", weight.weightKg)
        }"
        foodSummary != null -> "${foodSummary.totalCaloriesKcal ?: "n/a"} kcal · ${foodSummary.eventCount ?: "n/a"} items"
        weight != null -> "Weight: ${String.format(java.util.Locale.UK, "%.1f kg", weight.weightKg)}"
        else -> "No food log synced for this date"
    }

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Food & weight", fontWeight = FontWeight.SemiBold)
                    Text(summaryText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }, enabled = !isBusy) {
                    Text(if (expanded) "Less" else "More")
                }
            }
            if (expanded) {
                if (foodSummary != null || weight != null) {
                    FoodSummaryCard(summary = foodSummary, weight = weight)
                }
                ButtonRow {
                    Button(onClick = onSyncFood, enabled = !isBusy) {
                        Text("Sync food log")
                    }
                    OutlinedButton(onClick = onChooseFile, enabled = !isBusy) {
                        Text("Choose file")
                    }
                }
                SupportText("Saving the check-in also tries to import the FoodLogData CSV for this date.")
            }
        }
    }
}

@Composable
fun ReviewHistoryItem(
    checkIn: DailyCheckInEntity,
    foodSummary: FoodDailySummaryEntity?,
    weight: DailyWeightEntity?,
    onTap: () -> Unit
) {
    val parsedStatus = checkIn.eveningOutcome.toTrafficLightStatusOrNull()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f)
        )
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(checkIn.sourceDate, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                StatusBadge(labelForStatus(checkIn.eveningOutcome), parsedStatus)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Approach: ${checkIn.approachToDay?.let(::labelForStatus) ?: "-"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Weakness: ${if (checkIn.muscleWeaknessToday) "Yes" else "No"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                checkIn.manualGripStrengthKg?.let { grip ->
                    Text(
                        String.format(java.util.Locale.UK, "Grip: %.1f kg", grip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                "Food: ${reviewFoodImportSummary(foodSummary, weight)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            reviewDayShapeSummary(checkIn)?.let { summary ->
                Text(
                    "Day shape: $summary",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (!checkIn.notes.isNullOrBlank()) {
                Text(
                    checkIn.notes,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ReviewDatePickerField(
    selectedDate: String,
    hasSavedReview: Boolean,
    hasFoodImport: Boolean,
    flashSuccess: Boolean,
    todayDate: String,
    onClearFlash: () -> Unit,
    onDateSelected: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val selectedMillis = remember(selectedDate) {
        runCatching {
            LocalDate.parse(selectedDate)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }
    val parsedDate = remember(selectedDate) {
        runCatching { LocalDate.parse(selectedDate) }.getOrNull()
    }
    var successFlashActive by remember { mutableStateOf(false) }
    LaunchedEffect(flashSuccess) {
        if (flashSuccess) {
            successFlashActive = true
            kotlinx.coroutines.delay(1000)
            successFlashActive = false
            onClearFlash()
        }
    }
    val borderColor = when {
        successFlashActive -> Color(0xFF2E7D60)
        hasSavedReview -> Color(0xFF2E7D60).copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f)
    }
    val status = listOf(
        if (hasSavedReview) "saved journal" else "no saved journal",
        if (hasFoodImport) "food synced" else "no food import"
    ).joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f)
        ),
        border = BorderStroke(if (successFlashActive) 2.dp else 1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Active day", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    Text(
                        selectedDate,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    SupportText(status)
                }
                if (hasSavedReview) {
                    StatusBadge("Saved", TrafficLightStatus.GOOD)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { parsedDate?.minusDays(1)?.toString()?.let(onDateSelected) },
                    enabled = parsedDate != null
                ) {
                    Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous day")
                }
                OutlinedButton(
                    onClick = { onDateSelected(todayDate) }
                ) {
                    Text("Today")
                }
                IconButton(
                    onClick = { parsedDate?.plusDays(1)?.toString()?.let(onDateSelected) },
                    enabled = parsedDate != null
                ) {
                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Next day")
                }
            }
        }
    }

    if (showPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedMillis,
            initialDisplayMode = DisplayMode.Picker
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString()
                            onDateSelected(date)
                        }
                        showPicker = false
                    }
                ) {
                    Text("Use date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
fun DayShapeChipSection(
    mostlyHorizontal: Boolean,
    leftHouse: Boolean,
    majorTask: Boolean,
    majorTaskType: String?,
    pemPaybackToday: Boolean,
    paybackPeakToday: Boolean,
    onMostlyHorizontalChange: (Boolean) -> Unit,
    onLeftHouseChange: (Boolean) -> Unit,
    onMajorTaskChange: (Boolean) -> Unit,
    onMajorTaskTypeChange: (String?) -> Unit,
    onPemPaybackTodayChange: (Boolean) -> Unit,
    onPaybackPeakTodayChange: (Boolean) -> Unit
) {
    SectionLabel("Today included. Optional.")
    SupportText("Leave blank if you are not sure. These broad anchors help the model without turning Journal into homework.")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DayShapeFilterChip(
            selected = mostlyHorizontal,
            label = "Mostly horizontal",
            onClick = { onMostlyHorizontalChange(!mostlyHorizontal) }
        )
        DayShapeFilterChip(
            selected = leftHouse,
            label = "Left the house",
            onClick = { onLeftHouseChange(!leftHouse) }
        )
        DayShapeFilterChip(
            selected = majorTask,
            label = "Work / major task",
            onClick = { onMajorTaskChange(!majorTask) }
        )
        DayShapeFilterChip(
            selected = pemPaybackToday,
            label = "PEM / payback today",
            onClick = { onPemPaybackTodayChange(!pemPaybackToday) }
        )
        if (pemPaybackToday || paybackPeakToday) {
            DayShapeFilterChip(
                selected = paybackPeakToday,
                label = "Peak of payback",
                onClick = { onPaybackPeakTodayChange(!paybackPeakToday) }
            )
        }
    }
    if (majorTask) {
        SectionLabel("Major task type. Optional.")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                JournalMajorTaskTypes.WORK_FROM_HOME,
                JournalMajorTaskTypes.SITE_VISIT,
                JournalMajorTaskTypes.ADMIN_ASSESSMENT,
                JournalMajorTaskTypes.OTHER_MAJOR_TASK
            ).forEach { value ->
                DayShapeFilterChip(
                    selected = majorTaskType == value,
                    label = majorTaskTypeLabel(value) ?: value,
                    onClick = {
                        onMajorTaskTypeChange(if (majorTaskType == value) null else value)
                    }
                )
            }
        }
    }
}

@Composable
fun PaybackPeakPromptSection(
    prompt: PaybackEpisodePeakPrompt,
    onMarkPeak: (String) -> Unit,
    onNotSure: () -> Unit,
    onDismiss: () -> Unit
) {
    SectionLabel("Payback peak?")
    SupportText("Looks like a payback spell may have ended. Optional: mark the worst day in that spell.")
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        prompt.pemDates.forEach { sourceDate ->
            FilterChip(
                selected = false,
                onClick = { onMarkPeak(sourceDate) },
                label = { Text(sourceDate) }
            )
        }
        FilterChip(
            selected = false,
            onClick = onNotSure,
            label = { Text("Not sure") }
        )
        TextButton(onClick = onDismiss) {
            Text("Dismiss")
        }
    }
}

@Composable
private fun DayShapeFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
private fun FoodSummaryCard(
    summary: FoodDailySummaryEntity?,
    weight: DailyWeightEntity?
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Food for this date", fontWeight = FontWeight.SemiBold)
            if (summary != null) {
                DetailRow("Calories", summary.totalCaloriesKcal?.toString() ?: "n/a")
                DetailRow("Events", summary.eventCount?.toString() ?: "n/a")
                DetailRow("Tea", summary.teaCount?.toString() ?: "n/a")
                DetailRow("First intake", summary.firstIntakeTime ?: "n/a")
                DetailRow("Last intake", summary.lastIntakeTime ?: "n/a")
                DetailRow("Eating window", summary.eatingWindowHours?.let { String.format(java.util.Locale.UK, "%.1f h", it) } ?: "n/a")
            }
            if (weight != null) {
                DetailRow("Weight", String.format(java.util.Locale.UK, "%.1f kg", weight.weightKg))
            }
        }
    }
}

@Composable
fun MuscleWeaknessToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Did you feel muscle weakness today?", fontWeight = FontWeight.SemiBold)
                SupportText("Optional marker for distinct weakness episodes, separate from fatigue or brain fog.")
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun GripStrengthField(
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text("Grip strength, kg (optional)") },
        supportingText = { Text("Manual dynamometer reading if you took one today.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
fun NotesField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text("Anything notable about the day?") },
        minLines = 3,
        maxLines = 5,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
fun StatusChipRow(
    selected: TrafficLightStatus?,
    onSelect: (TrafficLightStatus) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TrafficLightStatus.entries.forEach { status ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelect(status) },
                label = { Text(labelForStatus(status.name)) }
            )
        }
    }
}

private fun String.toTrafficLightStatusOrNull(): TrafficLightStatus? =
    runCatching { TrafficLightStatus.valueOf(this) }.getOrNull()

fun feedbackCopyFor(status: TrafficLightStatus?): String =
    when (status) {
        null -> "Select the outcome before saving. This should be the day-end truth label."
        TrafficLightStatus.GOOD -> "Good: capacity felt broadly normal for you, without obvious warning signs."
        TrafficLightStatus.OK -> "OK: manageable, but with some need for care or adaptation."
        TrafficLightStatus.UNSTEADY -> "Unsteady: you were up and functioning, but the margin felt thin or unstable."
        TrafficLightStatus.CRASH -> "Crash: clearly beyond safe capacity, or a day dominated by symptoms and pullback."
    }

fun approachCopyFor(status: TrafficLightStatus?): String =
    when (status) {
        null -> "Leave this blank if you only want to capture how the day ended."
        TrafficLightStatus.GOOD -> "You treated the day as high-capacity and spent energy fairly freely."
        TrafficLightStatus.OK -> "You did things, but with some pacing or caution."
        TrafficLightStatus.UNSTEADY -> "You kept the day light, essential, or deliberately limited."
        TrafficLightStatus.CRASH -> "You treated the day as a crash-management day."
    }
