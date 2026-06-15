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
import com.daveharris.healthmonitor.data.CurrentStateSnapshotEntity
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.JournalMajorTaskTypes
import com.daveharris.healthmonitor.data.TrafficLightStatus

@Composable
fun HistoryScreen(
    padding: PaddingValues,
    currentStateSnapshots: List<CurrentStateSnapshotEntity>,
    dailyCheckIns: List<DailyCheckInEntity>,
    sleepEpisodeReviewState: SleepEpisodeReviewState,
    viewModel: ProbeViewModel,
    onOpenJournal: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val reports = remember(currentStateSnapshots, dailyCheckIns) {
        buildHistoryDayReports(
            forecasts = currentStateSnapshots,
            checkIns = dailyCheckIns
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
                subtitle = "Browse past days, compare the forecast with how the day ended, and edit saved journal entries.",
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
                    DetailRow("With forecast", coverage.withForecastCount.toString())
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
                    Text(report.forecastOutcomeLabel, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusBadge(report.outcomeLabel ?: report.forecastLabel, report.outcomeStatus ?: report.forecastStatus)
            }
            DetailRow("Forecast", report.forecastLabel)
            DetailRow("Outcome", report.outcomeLabel ?: "No journal outcome")
            DetailRow("Functional context", report.functionalContextLabel)
            report.confidenceLabel?.let { DetailRow("Confidence", it) }
            DetailRow("Evidence on file", report.dataCompletenessLabel)
            DetailRow("Forecast change", report.stateTransitionLabel)
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
    SectionCard(title = report.sourceDate, subtitle = report.forecastOutcomeLabel) {
        DetailRow("Forecast", report.forecastLabel)
        DetailRow("Outcome", report.outcomeLabel ?: "No journal outcome")
        DetailRow("Functional context", report.functionalContextLabel)
        report.confidenceLabel?.let { DetailRow("Confidence", it) }
        DetailRow("Completeness", report.dataCompletenessLabel)
        DetailRow("Forecast change", report.stateTransitionLabel)
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
    val withForecastCount: Int,
    val attentionDateCount: Int
)

fun buildHistoryCoverageSummary(
    reports: List<HistoryDayReport>,
    attentionDateCount: Int
): HistoryCoverageSummary =
    HistoryCoverageSummary(
        dayReportCount = reports.size,
        withJournalCount = reports.count { it.outcomeStatus != null },
        withForecastCount = reports.count { it.forecastStatus != null },
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
    val forecastStatus: TrafficLightStatus?,
    val forecastLabel: String,
    val outcomeStatus: TrafficLightStatus?,
    val outcomeLabel: String?,
    val forecastOutcomeLabel: String,
    val functionalContextLabel: String,
    val confidenceLabel: String?,
    val dataCompletenessLabel: String,
    val stateTransitionLabel: String,
    val notes: String?
)

/**
 * History reports surface the model-v1 forecast (`current_state_snapshot`) paired
 * with the journalled outcome, plus the current-model confidence and day-to-day
 * forecast change. Exploratory side measures (food / weight / grip imports) are
 * deliberately NOT surfaced here yet — they are not part of the v1 model and have
 * no in-app import flow. The shape leaves room to re-add per-measure rows once
 * any of them accrues enough data to be worth keeping; until then History stays
 * focused on the measures the current model actually uses.
 */
fun buildHistoryDayReports(
    forecasts: List<CurrentStateSnapshotEntity>,
    checkIns: List<DailyCheckInEntity>
): List<HistoryDayReport> {
    val latestForecastByDate = forecasts
        .groupBy { it.sourceDate }
        .mapValues { (_, snapshots) -> snapshots.maxBy { it.issuedAtEpochMs } }
    val checkInsByDate = checkIns.associateBy { it.sourceDate }
    val dates = (latestForecastByDate.keys + checkInsByDate.keys)
        .sortedDescending()
    val previousForecastByDate = mutableMapOf<String, String?>()
    var latestOlderForecast: String? = null
    for (date in dates.asReversed()) {
        previousForecastByDate[date] = latestOlderForecast
        latestForecastByDate[date]?.forecastLevel?.let { latestOlderForecast = it }
    }

    return dates.map { date ->
        val forecast = latestForecastByDate[date]
        val previousForecast = previousForecastByDate[date]
        val checkIn = checkInsByDate[date]
        val forecastStatus = forecast?.forecastLevel?.toTrafficLightStatusOrNull()
        val outcomeStatus = checkIn?.eveningOutcome?.toTrafficLightStatusOrNull()
        HistoryDayReport(
            sourceDate = date,
            forecastStatus = forecastStatus,
            forecastLabel = forecastStatus?.let { labelForStatus(it.name) } ?: "No forecast",
            outcomeStatus = outcomeStatus,
            outcomeLabel = outcomeStatus?.let { labelForStatus(it.name) },
            forecastOutcomeLabel = forecastOutcomeLabel(forecastStatus, outcomeStatus),
            functionalContextLabel = functionalContextLabel(checkIn),
            confidenceLabel = confidenceLabel(forecast),
            dataCompletenessLabel = dataCompletenessLabel(forecast, checkIn),
            stateTransitionLabel = stateTransitionLabel(previousForecast, forecast?.forecastLevel),
            notes = checkIn?.notes
        )
    }
}

private fun forecastOutcomeLabel(
    forecast: TrafficLightStatus?,
    outcome: TrafficLightStatus?
): String =
    when {
        forecast == null && outcome == null -> "No paired forecast and outcome yet"
        forecast == null -> "Outcome saved without a forecast"
        outcome == null -> "Forecast waiting for the day's outcome"
        forecast == outcome -> "Forecast and outcome aligned"
        outcome.severityRank() > forecast.severityRank() -> "Forecast was steadier than the outcome"
        forecast.severityRank() > outcome.severityRank() -> "Forecast was more cautious than the outcome"
        else -> "Forecast and outcome differed"
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
    }
    return parts.joinToString(" · ")
}

/**
 * Capped data-confidence for the day's forecast, shown only when degraded
 * (LOW/MEDIUM). HIGH is suppressed — confidence is capped while the model is
 * unvalidated, so a HIGH would be misleading (see lodestone-naming-contract.md).
 */
private fun confidenceLabel(forecast: CurrentStateSnapshotEntity?): String? =
    when (forecast?.confidenceLevel) {
        "LOW" -> "Low — limited recent data"
        "MEDIUM" -> "Medium — still gathering data"
        else -> null
    }

private fun dataCompletenessLabel(
    forecast: CurrentStateSnapshotEntity?,
    checkIn: DailyCheckInEntity?
): String {
    val present = buildList {
        if (forecast != null) add("forecast")
        if (checkIn != null) add("journal")
        if (checkIn?.dayShapeCaptured == true) add("day shape")
    }
    return if (present.isEmpty()) "No tracked data yet" else present.joinToString(", ")
}

private fun stateTransitionLabel(previousForecast: String?, currentForecast: String?): String =
    when {
        currentForecast == null -> "No forecast recorded"
        previousForecast == null -> "First forecast on record"
        previousForecast == currentForecast -> "Unchanged from prior day"
        else -> "${labelForStatus(previousForecast)} → ${labelForStatus(currentForecast)}"
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
