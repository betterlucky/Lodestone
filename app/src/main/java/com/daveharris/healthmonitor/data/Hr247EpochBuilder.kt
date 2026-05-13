package com.daveharris.healthmonitor.data

import java.time.Instant
import java.time.ZoneId

object Hr247EpochBuilder {
    const val EPOCH_MINUTES = 5L
    const val MIN_HR_BPM = 25
    const val MAX_HR_BPM = 240

    data class Sample(
        val timestampEpochMs: Long,
        val deviceId: String,
        val hrBpm: Int,
        val triggerType: String
    ) {
        val isUsable: Boolean
            get() = hrBpm in MIN_HR_BPM..MAX_HR_BPM
    }

    fun derive(samples: List<Sample>, updatedAtEpochMs: Long): List<Hr247EpochEntity> {
        if (samples.isEmpty()) return emptyList()
        val epochMs = EPOCH_MINUTES * 60_000L
        return samples
            .groupBy { floorToEpochWindow(it.timestampEpochMs, epochMs) }
            .toSortedMap()
            .map { (epochStart, epochSamples) ->
                val usable = epochSamples.filter { it.isUsable }
                val hrs = usable.map { it.hrBpm.toDouble() }
                val quality = when {
                    usable.isEmpty() -> "poor_invalid"
                    usable.size < epochSamples.size -> "usable_with_invalid_samples"
                    else -> "good"
                }
                Hr247EpochEntity(
                    deviceId = epochSamples.first().deviceId,
                    sourceDate = Instant.ofEpochMilli(epochStart).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                    epochStartEpochMs = epochStart,
                    epochEndEpochMs = epochStart + epochMs,
                    sampleCount = epochSamples.size,
                    meanHrBpm = averageOrNull(hrs),
                    medianHrBpm = percentile(hrs, 0.50),
                    minHrBpm = hrs.minOrNull()?.toInt(),
                    maxHrBpm = hrs.maxOrNull()?.toInt(),
                    triggerTypesCsv = epochSamples.map { it.triggerType }.distinct().sorted().joinToString(","),
                    epochQuality = quality,
                    updatedAtEpochMs = updatedAtEpochMs
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
}
