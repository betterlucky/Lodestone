package com.daveharris.healthmonitor.data

internal object Ppi247ScopeSummarizer {
    fun summarize(
        scope: AutonomicDetailScope,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        epochs: List<Ppi247EpochEntity>,
        sourceDate: String? = null,
        nowEpochMs: Long = System.currentTimeMillis()
    ): ScopeEpochBreakdown {
        if (windowEndEpochMs <= windowStartEpochMs) {
            return ScopeEpochBreakdown.empty(windowStartEpochMs, windowEndEpochMs)
        }
        val windowDurationMs = windowEndEpochMs - windowStartEpochMs
        val sortedEpochs = epochs
            .asSequence()
            .filter { epoch ->
                when {
                    scope == AutonomicDetailScope.ACTIVE_SLEEP_REST -> {
                        epoch.epochStartEpochMs >= windowStartEpochMs &&
                            epoch.epochEndEpochMs <= windowEndEpochMs &&
                            (sourceDate == null || epoch.sourceDate == sourceDate || epoch.epochStartEpochMs >= windowStartEpochMs)
                    }
                    else -> {
                        // Overlap test (not start-inside): an epoch that began before the
                        // window but ends inside it still contributes coverage near the
                        // left edge. Body clips its contribution via overlapDurationMs.
                        epoch.epochEndEpochMs > windowStartEpochMs &&
                            epoch.epochStartEpochMs < windowEndEpochMs
                    }
                }
            }
            .sortedBy { it.epochStartEpochMs }
            .toList()

        val chartPoints = mutableListOf<HrvTrajectoryPoint>()
        val summaryEpochs = mutableListOf<Ppi247EpochEntity>()
        var reviewCount = 0
        var poorCount = 0
        var excludedMotionContactCount = 0

        sortedEpochs.forEach { epoch ->
            when {
                epoch.epochQuality in SUMMARY_EPOCH_QUALITIES && epoch.rmssdMs != null -> {
                    if (scope.isRolling) {
                        val insideMs = overlapDurationMs(
                            epoch.epochStartEpochMs,
                            epoch.epochEndEpochMs,
                            windowStartEpochMs,
                            windowEndEpochMs
                        )
                        val epochDurationMs = (epoch.epochEndEpochMs - epoch.epochStartEpochMs).coerceAtLeast(1L)
                        if (insideMs < epochDurationMs / 2) return@forEach
                        if (isRollingExcluded(epoch)) {
                            excludedMotionContactCount++
                            return@forEach
                        }
                    }
                    summaryEpochs += epoch
                    chartPoints += HrvTrajectoryPoint(
                        epochStartEpochMs = epoch.epochStartEpochMs,
                        rmssdMs = epoch.rmssdMs,
                        epochQuality = epoch.epochQuality
                    )
                }
                epoch.epochQuality in REVIEW_EPOCH_QUALITIES -> reviewCount++
                epoch.epochQuality in POOR_EPOCH_QUALITIES || epoch.epochQuality.startsWith("poor") -> poorCount++
            }
        }

        val summaryMinutes = summaryEpochs.sumOf { epoch ->
            val durationMs = (epoch.epochEndEpochMs - epoch.epochStartEpochMs).coerceAtLeast(0L)
            if (scope.isRolling) {
                overlapDurationMs(
                    epoch.epochStartEpochMs,
                    epoch.epochEndEpochMs,
                    windowStartEpochMs,
                    windowEndEpochMs
                ) / 60_000L
            } else {
                durationMs / 60_000L
            }
        }.toInt()
        val windowDurationMinutes = (windowDurationMs / 60_000L).toInt().coerceAtLeast(1)
        val coveragePercent = ((summaryMinutes.toDouble() / windowDurationMinutes) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        val longestGapMinutes = longestGapMinutes(
            summaryEpochs = summaryEpochs,
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs
        )

        return ScopeEpochBreakdown(
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMs = windowEndEpochMs,
            trajectoryPoints = chartPoints,
            summaryEpochs = summaryEpochs,
            reviewEpochCount = reviewCount,
            poorEpochCount = poorCount,
            excludedMotionContactCount = excludedMotionContactCount,
            coveragePercent = coveragePercent,
            longestGapMinutes = longestGapMinutes,
            windowDurationMinutes = windowDurationMinutes
        )
    }

    private fun isRollingExcluded(epoch: Ppi247EpochEntity): Boolean {
        val sampleCount = epoch.sampleCount.coerceAtLeast(1)
        val movementRatio = epoch.movementDetectedCount.toDouble() / sampleCount
        val contactRatio = epoch.skinContactFalseCount.toDouble() / sampleCount
        val offlineRatio = epoch.offlineIntervalCount.toDouble() / sampleCount
        return movementRatio > 0.25 || contactRatio > 0.20 || offlineRatio > 0.10
    }

    private fun overlapDurationMs(
        epochStart: Long,
        epochEnd: Long,
        windowStart: Long,
        windowEnd: Long
    ): Long {
        val start = maxOf(epochStart, windowStart)
        val end = minOf(epochEnd, windowEnd)
        return (end - start).coerceAtLeast(0L)
    }

    private fun longestGapMinutes(
        summaryEpochs: List<Ppi247EpochEntity>,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long
    ): Int {
        if (summaryEpochs.isEmpty()) {
            return ((windowEndEpochMs - windowStartEpochMs) / 60_000L).toInt()
        }
        var longestGapMs = (summaryEpochs.first().epochStartEpochMs - windowStartEpochMs).coerceAtLeast(0L)
        for (index in 1 until summaryEpochs.size) {
            val gapMs = summaryEpochs[index].epochStartEpochMs - summaryEpochs[index - 1].epochEndEpochMs
            if (gapMs > longestGapMs) longestGapMs = gapMs
        }
        val tailGapMs = (windowEndEpochMs - summaryEpochs.last().epochEndEpochMs).coerceAtLeast(0L)
        if (tailGapMs > longestGapMs) longestGapMs = tailGapMs
        return (longestGapMs / 60_000L).toInt()
    }
}

internal data class ScopeEpochBreakdown(
    val windowStartEpochMs: Long,
    val windowEndEpochMs: Long,
    val trajectoryPoints: List<HrvTrajectoryPoint>,
    val summaryEpochs: List<Ppi247EpochEntity>,
    val reviewEpochCount: Int,
    val poorEpochCount: Int,
    val excludedMotionContactCount: Int,
    val coveragePercent: Int,
    val longestGapMinutes: Int,
    val windowDurationMinutes: Int
) {
    val summaryEpochCount: Int get() = summaryEpochs.size
    val goodEpochCount: Int get() = summaryEpochs.count { it.epochQuality == "good" }
    val usableEpochCount: Int get() = summaryEpochs.count { it.epochQuality == "usable" }

    companion object {
        fun empty(windowStartEpochMs: Long, windowEndEpochMs: Long): ScopeEpochBreakdown =
            ScopeEpochBreakdown(
                windowStartEpochMs = windowStartEpochMs,
                windowEndEpochMs = windowEndEpochMs,
                trajectoryPoints = emptyList(),
                summaryEpochs = emptyList(),
                reviewEpochCount = 0,
                poorEpochCount = 0,
                excludedMotionContactCount = 0,
                coveragePercent = 0,
                longestGapMinutes = 0,
                windowDurationMinutes = 0
            )
    }
}
