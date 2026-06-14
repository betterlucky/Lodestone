@file:OptIn(ExperimentalLayoutApi::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.GripSessionEntity
import com.daveharris.healthmonitor.data.JournalMajorTaskTypes
import com.daveharris.healthmonitor.data.MorningPredictionSnapshotEntity
import com.daveharris.healthmonitor.data.AnalysisWindowSource
import com.daveharris.healthmonitor.data.TrafficLightStatus

@Composable
fun HistoryScreen(
    padding: PaddingValues,
    morningPredictionSnapshots: List<MorningPredictionSnapshotEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    foodDailySummaries: List<FoodDailySummaryEntity>,
    dailyWeights: List<DailyWeightEntity>,
    gripSessions: List<GripSessionEntity>,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    viewModel: ProbeViewModel,
    onOpenJournal: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val reports = remember(morningPredictionSnapshots, dailyCheckIns, foodDailySummaries, dailyWeights, gripSessions) {
        buildHistoryDayReports(
            predictions = morningPredictionSnapshots,
            checkIns = dailyCheckIns,
            foodSummaries = foodDailySummaries,
            weights = dailyWeights,
            gripSessions = gripSessions
        )
    }
    val coverage = remember(reports, sleepEpisodeReviewState.attentionDateCount) {
        buildHistoryCoverageSummary(reports, sleepEpisodeReviewState.attentionDateCount)
    }
    val sleepRepairByDate = remember(sleepEpisodeReviewState) {
        sleepEpisodeReviewState.dateGroups.associate { it.sourceDate to it.repairStatusLabel }
    }
    var selectedReportDate by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeroCard(
                title = "History",
                subtitle = "Browse past days, compare morning signal with how the day ended, and edit saved journal entries.",
                eyebrow = "History",
                actionLabel = "Settings",
                onAction = onOpenSettings
            )
        }
        if (reports.isNotEmpty()) {
            item {
                SectionCard(title = "Past days at a glance", subtitle = "Coverage across saved reports") {
                    DetailRow("Day reports", coverage.dayReportCount.toString())
                    DetailRow("With journal outcome", coverage.withJournalCount.toString())
                    DetailRow("With morning signal", coverage.withMorningSignalCount.toString())
                    if (coverage.attentionDateCount > 0) {
                        DetailRow("Sleep-window review backlog", coverage.attentionDateCount.toString())
                        SupportText(
                            "These past days still have unconfirmed sleep/rest windows. " +
                                "Open a day below or use Now to repair the active read."
                        )
                    }
                    SupportText("Each day report pairs forecast and outcome where both exist. Open a day to see detail or edit its journal.")
                }
            }
        }
        item { SectionLabel("Past days") }
        if (reports.isEmpty()) {
            item {
                BannerNote(
                    text = "No history rows yet. Check in and save Journal entries to build paired reports.",
                    tint = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        } else {
            items(
                items = reports,
                key = { report -> "history-report-${report.sourceDate}" }
            ) { report ->
                val selected = selectedReportDate == report.sourceDate
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    HistoryDayReportCard(
                        report = report,
                        sleepWindowStatus = sleepRepairByDate[report.sourceDate],
                        selected = selected,
                        onOpenDetail = { selectedReportDate = report.sourceDate },
                        onOpenJournal = {
                            openJournalForDate(viewModel, report.sourceDate, onOpenJournal)
                        }
                    )
                    if (selected) {
                        HistoryDayDetailCard(
                            report = report,
                            sleepWindowStatus = sleepRepairByDate[report.sourceDate],
                            onOpenJournal = {
                                openJournalForDate(viewModel, report.sourceDate, onOpenJournal)
                            },
                            onClose = { selectedReportDate = null }
                        )
                    }
                }
            }
        }
    }
}

private fun openJournalForDate(
    viewModel: ProbeViewModel,
    sourceDate: String,
    onOpenJournal: () -> Unit
) {
    viewModel.updateCheckInDate(sourceDate)
    onOpenJournal()
}

@Composable
private fun HistoryDayReportCard(
    report: HistoryDayReport,
    sleepWindowStatus: String?,
    selected: Boolean,
    onOpenDetail: () -> Unit,
    onOpenJournal: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.88f)
            }
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Text(report.sourceDate, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Text(report.predictionOutcomeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(report.outcomeLabel ?: report.predictionLabel, report.outcomeStatus ?: report.predictionStatus)
            }
            DetailRow("Morning signal", report.predictionLabel)
            DetailRow("Outcome", report.outcomeLabel ?: "No journal outcome")
            DetailRow("Functional context", report.functionalContextLabel)
            DetailRow("Robustness", report.robustnessLabel)
            DetailRow("Evidence on file", report.dataCompletenessLabel)
            DetailRow("Window source", report.windowProvenanceLabel)
            DetailRow("Sleep bucket", report.sleepBucketLabel)
            DetailRow("Morning signal change", report.stabilityTransitionLabel)
            historySleepWindowDetailRow(sleepWindowStatus)?.let { (label, value) ->
                DetailRow(label, value)
            }
            report.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Text(
                    notes,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ButtonRow {
                TextButton(onClick = onOpenDetail) {
                    Text("Details")
                }
                TextButton(onClick = onOpenJournal) {
                    Text(historyJournalActionLabel(report.outcomeStatus != null))
                }
            }
        }
    }
}

