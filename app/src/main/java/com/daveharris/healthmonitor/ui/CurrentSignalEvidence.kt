package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.AnalysisWindowSource
import com.daveharris.healthmonitor.data.AnalysisWindowEvidence

internal const val MIN_READY_PPI_GOOD_EPOCHS = 12
internal const val MIN_READY_PPI_COVERAGE_HOURS = 3.0

internal fun AnalysisWindowEvidence?.hasPpiSignal(): Boolean =
    this?.rawPpiGoodEpochCount != null ||
        when (AnalysisWindowSource.fromKey(this?.overnightAutonomicSource)) {
            AnalysisWindowSource.PPI247_SLEEP_WINDOW,
            AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT,
            AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT,
            AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT,
            AnalysisWindowSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT,
            AnalysisWindowSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT,
            AnalysisWindowSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT,
            AnalysisWindowSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW,
            AnalysisWindowSource.RAW_PPI_PENDING_SLEEP_WINDOW -> true
            else -> false
        }

internal fun AnalysisWindowEvidence?.hasEstablishedSleepWindow(): Boolean =
    this?.sleepDataReady == true ||
        AnalysisWindowSource.fromKey(this?.overnightAutonomicSource)?.hasEstablishedSleepWindow == true

internal fun AnalysisWindowEvidence?.hasSufficientReadyPpiCoverage(): Boolean =
    (this?.rawPpiGoodEpochCount ?: 0) >= MIN_READY_PPI_GOOD_EPOCHS &&
        (this?.rawPpiCoverageHours ?: 0.0) >= MIN_READY_PPI_COVERAGE_HOURS
