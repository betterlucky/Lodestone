package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.MorningReadSource
import com.daveharris.healthmonitor.data.MorningReadSnapshot

internal const val MIN_READY_PPI_GOOD_EPOCHS = 12
internal const val MIN_READY_PPI_COVERAGE_HOURS = 3.0

internal fun MorningReadSnapshot?.hasPpiSignal(): Boolean =
    this?.rawPpiGoodEpochCount != null ||
        when (MorningReadSource.fromKey(this?.overnightAutonomicSource)) {
            MorningReadSource.PPI247_SLEEP_WINDOW,
            MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT,
            MorningReadSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT,
            MorningReadSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT,
            MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT,
            MorningReadSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT,
            MorningReadSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT,
            MorningReadSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW,
            MorningReadSource.RAW_PPI_PENDING_SLEEP_WINDOW -> true
            else -> false
        }

internal fun MorningReadSnapshot?.hasEstablishedSleepWindow(): Boolean =
    this?.sleepDataReady == true ||
        MorningReadSource.fromKey(this?.overnightAutonomicSource)?.hasEstablishedSleepWindow == true

internal fun MorningReadSnapshot?.hasSufficientReadyPpiCoverage(): Boolean =
    (this?.rawPpiGoodEpochCount ?: 0) >= MIN_READY_PPI_GOOD_EPOCHS &&
        (this?.rawPpiCoverageHours ?: 0.0) >= MIN_READY_PPI_COVERAGE_HOURS
