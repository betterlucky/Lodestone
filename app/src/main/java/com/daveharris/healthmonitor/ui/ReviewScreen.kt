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
