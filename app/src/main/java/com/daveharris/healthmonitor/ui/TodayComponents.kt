@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.HrvTrajectoryPoint
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.AnalysisWindowSource
import com.daveharris.healthmonitor.data.AnalysisWindowEvidence
import com.daveharris.healthmonitor.data.CautionLevel
import com.daveharris.healthmonitor.data.ConfidenceLevel
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.WakeMarkerEntity
import com.daveharris.healthmonitor.data.WakeMarkerSources
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class TodayReadinessStage {
    SLEEP_TIME,
    STARTING_SYNC,
    INITIAL_PPI,
    UPDATE_COMPLETE,
    NOT_STARTED
}

enum class SignalConfidenceState {
    READY,
    WAITING,
    PARTIAL
}

data class SignalConfidenceSummary(
    val state: SignalConfidenceState,
    val label: String,
    val missingInputs: List<String>,
    val supportingGaps: List<String>
)

data class TodayReadinessStatus(
    val stage: TodayReadinessStage,
    val title: String,
    val sleepReport: String,
    val ppiReceipt: String,
    val message: String,
    val hrvDetail: String,
    val dataQuality: SignalConfidenceSummary,
    val connectionPrompt: String? = null,
    val heroPrompt: String? = null,
    val catchUpPrompt: String? = null,
    val lastUsedLabel: String? = null,
    val lastLoopSyncLabel: String? = null
)

enum class NowEvidenceDetail {
    SIGNAL,
    SLEEP_REST,
    DATA_QUALITY,
    HRV
}

fun signalConfidenceSummary(
    stage: TodayReadinessStage,
    morningRead: AnalysisWindowEvidence?,
    hasFinalSleep: Boolean = morningRead?.sleepDataReady == true,
    hasPpi: Boolean = morningRead.hasPpiSignal(),
    hasUsableWindow: Boolean = morningRead.hasEstablishedSleepWindow(),
    hasReadyLocalSignal: Boolean = hasPpi && hasUsableWindow && morningRead.hasSufficientReadyPpiCoverage()
): SignalConfidenceSummary {
    val coreMissing = buildList {
        if (!hasUsableWindow) add("Sleep/rest window")
        if (!hasPpi) add("24/7 PPI epochs")
        if (hasPpi && hasUsableWindow && !hasFinalSleep && !hasReadyLocalSignal) {
            add("Ready local PPI coverage")
        }
    }
    val supportingGaps = buildList {
        if (morningRead == null) {
            add("Morning-read snapshot")
        } else {
            if (!hasFinalSleep && hasUsableWindow && hasPpi) add("Loop sleep report comparison")
            if (morningRead.nightlyRmssd == null) add("Nightly Recharge RMSSD")
            if ((morningRead.rawPpiCoverageHours ?: 0.0) < 4.0 && hasPpi) add("Long PPI coverage window")
            if (!morningRead.baselineReady) add("Personal baseline")
        }
    }
    return when {
        stage == TodayReadinessStage.SLEEP_TIME || stage == TodayReadinessStage.STARTING_SYNC ->
            SignalConfidenceSummary(SignalConfidenceState.WAITING, "Waiting", coreMissing, supportingGaps)
        coreMissing.isEmpty() ->
            SignalConfidenceSummary(
                state = if (supportingGaps.isEmpty()) SignalConfidenceState.READY else SignalConfidenceState.PARTIAL,
                label = if (supportingGaps.isEmpty()) "Ready" else "Ready, supporting gaps",
                missingInputs = emptyList(),
                supportingGaps = supportingGaps
            )
        hasPpi || hasFinalSleep ->
            SignalConfidenceSummary(SignalConfidenceState.PARTIAL, "Partial", coreMissing, supportingGaps)
        else ->
            SignalConfidenceSummary(SignalConfidenceState.WAITING, "Waiting", coreMissing, supportingGaps)
    }
}