@Composable
private fun HistoryDayDetailCard(
    report: HistoryDayReport,
    sleepWindowStatus: String?,
    onOpenJournal: () -> Unit,
    onClose: () -> Unit
) {
    SectionCard(title = report.sourceDate, subtitle = report.predictionOutcomeLabel) {
        DetailRow("Morning signal", report.predictionLabel)
        DetailRow("Outcome", report.outcomeLabel ?: "No journal outcome")
        DetailRow("Functional context", report.functionalContextLabel)
        DetailRow("Analysis window", report.windowProvenanceLabel)
        DetailRow("Robustness", report.robustnessLabel)
        DetailRow("Sleep bucket", report.sleepBucketLabel)
        DetailRow("Completeness", report.dataCompletenessLabel)
        DetailRow("Food", report.foodSummaryLabel)
        DetailRow("Weight", report.weightLabel)
        DetailRow("Grip strength", report.gripStrengthLabel)
        DetailRow("Morning signal change", report.stabilityTransitionLabel)
        historySleepWindowDetailRow(sleepWindowStatus)?.let { (label, value) ->
            DetailRow(label, value)
        }
        DetailRow("Notes", report.notes?.takeIf { it.isNotBlank() } ?: "No notes")
        ButtonRow {
            TextButton(onClick = onOpenJournal) {
                Text(historyJournalActionLabel(report.outcomeStatus != null))
            }
            TextButton(onClick = onClose) {
                Text("Close")
            }
        }
    }
}

data class HistoryCoverageSummary(
    val dayReportCount: Int,
    val withJournalCount: Int,
    val withMorningSignalCount: Int,
    val attentionDateCount: Int
)

fun buildHistoryCoverageSummary(
    reports: List<HistoryDayReport>,
    attentionDateCount: Int
): HistoryCoverageSummary =
    HistoryCoverageSummary(
        dayReportCount = reports.size,
        withJournalCount = reports.count { it.outcomeStatus != null },
        withMorningSignalCount = reports.count { it.predictionStatus != null },
        attentionDateCount = attentionDateCount
    )

fun historyJournalActionLabel(hasSavedOutcome: Boolean): String =
    if (hasSavedOutcome) "Edit journal" else "Add journal"

fun historySleepWindowDetailRow(status: String?): Pair<String, String>? =
    when (status) {
        "Needs review" -> "Sleep window" to "Needs review — confirm candidates on Now"
        "Confirmed" -> "Sleep window" to "Confirmed"
        "Context saved" -> "Sleep window" to "Context saved"
        else -> null
    }

data class HistoryDayReport(
    val sourceDate: String,
    val predictionStatus: TrafficLightStatus?,
    val predictionLabel: String,
    val outcomeStatus: TrafficLightStatus?,
    val outcomeLabel: String?,
    val predictionOutcomeLabel: String,
    val functionalContextLabel: String,
    val robustnessLabel: String,
    val dataCompletenessLabel: String,
    val windowProvenanceLabel: String,
    val sleepBucketLabel: String,
    val stabilityTransitionLabel: String,
    val foodSummaryLabel: String,
    val weightLabel: String,
    val gripStrengthLabel: String,
    val notes: String?
)

