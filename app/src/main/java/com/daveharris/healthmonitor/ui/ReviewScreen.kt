@file:OptIn(ExperimentalLayoutApi::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.MorningReadSnapshot

@Composable
fun JournalScreen(
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
                title = "Journal",
                subtitle = "A low-friction evening check-in with the current signal nearby only as context.",
                eyebrow = "Journal",
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
                todayDate = viewModel.currentLodestoneDate(),
                onClearFlash = viewModel::clearSaveSuccessFlash,
                onDateSelected = viewModel::updateCheckInDate
            )
        }
        item {
            val selectedMorningRead = morningRead?.takeIf { it.sourceDate == viewModel.checkInDate }
            JournalContextCard(
                selectedDate = viewModel.checkInDate,
                morningRead = selectedMorningRead
            )
        }
        item {
            SectionCard(title = "Evening check-in", subtitle = null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How did the day actually end?", fontWeight = FontWeight.SemiBold)
                    Text("(one tap is enough)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
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
                DayShapeChipSection(
                    mostlyHorizontal = viewModel.mostlyHorizontalDraft,
                    leftHouse = viewModel.leftHouseDraft,
                    majorTask = viewModel.majorTaskDraft,
                    majorTaskType = viewModel.majorTaskTypeDraft,
                    pemPaybackToday = viewModel.pemPaybackTodayDraft,
                    paybackPeakToday = viewModel.paybackPeakTodayDraft,
                    onMostlyHorizontalChange = viewModel::updateMostlyHorizontal,
                    onLeftHouseChange = viewModel::updateLeftHouse,
                    onMajorTaskChange = viewModel::updateMajorTask,
                    onMajorTaskTypeChange = viewModel::updateMajorTaskType,
                    onPemPaybackTodayChange = viewModel::updatePemPaybackToday,
                    onPaybackPeakTodayChange = viewModel::updatePaybackPeakToday
                )
                pendingPaybackPeakPrompt(
                    activeDate = viewModel.checkInDate,
                    checkIns = dailyCheckIns,
                    activePemMarked = viewModel.pemPaybackTodayDraft
                )?.let { prompt ->
                    PaybackPeakPromptSection(
                        prompt = prompt,
                        onMarkPeak = viewModel::markPaybackPeakDate,
                        onNotSure = { viewModel.markPaybackPeakNotSure(prompt.episodeEndDate) },
                        onDismiss = { viewModel.dismissPaybackPeakPrompt(prompt.episodeEndDate) }
                    )
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
                GripStrengthField(
                    value = viewModel.manualGripStrengthKgDraft,
                    onValueChange = viewModel::updateManualGripStrengthKg
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
        item { SectionLabel("Recent journal entries") }
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

@Composable
private fun JournalContextCard(
    selectedDate: String,
    morningRead: MorningReadSnapshot?
) {
    SectionCard(title = "Context", subtitle = "Optional signal context") {
        if (morningRead == null) {
            SupportText("No morning signal is available for $selectedDate. You can still record how the day ended.")
            return@SectionCard
        }
        DetailRow("Morning signal", morningRead.status?.let { labelForStatus(it.name) } ?: "TBC")
        DetailRow("Signal state", morningRead.signalContextLabel())
        DetailRow("Confidence", morningRead.confidence.replaceFirstChar { it.titlecase() })
        DetailRow("Source", morningRead.overnightAutonomicSource.replace('_', ' '))
        morningRead.reasons.firstOrNull()?.let { reason ->
            SupportText(reason)
        }
    }
}

private fun MorningReadSnapshot.signalContextLabel(): String =
    when {
        sleepDataReady -> "Loop sleep report attached"
        hasEstablishedSleepWindow() && hasSufficientReadyPpiCoverage() ->
            "Current signal ready; Loop report pending for comparison"
        hasEstablishedSleepWindow() -> "Current signal limited; thin PPI coverage"
        isInterim -> "Current signal limited; sleep window pending"
        else -> "Current, sleep context pending"
    }