@Composable
fun TodayHeroCard(
    nowState: NowScreenState,
    onOpenSettings: () -> Unit,
    onOpenSignalsSection: (SignalsSection) -> Unit = {}
) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatHeroDate(nowState.today),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    TextButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                Text(
                    "Daily Forecast",
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    nowState.currentState.label,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    dailyForecastHeroFacts(nowState).forEach { fact ->
                        HeroPill(fact = fact, onClick = onOpenSignalsSection)
                    }
                }
                Text(
                    dailyForecastHeroMessage(nowState),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.90f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

// A hero pill: its label plus the Signals section it drills into (table-of-contents
// intent). A null target makes the pill non-interactive context only.
data class HeroFact(
    val label: String,
    val target: SignalsSection?
)

internal fun dailyForecastHeroFacts(nowState: NowScreenState): List<HeroFact> =
    buildList {
        heroAttentionFact(nowState)?.let { add(HeroFact(it, SignalsSection.SIGNAL_DETAIL)) }
        heroCautionFact(nowState)?.let { add(HeroFact(it, SignalsSection.CURRENT_SIGNAL)) }
        heroConfidenceFact(nowState)?.let { add(HeroFact(it, SignalsSection.SIGNAL_DETAIL)) }
        add(HeroFact(heroFreshnessFact(nowState), SignalsSection.SIGNAL_DETAIL))
        add(HeroFact(heroSleepRestFact(nowState), SignalsSection.SLEEP_REST))
    }.distinctBy { it.label }.take(MAX_DAILY_FORECAST_FACTS)

private const val MAX_DAILY_FORECAST_FACTS = 5

internal fun dailyForecastHeroMessage(nowState: NowScreenState): String =
    nowState.currentState.message

internal fun dailyForecastCheckInMessage(nowState: NowScreenState): String =
    when {
        nowState.deviceConnection.availability == NowDataAvailability.MISSING ->
            "Check in can refresh Loop data once a device is selected. Recent check-ins still provide functional context."
        nowState.signalRobustness.availability == NowDataAvailability.MISSING &&
            nowState.functionalContext.availability != NowDataAvailability.MISSING ->
            "Check in refreshes Loop data when available. Until then, the forecast leans on recent check-ins."
        else ->
            "Check in refreshes the forecast and syncs the Loop."
    }

private fun heroAttentionFact(nowState: NowScreenState): String? {
    val needsDeviceContext = nowState.readinessStatus.connectionPrompt != null ||
        nowState.deviceConnection.availability in setOf(
            NowDataAvailability.MISSING,
            NowDataAvailability.PENDING
        )
    return if (needsDeviceContext) {
        nowState.deviceConnection.detail
    } else {
        nowState.readinessStatus.heroPrompt
    }
}

// Caution sits beside the forecast and only surfaces when ELEVATED (naming contract:
// heroStabilityFact -> heroCautionFact).
private fun heroCautionFact(nowState: NowScreenState): String? =
    nowState.currentStateRead?.caution
        ?.takeIf { it.level == CautionLevel.ELEVATED }
        ?.let { "Caution: ${it.reasons.firstOrNull() ?: "ease off; payback can land a couple of days out"}" }

// Confidence is shown ONLY when degraded — never over-claim during data collection.
// "Degraded" = a genuinely low model read or no Loop signal at all; the normal capped
// MEDIUM ceiling is not surfaced.
private fun heroConfidenceFact(nowState: NowScreenState): String? =
    when {
        nowState.signalRobustness.availability == NowDataAvailability.MISSING &&
            nowState.functionalContext.availability != NowDataAvailability.MISSING ->
            "Confidence: check-ins only"
        nowState.signalRobustness.availability == NowDataAvailability.MISSING ->
            "Confidence: low"
        nowState.currentStateRead?.confidence == ConfidenceLevel.LOW ->
            "Confidence: low"
        else -> null
    }

private fun heroFreshnessFact(nowState: NowScreenState): String =
    "Last sync: ${nowState.freshness.loopSync.detail}"

private fun heroSleepRestFact(nowState: NowScreenState): String =
    "Recent rest: ${nowState.recentRest.detail}"

@Composable
private fun HeroPill(
    fact: HeroFact,
    onClick: (SignalsSection) -> Unit
) {
    val pillShape = RoundedCornerShape(100.dp)
    val base = Modifier
        .clip(pillShape)
        .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f))
        .border(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.24f), pillShape)
    val target = fact.target
    val modifier = if (target != null) base.clickable { onClick(target) } else base
    Box(modifier = modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(
            fact.label,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

private fun formatHeroDate(value: String): String =
    runCatching {
        LocalDate.parse(value).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", java.util.Locale.UK))
    }.getOrDefault(value)

@Composable
fun MorningSignalSection(
    nowState: NowScreenState,
    onOpenEvidence: (NowEvidenceDetail) -> Unit
) {
    val morningRead = nowState.activeMorningRead
    val todayStatus = nowState.readinessStatus
    val tone = statusTone(nowState.currentState.status)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = tone.copy(alpha = if (nowState.currentState.status == null) 0.06f else 0.10f)
        ),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Current signal", fontWeight = FontWeight.SemiBold)
            if (morningRead == null) {
                Text(nowState.currentState.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (todayStatus.dataQuality.missingInputs.isNotEmpty()) {
                    DetailRow("Needed", todayStatus.dataQuality.missingInputs.joinToString())
                }
                DetailRow("Sleep/rest", nowState.activeAnalysisWindow.label)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Daily forecast", nowState.currentState.status?.let { labelForStatus(it.name) } ?: "TBC")
                    DetailRow("Functional context", nowState.functionalContext.label)
                    DetailRow("Autonomic context", nowState.autonomicContext.label)
                    if (nowState.stateStability.availability == NowDataAvailability.PRESENT) {
                        DetailRow("Stability", nowState.stateStability.label)
                    }
                    if (nowState.signalRobustness.missingInputs.isNotEmpty()) {
                        DetailRow("Needed", nowState.signalRobustness.missingInputs.joinToString())
                    }
                }
                nowState.currentStateRead?.reasons.orEmpty().take(3).forEach { reason ->
                    Text("* $reason", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (nowState.functionalContext.availability != NowDataAvailability.MISSING) {
                    Text(nowState.functionalContext.detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                EvidenceChip("Signal", { onOpenEvidence(NowEvidenceDetail.SIGNAL) })
                EvidenceChip("Sleep/rest", { onOpenEvidence(NowEvidenceDetail.SLEEP_REST) })
                EvidenceChip("Data quality", { onOpenEvidence(NowEvidenceDetail.DATA_QUALITY) })
                EvidenceChip("HRV detail", { onOpenEvidence(NowEvidenceDetail.HRV) })
            }
        }
    }
}

@Composable
private fun EvidenceChip(
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(label) }
    )
}

@Composable
fun NowEvidenceDetailSheet(
    detail: NowEvidenceDetail,
    nowState: NowScreenState,
    onDismiss: () -> Unit,
    onOpenHrvTrajectory: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(detail.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            when (detail) {
                NowEvidenceDetail.SIGNAL -> SignalEvidenceContent(nowState)
                NowEvidenceDetail.SLEEP_REST -> SleepRestEvidenceContent(nowState)
                NowEvidenceDetail.DATA_QUALITY -> DataQualityEvidenceContent(nowState)
                NowEvidenceDetail.HRV -> HrvEvidenceContent(nowState, onOpenHrvTrajectory)
            }
        }
    }
}