fun buildHistoryDayReports(
    predictions: List<MorningPredictionSnapshotEntity>,
    checkIns: List<DailyCheckInEntity>,
    foodSummaries: List<FoodDailySummaryEntity>,
    weights: List<DailyWeightEntity>,
    gripSessions: List<GripSessionEntity> = emptyList()
): List<HistoryDayReport> {
    val latestPredictionByDate = predictions
        .groupBy { it.sourceDate }
        .mapValues { (_, snapshots) -> snapshots.maxBy { it.issuedAtEpochMs } }
    val checkInsByDate = checkIns.associateBy { it.sourceDate }
    val foodByDate = foodSummaries.associateBy { it.sourceDate }
    val weightsByDate = weights.associateBy { it.sourceDate }
    val gripByDate = gripSessions.groupBy { it.sourceDate }
    val dates = (latestPredictionByDate.keys + checkInsByDate.keys + foodByDate.keys + weightsByDate.keys + gripByDate.keys)
        .sortedDescending()
    val previousPredictionStatusByDate = mutableMapOf<String, String?>()
    var latestOlderPredictionStatus: String? = null
    for (date in dates.asReversed()) {
        previousPredictionStatusByDate[date] = latestOlderPredictionStatus
        latestPredictionByDate[date]?.status?.let { latestOlderPredictionStatus = it }
    }

    return dates.map { date ->
        val prediction = latestPredictionByDate[date]
        val previousPrediction = previousPredictionStatusByDate[date]
        val checkIn = checkInsByDate[date]
        val predictionStatus = prediction?.status?.toTrafficLightStatusOrNull()
        val outcomeStatus = checkIn?.eveningOutcome?.toTrafficLightStatusOrNull()
        val food = foodByDate[date]
        val weight = weightsByDate[date]
        val gripForDate = gripByDate[date].orEmpty()
        HistoryDayReport(
            sourceDate = date,
            predictionStatus = predictionStatus,
            predictionLabel = predictionStatus?.let { labelForStatus(it.name) } ?: "No morning signal",
            outcomeStatus = outcomeStatus,
            outcomeLabel = outcomeStatus?.let { labelForStatus(it.name) },
            predictionOutcomeLabel = predictionOutcomeLabel(predictionStatus, outcomeStatus),
            functionalContextLabel = functionalContextLabel(checkIn),
            robustnessLabel = robustnessLabel(prediction),
            dataCompletenessLabel = dataCompletenessLabel(prediction, checkIn, food, weight, gripForDate),
            windowProvenanceLabel = historyWindowSourceLabel(prediction),
            sleepBucketLabel = sleepBucketLabel(prediction?.sleepDurationMinutes),
            stabilityTransitionLabel = stabilityTransitionLabel(previousPrediction, prediction?.status),
            foodSummaryLabel = historyFoodLabel(food),
            weightLabel = historyWeightLabel(weight),
            gripStrengthLabel = historyGripStrengthLabel(checkIn, gripForDate),
            notes = checkIn?.notes
        )
    }
}

private fun predictionOutcomeLabel(
    prediction: TrafficLightStatus?,
    outcome: TrafficLightStatus?
): String =
    when {
        prediction == null && outcome == null -> "No paired autonomic/functional evidence yet"
        prediction == null -> "Functional outcome saved without an autonomic signal"
        outcome == null -> "Autonomic signal waiting for functional outcome"
        prediction == outcome -> "Autonomic signal and functional outcome aligned"
        outcome.severityRank() > prediction.severityRank() -> "Autonomic signal looked steadier than functional outcome"
        prediction.severityRank() > outcome.severityRank() -> "Autonomic signal looked more cautious than functional outcome"
        else -> "Autonomic and functional lanes differed"
    }

private fun functionalContextLabel(checkIn: DailyCheckInEntity?): String {
    if (checkIn == null) return "No functional outcome"
    val outcome = checkIn.eveningOutcome.toTrafficLightStatusOrNull()
    val parts = buildList {
        add("Outcome: ${outcome?.let { labelForStatus(it.name) } ?: checkIn.eveningOutcome}")
        if (checkIn.dayShapeCaptured == true) {
            val anchors = checkIn.functionalAnchors()
            add(if (anchors.isEmpty()) "no day-shape anchors selected" else anchors.joinToString(", "))
        } else {
            add("day shape unknown")
        }
        checkIn.manualGripStrengthKg?.let { grip ->
            add(String.format(java.util.Locale.UK, "grip %.1f kg", grip))
        }
    }
    return parts.joinToString(" · ")
}

private fun robustnessLabel(prediction: MorningPredictionSnapshotEntity?): String {
    if (prediction == null) return "No morning signal evidence"
    val parts = buildList {
        add(if (prediction.sleepDataReady) "sleep report" else "sleep pending")
        add(if ((prediction.rawPpiGoodEpochCount ?: 0) > 0) "PPI ${prediction.rawPpiGoodEpochCount}" else "no PPI")
        add(if (prediction.baselineReady) "baseline ready" else "baseline sparse")
        add(prediction.confidence.replaceFirstChar { it.titlecase() })
    }
    return parts.joinToString(" · ")
}

private fun historyWindowSourceLabel(prediction: MorningPredictionSnapshotEntity?): String {
    if (prediction == null) return "No active window recorded"
    val source = prediction.overnightAutonomicSource.replace('_', ' ')
    val morningReadSource = AnalysisWindowSource.fromKey(prediction.overnightAutonomicSource)
    val state = when {
        prediction.sleepDataReady -> "Loop report attached"
        morningReadSource?.hasEstablishedSleepWindow == true -> "Loop report pending for comparison"
        prediction.isInterim -> "sleep/rest window pending"
        else -> "sleep context pending"
    }
    return "$source ($state)"
}

