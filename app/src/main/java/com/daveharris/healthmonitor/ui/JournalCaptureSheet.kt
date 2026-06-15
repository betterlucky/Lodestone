@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus

enum class JournalCaptureStep(val title: String, val subtitle: String) {
    OUTCOME(
        "How did the day go?",
        "Required. Saving here is enough."
    ),
    DAY_SHAPE(
        "Today included",
        "Optional broad anchors."
    ),
    APPROACH(
        "How did you approach the day?",
        "Optional pacing context."
    ),
    BODY(
        "Body check",
        "Optional weakness."
    ),
    NOTES(
        "Notes",
        "Optional detail for this date."
    )
}

fun nextJournalCaptureStep(step: JournalCaptureStep): JournalCaptureStep =
    journalCaptureSteps[(journalCaptureSteps.indexOf(step) + 1).coerceAtMost(journalCaptureSteps.lastIndex)]

fun previousJournalCaptureStep(step: JournalCaptureStep): JournalCaptureStep =
    journalCaptureSteps[(journalCaptureSteps.indexOf(step) - 1).coerceAtLeast(0)]

fun canSaveJournalCapture(outcome: TrafficLightStatus?): Boolean = outcome != null

fun shouldAllowJournalCaptureSheetValue(value: SheetValue): Boolean = value != SheetValue.Hidden

val journalCaptureSteps: List<JournalCaptureStep> = JournalCaptureStep.entries

@Composable
fun JournalCaptureSheet(
    selectedDate: String,
    dailyCheckIns: List<DailyCheckInEntity>,
    viewModel: ProbeViewModel,
    actionsEnabled: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = ::shouldAllowJournalCaptureSheetValue
    )
    var step by rememberSaveable(selectedDate) { mutableStateOf(JournalCaptureStep.OUTCOME) }
    val stepIndex = journalCaptureSteps.indexOf(step).coerceAtLeast(0)
    val canGoBack = stepIndex > 0
    val canGoForward = stepIndex < journalCaptureSteps.lastIndex
    val canSave = actionsEnabled && !viewModel.isBusy && canSaveJournalCapture(viewModel.eveningOutcomeDraft)
    val hasSavedReview = dailyCheckIns.any { it.sourceDate == selectedDate }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            JournalCaptureHeader(
                selectedDate = selectedDate,
                hasSavedReview = hasSavedReview,
                onDismiss = onDismiss
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(step.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(step.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (step) {
                    JournalCaptureStep.OUTCOME -> JournalOutcomeStep(viewModel)
                    JournalCaptureStep.DAY_SHAPE -> JournalDayShapeStep(viewModel, dailyCheckIns, selectedDate)
                    JournalCaptureStep.APPROACH -> JournalApproachStep(viewModel)
                    JournalCaptureStep.BODY -> JournalBodyStep(viewModel)
                    JournalCaptureStep.NOTES -> JournalNotesStep(viewModel)
                }
            }
            JournalCaptureFooter(
                stepIndex = stepIndex,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                canSave = canSave,
                onBack = { step = previousJournalCaptureStep(step) },
                onForward = { step = nextJournalCaptureStep(step) },
                onDone = {
                    viewModel.saveDailyCheckIn()
                    onDismiss()
                }
            )
        }
    }
}

@Composable
private fun JournalCaptureHeader(
    selectedDate: String,
    hasSavedReview: Boolean,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Journal", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "$selectedDate · ${if (hasSavedReview) "saved entry" else "new entry"}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onDismiss) {
            Text("Close")
        }
    }
}

@Composable
private fun JournalOutcomeStep(viewModel: ProbeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        SupportText(feedbackCopyFor(viewModel.eveningOutcomeDraft))
    }
}

@Composable
private fun JournalDayShapeStep(
    viewModel: ProbeViewModel,
    dailyCheckIns: List<DailyCheckInEntity>,
    selectedDate: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            activeDate = selectedDate,
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
    }
}

@Composable
private fun JournalApproachStep(viewModel: ProbeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusChipRow(
            selected = viewModel.approachToDayDraft,
            onSelect = { selected ->
                viewModel.setApproachToDay(
                    if (viewModel.approachToDayDraft == selected) null else selected
                )
            }
        )
        SupportText(approachCopyFor(viewModel.approachToDayDraft))
    }
}

@Composable
private fun JournalBodyStep(viewModel: ProbeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MuscleWeaknessToggle(
            checked = viewModel.muscleWeaknessTodayDraft,
            onCheckedChange = viewModel::updateMuscleWeaknessToday
        )
    }
}

@Composable
private fun JournalNotesStep(viewModel: ProbeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NotesField(
            value = viewModel.notesDraft,
            onValueChange = viewModel::updateNotesDraft
        )
    }
}

@Composable
private fun JournalCaptureFooter(
    stepIndex: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    canSave: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous journal question")
        }
        Text(
            "${stepIndex + 1}/${journalCaptureSteps.size}",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next journal question")
        }
        Spacer(modifier = Modifier.weight(1f))
        Button(onClick = onDone, enabled = canSave) {
            Text("Done")
        }
    }
}