private val NowEvidenceDetail.title: String
    get() = when (this) {
        NowEvidenceDetail.SIGNAL -> "Signal detail"
        NowEvidenceDetail.SLEEP_REST -> "Sleep/rest evidence"
        NowEvidenceDetail.DATA_QUALITY -> "Data quality"
        NowEvidenceDetail.HRV -> "Autonomic detail"
    }

@Composable
private fun SignalEvidenceContent(nowState: NowScreenState) {
    val morningRead = nowState.activeMorningRead
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailRow("Forecast", nowState.currentState.label)
        DetailRow("Confidence", nowState.signalRobustness.label)
        DetailRow("Basis", nowState.signalRobustness.basisLabel)
        DetailRow("Functional", nowState.functionalContext.detail)
        DetailRow("Autonomic", nowState.autonomicContext.detail)
        DetailRow("Stability", nowState.stateStability.detail)
        morningRead?.let {
            DetailRow("Date", it.sourceDate ?: "unknown")
            DetailRow("Signal source", autonomicSourceDisplayLabel(it.overnightAutonomicSource))
            DetailRow("RMSSD", it.nightlyRmssd?.toInt()?.toString() ?: "n/a")
        }
    }
}

@Composable
private fun SleepRestEvidenceContent(nowState: NowScreenState) {
    val window = nowState.activeAnalysisWindow
    val morningRead = nowState.activeMorningRead
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailRow("Window", window.label)
        DetailRow("Reason", window.reason)
        DetailRow("Time", window.timeRangeLabel)
        DetailRow("Duration", window.durationLabel)
        DetailRow("Confidence", window.confidenceLabel)
        DetailRow("Selected by user", if (window.selectedByUser) "Yes" else "No")
        DetailRow("Marker", nowState.markerStatus.detail)
        morningRead?.let {
            DetailRow("Loop report", morningReadReportStateLabel(it))
            DetailRow("Sleep total", formatDurationMinutes(it.sleepDurationMinutes))
        }
    }
}