private fun dataCompletenessLabel(
    prediction: MorningPredictionSnapshotEntity?,
    checkIn: DailyCheckInEntity?,
    food: FoodDailySummaryEntity?,
    weight: DailyWeightEntity?,
    gripSessions: List<GripSessionEntity>
): String {
    val present = buildList {
        if (prediction != null) add("prediction")
        if (prediction?.sleepDataReady == true) add("sleep")
        if ((prediction?.rawPpiGoodEpochCount ?: 0) > 0) add("PPI")
        if (checkIn != null) add("journal")
        if (checkIn?.dayShapeCaptured == true) add("day shape")
        if (food != null) add("food")
        if (weight != null) add("weight")
        if (checkIn?.manualGripStrengthKg != null || gripSessions.isNotEmpty()) add("grip")
    }
    return if (present.isEmpty()) "No tracked data yet" else present.joinToString(", ")
}

private fun historyFoodLabel(food: FoodDailySummaryEntity?): String {
    if (food == null) return "No food import"
    val parts = buildList {
        food.totalCaloriesKcal?.let { add("$it kcal") }
        food.eventCount?.let { add("$it items") }
        food.teaCount?.let { add("$it tea") }
        food.eatingWindowHours?.let { add(String.format(java.util.Locale.UK, "%.1fh window", it)) }
    }
    return parts.joinToString(", ").ifBlank { "Food import present" }
}

private fun historyWeightLabel(weight: DailyWeightEntity?): String =
    weight?.let {
        val measured = it.measuredTime?.let { time -> " at $time" }.orEmpty()
        String.format(java.util.Locale.UK, "%.1f kg%s", it.weightKg, measured)
    } ?: "No weight row"

private fun historyGripStrengthLabel(
    checkIn: DailyCheckInEntity?,
    sessions: List<GripSessionEntity>
): String {
    if (sessions.isEmpty()) {
        return checkIn?.manualGripStrengthKg?.let { String.format(java.util.Locale.UK, "%.1f kg", it) }
            ?: "No grip reading"
    }
    val parts = buildList {
        checkIn?.manualGripStrengthKg?.let { add(String.format(java.util.Locale.UK, "manual %.1f kg", it)) }
        if (sessions.isNotEmpty()) add(reviewGripSessionSummary(sessions))
    }
    return parts.joinToString(" · ").ifBlank { "No grip reading" }
}

private fun sleepBucketLabel(minutes: Int?): String =
    when {
        minutes == null -> "No sleep duration"
        minutes < 4 * 60 -> "Short/irregular sleep"
        minutes > 10 * 60 -> "Long/irregular sleep"
        else -> "Typical-duration sleep"
    }

private fun stabilityTransitionLabel(previousStatus: String?, currentStatus: String?): String =
    when {
        currentStatus == null -> "No morning signal recorded"
        previousStatus == null -> "First morning signal on record"
        previousStatus == currentStatus -> "Unchanged from prior day"
        else -> "${labelForStatus(previousStatus)} → ${labelForStatus(currentStatus)}"
    }

private fun String.toTrafficLightStatusOrNull(): TrafficLightStatus? =
    runCatching { TrafficLightStatus.valueOf(this) }.getOrNull()

private fun DailyCheckInEntity.functionalAnchors(): List<String> =
    buildList {
        if (mostlyHorizontal == true) add("Mostly horizontal")
        if (leftHouse == true) add("Left the house")
        if (majorTask == true) add(majorTaskType.historyMajorTaskTypeLabel() ?: "Work / major task")
        if (pemPaybackToday == true) add("PEM / payback")
        if (paybackPeakToday == true) add("Payback peak")
        if (muscleWeaknessToday) add("Muscle weakness")
    }

private fun String?.historyMajorTaskTypeLabel(): String? =
    when (this) {
        JournalMajorTaskTypes.WORK_FROM_HOME -> "Work from home"
        JournalMajorTaskTypes.SITE_VISIT -> "Site visit"
        JournalMajorTaskTypes.ADMIN_ASSESSMENT -> "Admin / assessment"
        JournalMajorTaskTypes.OTHER_MAJOR_TASK -> "Other major task"
        else -> null
    }

private fun TrafficLightStatus.severityRank(): Int =
    when (this) {
        TrafficLightStatus.GOOD -> 0
        TrafficLightStatus.OK -> 1
        TrafficLightStatus.UNSTEADY -> 2
        TrafficLightStatus.CRASH -> 3
    }
