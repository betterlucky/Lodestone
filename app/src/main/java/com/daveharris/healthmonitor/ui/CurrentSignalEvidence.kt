package com.daveharris.healthmonitor.ui

import com.daveharris.healthmonitor.data.MorningReadSource
import com.daveharris.healthmonitor.data.MorningReadSnapshot

internal const val MIN_READY_PPI_GOOD_EPOCHS = 12
internal const val MIN_READY_PPI_COVERAGE_HOURS = 3.0

internal fun MorningReadSnapshot?.hasPpiSignal(): Boolean =
    this?.rawPpiGoodEpochCount != null ||
        this?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true

internal fun MorningReadSnapshot?.hasEstablishedSleepWindow(): Boolean =
    this?.sleepDataReady == true ||
        MorningReadSource.fromKey(this?.overnightAutonomicSource)?.hasEstablishedSleepWindow == true

internal fun MorningReadSnapshot?.hasSufficientReadyPpiCoverage(): Boolean =
    (this?.rawPpiGoodEpochCount ?: 0) >= MIN_READY_PPI_GOOD_EPOCHS &&
        (this?.rawPpiCoverageHours ?: 0.0) >= MIN_READY_PPI_COVERAGE_HOURS