@Composable
private fun DataQualityEvidenceContent(nowState: NowScreenState) {
    val robustness = nowState.signalRobustness
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DetailRow("State", robustness.label)
        DetailRow("Missing", robustness.missingInputs.joinToString().ifBlank { "None" })
        DetailRow("Supporting gaps", robustness.supportingGaps.joinToString().ifBlank { "None" })
        DetailRow("Sleep report", robustness.sleepReport.detail)
        DetailRow("PPI", robustness.ppi.detail)
        DetailRow("Baseline", robustness.baseline.detail)
        DetailRow("Nightly Recharge", robustness.nightlyRecharge.detail)
        DetailRow("Last sync", nowState.freshness.loopSync.detail)
        DetailRow("Marker", nowState.markerStatus.detail)
    }
}

@Composable
private fun HrvEvidenceContent(
    nowState: NowScreenState,
    onOpenHrvTrajectory: () -> Unit
) {
    val morningRead = nowState.activeMorningRead
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (morningRead == null) {
            SupportText(nowState.readinessStatus.hrvDetail)
        } else {
            DetailRow("Signal basis", morningReadBasisLabel(morningRead, nowState.readinessStatus))
            DetailRow("Window", morningRead.analysisWindowLabel())
            DetailRow("Usable windows", (morningRead.rawPpiGoodEpochCount ?: 0).toString())
            DetailRow(
                "Coverage",
                morningRead.rawPpiCoverageHours?.let { String.format(java.util.Locale.UK, "%.1fh", it) } ?: "n/a"
            )
            if ((morningRead.rawPpiPoorEpochCount ?: 0) > 0) {
                DetailRow("Flagged windows", morningRead.rawPpiPoorEpochCount.toString())
            }
            TextButton(
                onClick = onOpenHrvTrajectory,
                enabled = morningRead.hrvTrajectory.isNotEmpty()
            ) {
                Text("Open autonomic detail")
            }
        }
    }
}

fun morningReadBasisLabel(
    morningRead: AnalysisWindowEvidence?,
    todayStatus: TodayReadinessStatus
): String =
    when {
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT ->
            "Calibrated sleep window + PPI, Loop report pending"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT ->
            "Manual sleep window + PPI, Loop report pending"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT ->
            "PPI-inferred sleep window, Loop report pending"
        morningRead?.morningReadSource() == AnalysisWindowSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT ->
            "Manual sleep window, autonomic unavailable"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "Calibrated sleep window + PPI, Loop report as context"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "Manual sleep window + PPI, Loop report as context"
        morningRead?.morningReadSource() == AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT ->
            "PPI-inferred sleep window, Loop report as context"
        morningRead?.sleepDataReady == true && morningRead.hasPpiSignal() ->
            "PPI aligned to Loop sleep report"
        morningRead?.sleepDataReady == true ->
            "Loop sleep context only"
        morningRead?.isInterim == true ->
            "Current signal, Loop report pending"
        todayStatus.stage == TodayReadinessStage.SLEEP_TIME ->
            "Waiting for wake sync"
        else ->
            "Waiting for morning data"
    }

internal fun AnalysisWindowEvidence.analysisWindowLabel(): String =
    when (morningReadSource()) {
        AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT -> "calibrated sleep window"
        AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT -> "manual marker-derived sleep window"
        AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT -> "PPI-inferred sleep window"
        AnalysisWindowSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT -> "manual marker-derived sleep window"
        AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "calibrated primary window"
        AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "manual primary window"
        AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "PPI-inferred primary window"
        AnalysisWindowSource.PPI247_SLEEP_WINDOW -> "Loop sleep report window"
        AnalysisWindowSource.SLEEP_CONTEXT_ONLY -> "Loop sleep context"
        else -> if (sleepDataReady) "resolved sleep window" else "sleep/rest window"
    }

