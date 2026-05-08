@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.TrafficLightStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun FeedbackScreen(
    padding: PaddingValues,
    morningRead: MorningReadSnapshot?,
    dailyCheckIns: List<DailyCheckInEntity>,
    foodDailySummaries: List<FoodDailySummaryEntity>,
    dailyWeights: List<DailyWeightEntity>,
    viewModel: ProbeViewModel,
    onImportFoodCsv: () -> Unit,
    actionsEnabled: Boolean,
    onOpenSettings: () -> Unit
) {
    val foodSummariesByDate = remember(foodDailySummaries) {
        foodDailySummaries.associateBy { it.sourceDate }
    }
    val weightsByDate = remember(dailyWeights) {
        dailyWeights.associateBy { it.sourceDate }
    }
    val hasSavedReview = dailyCheckIns.any { it.sourceDate == viewModel.checkInDate }
    val hasFoodImport = viewModel.currentFoodSummary != null || viewModel.currentDailyWeight != null
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeroCard(
                title = "Day Review",
                subtitle = "A low-friction evening check-in with the morning signal nearby for context.",
                eyebrow = "Review",
                actionLabel = "Settings",
                onAction = onOpenSettings
            )
        }
        item {
            ReviewDatePickerField(
                selectedDate = viewModel.checkInDate,
                hasSavedReview = hasSavedReview,
                hasFoodImport = hasFoodImport,
                flashSuccess = viewModel.saveSuccessFlash,
                onClearFlash = viewModel::clearSaveSuccessFlash,
                onDateSelected = viewModel::updateCheckInDate
            )
        }
        item {
            if (morningRead != null) {
                MorningReadCard(morningRead)
            } else {
                BannerNote(
                    text = "No morning read is available yet. You can still record how the day ended.",
                    tint = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
        item {
            SectionCard(title = "Evening check-in", subtitle = null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How did the day actually end?", fontWeight = FontWeight.SemiBold)
                    Text("(required)", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                StatusChipRow(
                    selected = viewModel.eveningOutcomeDraft,
                    onSelect = { selected ->
                        viewModel.setEveningOutcome(
                            if (viewModel.eveningOutcomeDraft == selected) null else selected
                        )
                        viewModel.clearOutcomeValidation()
                    }
                )
                if (viewModel.showOutcomeValidation && viewModel.eveningOutcomeDraft == null) {
                    Text(
                        "Select an outcome before saving.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (viewModel.eveningOutcomeDraft != null) {
                    SupportText(feedbackCopyFor(viewModel.eveningOutcomeDraft))
                }
                SectionLabel("How did you approach the day? Optional.")
                StatusChipRow(
                    selected = viewModel.approachToDayDraft,
                    onSelect = { selected ->
                        viewModel.setApproachToDay(
                            if (viewModel.approachToDayDraft == selected) null else selected
                        )
                    }
                )
                if (viewModel.approachToDayDraft != null) {
                    SupportText(approachCopyFor(viewModel.approachToDayDraft))
                }
                MuscleWeaknessToggle(
                    checked = viewModel.muscleWeaknessTodayDraft,
                    onCheckedChange = viewModel::updateMuscleWeaknessToday
                )
                SectionLabel("Notes")
                NotesField(
                    value = viewModel.notesDraft,
                    onValueChange = viewModel::updateNotesDraft
                )
                ButtonRow {
                    Button(
                        onClick = { if (actionsEnabled) viewModel.saveDailyCheckIn() },
                        enabled = !viewModel.isBusy
                    ) {
                        Text(if (hasSavedReview) "Update ${viewModel.checkInDate}" else "Save ${viewModel.checkInDate}")
                    }
                    OutlinedButton(
                        onClick = { if (actionsEnabled) viewModel.resetSelectedReviewDate() },
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Reset")
                    }
                }
            }
        }
        item {
            FoodSection(
                foodSummary = viewModel.currentFoodSummary,
                weight = viewModel.currentDailyWeight,
                onSyncFood = { if (actionsEnabled) viewModel.importLatestFoodCsvFromDownloads() },
                onChooseFile = { if (actionsEnabled) onImportFoodCsv() },
                isBusy = viewModel.isBusy
            )
        }
        item { SectionLabel("Recent reviews") }
        items(
            items = dailyCheckIns,
            key = { item -> "check-in-${item.sourceDate}-${item.updatedAtEpochMs}" }
        ) { checkIn ->
            val foodSummary = foodSummariesByDate[checkIn.sourceDate]
            val weight = weightsByDate[checkIn.sourceDate]
            ReviewHistoryItem(
                checkIn = checkIn,
                foodSummary = foodSummary,
                weight = weight,
                onTap = { viewModel.loadDailyCheckIn(checkIn.sourceDate) }
            )
        }
    }
}

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

@Composable
private fun FoodSection(
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
private fun ReviewHistoryItem(
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
            }
            Text(
                "Food: ${reviewFoodImportSummary(foodSummary, weight)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
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
private fun ReviewDatePickerField(
    selectedDate: String,
    hasSavedReview: Boolean,
    hasFoodImport: Boolean,
    flashSuccess: Boolean,
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
        if (hasSavedReview) "saved review" else "no saved review",
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
                    onClick = { onDateSelected(LocalDate.now().toString()) }
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
private fun MuscleWeaknessToggle(
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
private fun NotesField(value: String, onValueChange: (String) -> Unit) {
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
private fun StatusChipRow(
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

private fun feedbackCopyFor(status: TrafficLightStatus?): String =
    when (status) {
        null -> "Select the outcome before saving. This should be the day-end truth label."
        TrafficLightStatus.GOOD -> "Good: capacity felt broadly normal for you, without obvious warning signs."
        TrafficLightStatus.OK -> "OK: manageable, but with some need for care or adaptation."
        TrafficLightStatus.UNSTEADY -> "Unsteady: you were up and functioning, but the margin felt thin or unstable."
        TrafficLightStatus.CRASH -> "Crash: clearly beyond safe capacity, or a day dominated by symptoms and pullback."
    }

private fun approachCopyFor(status: TrafficLightStatus?): String =
    when (status) {
        null -> "Leave this blank if you only want to capture how the day ended."
        TrafficLightStatus.GOOD -> "You treated the day as high-capacity and spent energy fairly freely."
        TrafficLightStatus.OK -> "You did things, but with some pacing or caution."
        TrafficLightStatus.UNSTEADY -> "You kept the day light, essential, or deliberately limited."
        TrafficLightStatus.CRASH -> "You treated the day as a crash-management day."
    }
