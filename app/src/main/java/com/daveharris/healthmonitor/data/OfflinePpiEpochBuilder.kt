package com.daveharris.healthmonitor.data

import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt

object OfflinePpiEpochBuilder {
    const val EPOCH_MINUTES = 5L
    const val MIN_USABLE_SAMPLES_PER_EPOCH = 120
    const val MIN_PPI_MS = 300
    const val MAX_PPI_MS = 2200
    const val MAX_ERROR_ESTIMATE_MS = 50

    data class Sample(
        val timestampEpochMs: Long,
        val ppiMs: Int,
        val errorEstimateMs: Int,
        val hrBpm: Int,
        val blockerBit: Boolean,
        val skinContactStatus: Boolean
    ) {
        val isUsable: Boolean
            get() = ppiMs in MIN_PPI_MS..MAX_PPI_MS &&
                !blockerBit &&
                skinContactStatus &&
                errorEstimateMs <= MAX_ERROR_ESTIMATE_MS
    }

    fun derive(
        recordingPath: String,
        syncDomainResultId: Long,
        syncRunId: Long,
        deviceId: String,
        samples: List<Sample>
    ): List<OfflinePpiEpochEntity> {
        if (samples.isEmpty()) return emptyList()
        val epochMs = EPOCH_MINUTES * 60_000L
        val firstEpochStart = floorToEpochWindow(samples.first().timestampEpochMs, epochMs)
        return samples
            .groupBy { floorToEpochWindow(it.timestampEpochMs, epochMs) }
            .toSortedMap()
            .map { (epochStart, epochSamples) ->
                val usable = epochSamples.filter { it.isUsable }
                val ppis = usable.map { it.ppiMs.toDouble() }
                val hrs = usable.map { it.hrBpm }
                val errors = epochSamples.map { it.errorEstimateMs.toDouble() }
                val errorP90 = percentile(errors, 0.90)
                val hrRange = hrs.takeIf { it.isNotEmpty() }?.let { it.max() - it.min() }
                val quality = when {
                    usable.size < MIN_USABLE_SAMPLES_PER_EPOCH -> "poor_sparse"
                    usable.size.toDouble() / epochSamples.size.toDouble() < 0.75 -> "poor_contact_or_error"
                    (errorP90 ?: 0.0) > 100.0 || (hrRange ?: 0) > 50 -> "review"
                    (errorP90 ?: Double.MAX_VALUE) <= 25.0 -> "good"
                    else -> "usable"
                }
                OfflinePpiEpochEntity(
                    recordingPath = recordingPath,
                    syncDomainResultId = syncDomainResultId,
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    sourceDate = Instant.ofEpochMilli(epochStart).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                    epochStartEpochMs = epochStart,
                    epochEndEpochMs = epochStart + epochMs,
                    epochIndex = ((epochStart - firstEpochStart) / epochMs).toInt(),
                    sampleCount = epochSamples.size,
                    usableSampleCount = usable.size,
                    skinContactFalseCount = epochSamples.count { !it.skinContactStatus },
                    blockerCount = epochSamples.count { it.blockerBit },
                    highErrorCount = epochSamples.count { it.errorEstimateMs > MAX_ERROR_ESTIMATE_MS },
                    ppiLowCount = epochSamples.count { it.ppiMs < MIN_PPI_MS },
                    ppiHighCount = epochSamples.count { it.ppiMs > MAX_PPI_MS },
                    meanPpiMs = averageOrNull(ppis),
                    medianPpiMs = percentile(ppis, 0.50),
                    ppiP10Ms = percentile(ppis, 0.10),
                    ppiP90Ms = percentile(ppis, 0.90),
                    rmssdMs = rmssdOrNull(ppis),
                    meanHrBpm = averageOrNull(hrs.map { it.toDouble() }),
                    minHrBpm = hrs.minOrNull(),
                    maxHrBpm = hrs.maxOrNull(),
                    medianErrorEstimateMs = percentile(errors, 0.50),
                    errorEstimateP90Ms = errorP90,
                    epochQuality = quality
                )
            }
    }

    private fun floorToEpochWindow(epochMs: Long, windowMs: Long): Long =
        (epochMs / windowMs) * windowMs

    private fun averageOrNull(values: List<Double>): Double? =
        values.takeIf { it.isNotEmpty() }?.average()

    private fun percentile(values: List<Double>, quantile: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = quantile.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = position.toInt()
        val upper = (lower + 1).coerceAtMost(sorted.lastIndex)
        val fraction = position - lower
        return sorted[lower] + ((sorted[upper] - sorted[lower]) * fraction)
    }

    private fun rmssdOrNull(values: List<Double>): Double? {
        if (values.size < 2) return null
        val meanSquaredDiff = values
            .zipWithNext { previous, current -> val diff = current - previous; diff * diff }
            .average()
        return sqrt(meanSquaredDiff)
    }
}