private fun AnalysisWindowEvidence.morningReadSource(): AnalysisWindowSource? =
    AnalysisWindowSource.fromKey(overnightAutonomicSource)

private fun stabilityLabel(morningRead: AnalysisWindowEvidence?): String? {
    val goodEpochs = morningRead?.rawPpiGoodEpochCount ?: return null
    val poorEpochs = morningRead.rawPpiPoorEpochCount ?: 0
    val coverageHours = morningRead.rawPpiCoverageHours ?: return null
    return when {
        goodEpochs < 12 || coverageHours < 3.0 -> "Brittle"
        poorEpochs > goodEpochs / 3 -> "Brittle"
        poorEpochs > 0 || coverageHours < 5.0 -> "Mixed"
        else -> "Stable"
    }
}

private fun morningReadReportStateLabel(morningRead: AnalysisWindowEvidence): String =
    when {
        morningRead.sleepDataReady -> "Loop report attached"
        morningRead.isInterim -> "Loop report pending"
        else -> "Pending"
    }

private fun autonomicSourceDisplayLabel(source: String): String =
    when (AnalysisWindowSource.fromKey(source)) {
        AnalysisWindowSource.USER_CONFIRMED_NO_SLEEP -> "User confirmed no main sleep"
        AnalysisWindowSource.EDITED_SLEEP_EPISODE_PRIMARY -> "Edited sleep episode (primary)"
        AnalysisWindowSource.MIXED_SLEEP_EPISODE_PRIMARY -> "Mixed sleep episode (primary)"
        AnalysisWindowSource.MANUAL_SLEEP_EPISODE_PRIMARY -> "Manual sleep episode (primary)"
        AnalysisWindowSource.CONFIRMED_SLEEP_EPISODE_PRIMARY -> "Confirmed sleep episode (primary)"
        AnalysisWindowSource.PPI247_SLEEP_WINDOW -> "24/7 PPI aligned to sleep"
        AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT -> "24/7 PPI, calibrated sleep window"
        AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT -> "24/7 PPI, manual sleep window"
        AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT -> "24/7 PPI, inferred sleep window"
        AnalysisWindowSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT -> "Manual sleep window, no autonomic signal"
        AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "24/7 PPI, calibrated primary window"
        AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "24/7 PPI, manual primary window"
        AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT -> "24/7 PPI, inferred primary window"
        AnalysisWindowSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW -> "24/7 PPI, waiting for bedtime marker"
        AnalysisWindowSource.RAW_PPI_PENDING_SLEEP_WINDOW -> "24/7 PPI, waiting for sleep/rest window"
        AnalysisWindowSource.NIGHTLY_RECHARGE_SUMMARY -> "Nightly Recharge summary"
        AnalysisWindowSource.SLEEP_CONTEXT_ONLY -> "Sleep/context only"
        AnalysisWindowSource.AWAITING_SLEEP_DATA -> "Awaiting sleep data"
        else -> source
    }

@Composable
fun HrvTrajectoryDialog(
    morningRead: AnalysisWindowEvidence,
    onDismiss: () -> Unit
) {
    val points = morningRead.hrvTrajectory.sortedBy { it.epochStartEpochMs }
    val average = points.map { it.rmssdMs }.averageOrNull()
    val early = points.take((points.size / 3).coerceAtLeast(1)).map { it.rmssdMs }.averageOrNull()
    val late = points.takeLast((points.size / 3).coerceAtLeast(1)).map { it.rmssdMs }.averageOrNull()
    val delta = if (early != null && late != null) late - early else null
    val trend = hrvTrendSummary(points)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep/rest HRV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    morningReadBasisLabel(
                        morningRead = morningRead,
                        todayStatus = TodayReadinessStatus(
                            stage = TodayReadinessStage.UPDATE_COMPLETE,
                            title = "",
                            sleepReport = "",
                            ppiReceipt = "",
                            message = "",
                            hrvDetail = "",
                            dataQuality = signalConfidenceSummary(TodayReadinessStage.UPDATE_COMPLETE, morningRead)
                        )
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HrvTrajectoryChart(points = points)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Windows", points.size.toString())
                    DetailRow("Average RMSSD", average?.let { "${it.toInt()} ms" } ?: "n/a")
                    DetailRow("Early -> late", delta?.let { formatSignedMs(it) } ?: "n/a")
                    DetailRow("Shape", trend.shapeLabel)
                    DetailRow("Linear trend", formatSignedMs(trend.linearDeltaMs))
                    DetailRow("Range", hrvRangeLabel(points))
                    DetailRow("Time span", hrvTimeSpanLabel(points))
                }
                SupportText("Faint line = raw RMSSD, bold line = rolling median, straight line = linear trend. This is qualitative for now, not a diagnosis.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun HrvTrajectoryChart(points: List<HrvTrajectoryPoint>) {
    val rawLineColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.34f)
    val smoothLineColor = MaterialTheme.colorScheme.primary
    val trendLineColor = MaterialTheme.colorScheme.tertiary
    val guideColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val values = points.map { it.rmssdMs }
    val minValue = values.minOrNull() ?: 0.0
    val maxValue = values.maxOrNull() ?: 1.0
    val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val paddedMin = (minValue - span * 0.12).coerceAtLeast(0.0)
    val paddedMax = maxValue + span * 0.12
    val firstTime = points.firstOrNull()?.epochStartEpochMs ?: 0L
    val lastTime = points.lastOrNull()?.epochStartEpochMs ?: firstTime + 1L
    val timeSpan = (lastTime - firstTime).coerceAtLeast(1L).toFloat()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(fillColor)
            .padding(8.dp)
    ) {
        val left = 18.dp.toPx()
        val right = size.width - 10.dp.toPx()
        val top = 14.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        repeat(4) { index ->
            val y = top + (bottom - top) * index / 3f
            drawLine(
                color = guideColor,
                start = androidx.compose.ui.geometry.Offset(left, y),
                end = androidx.compose.ui.geometry.Offset(right, y),
                strokeWidth = 1.dp.toPx()
            )
        }
        if (points.size < 2) return@Canvas
        val rawPath = hrvPath(points, paddedMin, paddedMax, firstTime, timeSpan, left, right, top, bottom)
        drawPath(
            path = rawPath,
            color = rawLineColor,
            style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        )
        val smoothPoints = rollingMedianPoints(points, windowSize = 5)
        val smoothPath = hrvPath(smoothPoints, paddedMin, paddedMax, firstTime, timeSpan, left, right, top, bottom)
        drawPath(
            path = smoothPath,
            color = smoothLineColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
        val trend = hrvTrendSummary(points)
        val trendStartY = valueToChartY(trend.startValueMs, paddedMin, paddedMax, top, bottom)
        val trendEndY = valueToChartY(trend.endValueMs, paddedMin, paddedMax, top, bottom)
        drawLine(
            color = trendLineColor.copy(alpha = 0.74f),
            start = androidx.compose.ui.geometry.Offset(left, trendStartY),
            end = androidx.compose.ui.geometry.Offset(right, trendEndY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        val startLabel = formatEpochTime(firstTime)
        val endLabel = formatEpochTime(lastTime)
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = labelColor.toArgb()
                textSize = 11.sp.toPx()
                isAntiAlias = true
            }
            drawText(startLabel, left, size.height - 8.dp.toPx(), paint)
            drawText(endLabel, right - measureText(endLabel, paint), size.height - 8.dp.toPx(), paint)
        }
    }
}

internal fun measureText(text: String, paint: android.graphics.Paint): Float =
    paint.measureText(text)

internal data class HrvTrendSummary(
    val startValueMs: Double,
    val endValueMs: Double,
    val linearDeltaMs: Double,
    val shapeLabel: String
)

internal fun hrvPath(
    points: List<HrvTrajectoryPoint>,
    minValue: Double,
    maxValue: Double,
    firstTime: Long,
    timeSpan: Float,
    left: Float,
    right: Float,
    top: Float,
    bottom: Float
): Path {
    val path = Path()
    points.forEachIndexed { index, point ->
        val x = left + (right - left) * ((point.epochStartEpochMs - firstTime).toFloat() / timeSpan)
        val y = valueToChartY(point.rmssdMs, minValue, maxValue, top, bottom)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    return path
}

internal fun valueToChartY(
    value: Double,
    minValue: Double,
    maxValue: Double,
    top: Float,
    bottom: Float
): Float {
    val yRatio = ((value - minValue) / (maxValue - minValue)).toFloat().coerceIn(0f, 1f)
    return bottom - (bottom - top) * yRatio
}

internal fun rollingMedianPoints(
    points: List<HrvTrajectoryPoint>,
    windowSize: Int
): List<HrvTrajectoryPoint> {
    if (points.size <= 2) return points
    val halfWindow = windowSize / 2
    return points.mapIndexed { index, point ->
        val start = (index - halfWindow).coerceAtLeast(0)
        val endExclusive = (index + halfWindow + 1).coerceAtMost(points.size)
        val median = points.subList(start, endExclusive).map { it.rmssdMs }.medianOrNull() ?: point.rmssdMs
        point.copy(rmssdMs = median)
    }
}

internal fun hrvTrendSummary(points: List<HrvTrajectoryPoint>): HrvTrendSummary {
    if (points.size < 2) {
        val value = points.firstOrNull()?.rmssdMs ?: 0.0
        return HrvTrendSummary(value, value, 0.0, "Not enough data")
    }
    val firstTime = points.first().epochStartEpochMs.toDouble()
    val lastTime = points.last().epochStartEpochMs.toDouble()
    val timeSpan = (lastTime - firstTime).takeIf { it > 0.0 } ?: 1.0
    val xValues = points.map { (it.epochStartEpochMs.toDouble() - firstTime) / timeSpan }
    val yValues = points.map { it.rmssdMs }
    val meanX = xValues.average()
    val meanY = yValues.average()
    val denominator = xValues.sumOf { (it - meanX) * (it - meanX) }
    val slope = if (denominator == 0.0) {
        0.0
    } else {
        xValues.zip(yValues).sumOf { (x, y) -> (x - meanX) * (y - meanY) } / denominator
    }
    val intercept = meanY - slope * meanX
    val startValue = intercept
    val endValue = intercept + slope
    val smooth = rollingMedianPoints(points, windowSize = 5)
    val thirdSize = (smooth.size / 3).coerceAtLeast(1)
    val early = smooth.take(thirdSize).map { it.rmssdMs }.averageOrNull()
    val middleStart = ((smooth.size - thirdSize) / 2).coerceAtLeast(0)
    val middle = smooth.drop(middleStart).take(thirdSize).map { it.rmssdMs }.averageOrNull()
    val late = smooth.takeLast(thirdSize).map { it.rmssdMs }.averageOrNull()
    val shape = hrvShapeLabel(early, middle, late, endValue - startValue)
    return HrvTrendSummary(startValue, endValue, endValue - startValue, shape)
}

private fun hrvShapeLabel(
    early: Double?,
    middle: Double?,
    late: Double?,
    linearDelta: Double
): String {
    if (early == null || middle == null || late == null) return "Mixed"
    val minEdge = minOf(early, late)
    val maxEdge = maxOf(early, late)
    val threshold = 8.0
    return when {
        middle <= minEdge - threshold -> "Dip then recovery"
        middle >= maxEdge + threshold -> "Mid-night peak"
        linearDelta >= threshold -> "Rising"
        linearDelta <= -threshold -> "Falling"
        else -> "Mostly flat / mixed"
    }
}

internal fun List<Double>.averageOrNull(): Double? =
    if (isEmpty()) null else average()

private fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun formatSignedMs(value: Double): String {
    val sign = if (value >= 0) "+" else "-"
    return "$sign${kotlin.math.abs(value).toInt()} ms"
}

private fun hrvRangeLabel(points: List<HrvTrajectoryPoint>): String {
    val values = points.map { it.rmssdMs }
    val min = values.minOrNull()?.toInt() ?: return "n/a"
    val max = values.maxOrNull()?.toInt() ?: return "n/a"
    return "$min-$max ms"
}

private fun hrvTimeSpanLabel(points: List<HrvTrajectoryPoint>): String {
    val first = points.firstOrNull()?.epochStartEpochMs ?: return "n/a"
    val last = points.lastOrNull()?.epochStartEpochMs ?: return "n/a"
    return "${formatEpochTime(first)}-${formatEpochTime(last)}"
}

private fun formatEpochTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.UK)
        .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
