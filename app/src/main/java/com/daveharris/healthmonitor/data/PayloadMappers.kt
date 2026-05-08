package com.daveharris.healthmonitor.data

import com.daveharris.healthmonitor.util.GsonProvider
import com.polar.sdk.api.model.PolarSkinTemperatureData
import com.polar.sdk.api.model.activity.Polar247HrSamples
import com.polar.sdk.api.model.activity.Polar247HrSamplesData
import com.polar.sdk.api.model.activity.Polar247PPiSamplesData
import com.polar.sdk.api.model.activity.PolarActivityInfo
import com.polar.sdk.api.model.activity.PolarActivitySamplesData
import com.polar.sdk.api.model.activity.PolarActivitySamplesDayData
import com.polar.sdk.api.model.activity.PolarDailySummaryData
import com.polar.sdk.api.model.sleep.PolarNightlyRechargeData
import com.polar.sdk.api.model.sleep.PolarSleepData
import java.time.Duration

object PayloadMappers {
    fun sleepList(data: List<PolarSleepData>): String = GsonProvider.gson.toJson(data.map(::sleepDto))
    fun sleep(data: PolarSleepData): String = GsonProvider.gson.toJson(sleepDto(data))

    fun nightlyRechargeList(data: List<PolarNightlyRechargeData>): String = GsonProvider.gson.toJson(data.map(::nightlyRechargeDto))
    fun nightlyRecharge(data: PolarNightlyRechargeData): String = GsonProvider.gson.toJson(nightlyRechargeDto(data))

    fun hrList(data: List<Polar247HrSamplesData>): String = GsonProvider.gson.toJson(data.map(::hrDto))
    fun hr(data: Polar247HrSamplesData): String = GsonProvider.gson.toJson(hrDto(data))

    fun ppiList(data: List<Polar247PPiSamplesData>): String = GsonProvider.gson.toJson(data.map(::ppiDto))
    fun ppi(data: Polar247PPiSamplesData): String = GsonProvider.gson.toJson(ppiDto(data))

    fun skinTemperatureList(data: List<PolarSkinTemperatureData>): String = GsonProvider.gson.toJson(data.map(::skinTemperatureDto))
    fun skinTemperature(data: PolarSkinTemperatureData): String = GsonProvider.gson.toJson(skinTemperatureDto(data))

    fun dailySummaryList(data: List<PolarDailySummaryData>): String = GsonProvider.gson.toJson(data.map(::dailySummaryDto))
    fun dailySummary(data: PolarDailySummaryData): String = GsonProvider.gson.toJson(dailySummaryDto(data))

    fun activitySamplesList(data: List<PolarActivitySamplesDayData>): String = GsonProvider.gson.toJson(data.map(::activitySamplesDayDto))
    fun activitySamples(data: PolarActivitySamplesDayData): String = GsonProvider.gson.toJson(activitySamplesDayDto(data))

    private fun sleepDto(data: PolarSleepData) = mapOf(
        "date" to data.date,
        "result" to data.result?.let { result ->
            val sleepPhases = result.sleepWakePhases.orEmpty()
            val phaseCounts = sleepPhases.groupingBy { it.state.name }.eachCount()
            mapOf(
                "sleepStartTime" to result.sleepStartTime,
                "sleepEndTime" to result.sleepEndTime,
                "lastModified" to result.lastModified,
                "sleepGoalMinutes" to result.sleepGoalMinutes,
                "sleepStartOffsetSeconds" to result.sleepStartOffsetSeconds,
                "sleepEndOffsetSeconds" to result.sleepEndOffsetSeconds,
                "userSleepRating" to result.userSleepRating?.name,
                "deviceId" to result.deviceId,
                "batteryRanOut" to result.batteryRanOut,
                "sleepResultDate" to result.sleepResultDate,
                "originalSleepRange" to result.originalSleepRange?.let {
                    mapOf("startTime" to it.startTime, "endTime" to it.endTime)
                },
                "sleepWakePhases" to result.sleepWakePhases?.map {
                    mapOf("secondsFromSleepStart" to it.secondsFromSleepStart, "state" to it.state.name)
                },
                "sleepCycles" to result.sleepCycles?.map {
                    mapOf("secondsFromSleepStart" to it.secondsFromSleepStart, "sleepDepthStart" to it.sleepDepthStart)
                },
                "snoozeTime" to result.snoozeTime,
                "alarmTime" to result.alarmTime,
                "sleepSkinTemperatureResult" to result.sleepSkinTemperatureResult?.let {
                    mapOf(
                        "sleepResultDate" to it.sleepResultDate,
                        "sleepSkinTemperatureCelsius" to it.sleepSkinTemperatureCelsius,
                        "deviationFromBaseLine" to it.deviationFromBaseLine
                    )
                },
                "summary" to mapOf(
                    "sleepResultDate" to result.sleepResultDate,
                    "durationMinutes" to durationMinutes(result.sleepStartTime, result.sleepEndTime),
                    "goalDeltaMinutes" to durationMinutes(result.sleepStartTime, result.sleepEndTime)?.let { actual ->
                        result.sleepGoalMinutes?.let { goal -> actual - goal }
                    },
                    "phaseCounts" to phaseCounts,
                    "cycleCount" to result.sleepCycles?.size,
                    "batteryRanOut" to result.batteryRanOut
                )
            )
        }
    )

    private fun nightlyRechargeDto(data: PolarNightlyRechargeData) = mapOf(
        "createdTimestamp" to data.createdTimestamp,
        "modifiedTimestamp" to data.modifiedTimestamp,
        "ansStatus" to data.ansStatus,
        "recoveryIndicator" to data.recoveryIndicator,
        "recoveryIndicatorSubLevel" to data.recoveryIndicatorSubLevel,
        "ansRate" to data.ansRate,
        "scoreRateObsolete" to data.scoreRateObsolete,
        "meanNightlyRecoveryRRI" to data.meanNightlyRecoveryRRI,
        "meanNightlyRecoveryRMSSD" to data.meanNightlyRecoveryRMSSD,
        "meanNightlyRecoveryRespirationInterval" to data.meanNightlyRecoveryRespirationInterval,
        "meanBaselineRRI" to data.meanBaselineRRI,
        "sdBaselineRRI" to data.sdBaselineRRI,
        "meanBaselineRMSSD" to data.meanBaselineRMSSD,
        "sdBaselineRMSSD" to data.sdBaselineRMSSD,
        "meanBaselineRespirationInterval" to data.meanBaselineRespirationInterval,
        "sdBaselineRespirationInterval" to data.sdBaselineRespirationInterval,
        "sleepTip" to data.sleepTip,
        "vitalityTip" to data.vitalityTip,
        "exerciseTip" to data.exerciseTip,
        "sleepResultDate" to data.sleepResultDate,
        "summary" to mapOf(
            "sleepResultDate" to data.sleepResultDate,
            "baselineReady" to listOf(
                data.meanBaselineRRI,
                data.meanBaselineRMSSD,
                data.meanBaselineRespirationInterval
            ).all { (it ?: -1) >= 0 },
            "ansAvailable" to ((data.ansStatus?.toDouble() ?: -100.0) >= 0.0),
            "recoveryAvailable" to ((data.recoveryIndicator ?: -1) >= 0),
            "meanNightlyRecoveryRRI" to data.meanNightlyRecoveryRRI,
            "meanNightlyRecoveryRMSSD" to data.meanNightlyRecoveryRMSSD,
            "meanNightlyRecoveryRespirationInterval" to data.meanNightlyRecoveryRespirationInterval
        )
    )

    private fun hrDto(data: Polar247HrSamplesData): Map<String, Any?> {
        val hrValues = data.samples.flatMap { it.hrSamples }
        return mapOf(
            "date" to data.date,
            "samples" to data.samples.map(::hrSampleDto),
            "summary" to mapOf(
                "sessionCount" to data.samples.size,
                "sampleCount" to hrValues.size,
                "minHr" to hrValues.minOrNull(),
                "maxHr" to hrValues.maxOrNull(),
                "avgHr" to hrValues.takeIf { it.isNotEmpty() }?.average()
            )
        )
    }

    private fun hrSampleDto(data: Polar247HrSamples) = mapOf(
        "startTime" to data.startTime,
        "triggerType" to data.triggerType.name,
        "hrSamples" to data.hrSamples
    )

    private fun ppiDto(data: Polar247PPiSamplesData): Map<String, Any?> {
        val statuses = data.samples.statusList
        return mapOf(
            "date" to data.date,
            "samples" to mapOf(
                "startTime" to data.samples.startTime,
                "triggerType" to data.samples.triggerType.name,
                "ppiValueList" to data.samples.ppiValueList,
                "ppiErrorEstimateList" to data.samples.ppiErrorEstimateList,
                "statusList" to statuses.map {
                    mapOf(
                        "skinContact" to it.skinContact.name,
                        "movement" to it.movement.name,
                        "intervalStatus" to it.intervalStatus.name
                    )
                }
            ),
            "summary" to mapOf(
                "sampleCount" to data.samples.ppiValueList.size,
                "avgPpi" to data.samples.ppiValueList.takeIf { it.isNotEmpty() }?.average(),
                "avgErrorEstimate" to data.samples.ppiErrorEstimateList.takeIf { it.isNotEmpty() }?.average(),
                "movementDetectedCount" to statuses.count { it.movement.name != "MOVING_NOT_DETECTED" },
                "skinContactDetectedCount" to statuses.count { it.skinContact.name == "SKIN_CONTACT_DETECTED" },
                "onlineIntervalCount" to statuses.count { it.intervalStatus.name == "INTERVAL_IS_ONLINE" }
            )
        )
    }

    private fun skinTemperatureDto(data: PolarSkinTemperatureData) = mapOf(
        "date" to data.date,
        "result" to data.result?.let {
            val temperatures = it.skinTemperatureList?.map { sample -> sample.temperature }.orEmpty()
            mapOf(
                "deviceId" to it.deviceId,
                "sensorLocation" to it.sensorLocation?.name,
                "measurementType" to it.measurementType?.name,
                "skinTemperatureList" to it.skinTemperatureList?.map { sample ->
                    mapOf(
                        "recordingTimeDeltaMs" to sample.recordingTimeDeltaMs,
                        "temperature" to sample.temperature
                    )
                },
                "summary" to mapOf(
                    "sampleCount" to temperatures.size,
                    "minTemperature" to temperatures.minOrNull(),
                    "maxTemperature" to temperatures.maxOrNull(),
                    "avgTemperature" to temperatures.takeIf { it.isNotEmpty() }?.average()
                )
            )
        }
    )

    private fun dailySummaryDto(data: PolarDailySummaryData): Map<String, Any?> {
        val classTimes = data.activityClassTimes
        return mapOf(
            "date" to data.date,
            "activityCalories" to data.activityCalories,
            "trainingCalories" to data.trainingCalories,
            "bmrCalories" to data.bmrCalories,
            "steps" to data.steps,
            "activityDistance" to data.activityDistance,
            "activityGoalSummary" to data.activityGoalSummary?.let {
                mapOf(
                    "activityGoal" to it.activityGoal,
                    "achievedActivity" to it.achievedActivity,
                    "timeToGoUp" to activeTimeDto(it.timeToGoUp),
                    "timeToGoWalk" to activeTimeDto(it.timeToGoWalk),
                    "timeToGoJog" to activeTimeDto(it.timeToGoJog)
                )
            },
            "activityClassTimes" to classTimes?.let {
                mapOf(
                    "date" to it.date,
                    "timeNonWear" to activeTimeDto(it.timeNonWear),
                    "timeSleep" to activeTimeDto(it.timeSleep),
                    "timeSedentary" to activeTimeDto(it.timeSedentary),
                    "timeLightActivity" to activeTimeDto(it.timeLightActivity),
                    "timeContinuousModerateActivity" to activeTimeDto(it.timeContinuousModerateActivity),
                    "timeIntermittentModerateActivity" to activeTimeDto(it.timeIntermittentModerateActivity),
                    "timeContinuousVigorousActivity" to activeTimeDto(it.timeContinuousVigorousActivity),
                    "timeIntermittentVigorousActivity" to activeTimeDto(it.timeIntermittentVigorousActivity)
                )
            },
            "dailyBalanceFeedback" to data.dailyBalanceFeedback?.name,
            "readinessForSpeedAndStrengthTraining" to data.readinessForSpeedAndStrengthTraining?.name,
            "summary" to mapOf(
                "steps" to data.steps,
                "activityDistance" to data.activityDistance,
                "activityCalories" to data.activityCalories,
                "sleepMinutes" to classTimes?.timeSleep?.let(::activeTimeToMinutes),
                "sedentaryMinutes" to classTimes?.timeSedentary?.let(::activeTimeToMinutes),
                "lightActivityMinutes" to classTimes?.timeLightActivity?.let(::activeTimeToMinutes)
            )
        )
    }

    private fun activitySamplesDayDto(data: PolarActivitySamplesDayData): Map<String, Any?> {
        val samples = data.polarActivitySamplesDataList.orEmpty()
        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        return mapOf(
            "polarActivitySamplesDataList" to samples.map(::activitySamplesDto),
            "summary" to mapOf(
                "sessionCount" to samples.size,
                "firstStartTime" to first?.startTime,
                "lastStartTime" to last?.startTime,
                "metSampleCount" to samples.sumOf { it.metSamples.size },
                "stepSampleCount" to samples.sumOf { it.stepSamples.size },
                "activityInfoCount" to samples.sumOf { it.activityInfoList.size },
                "totalRecordedSteps" to samples.sumOf { it.stepSamples.sum() },
                "avgMet" to samples.flatMap { it.metSamples }.takeIf { it.isNotEmpty() }?.average()
            )
        )
    }

    private fun activitySamplesDto(data: PolarActivitySamplesData) = mapOf(
        "startTime" to data.startTime,
        "metRecordingInterval" to data.metRecordingInterval,
        "metSamples" to data.metSamples,
        "stepRecordingInterval" to data.stepRecordingInterval,
        "stepSamples" to data.stepSamples,
        "activityInfoList" to data.activityInfoList.map(::activityInfoDto)
    )

    private fun activityInfoDto(data: PolarActivityInfo) = mapOf(
        "activityClass" to data.activityClass?.name,
        "timeStamp" to data.timeStamp,
        "factor" to data.factor
    )

    private fun activeTimeDto(data: Any?): Map<String, Any?>? {
        if (data == null) return null
        return when (data) {
            is com.polar.sdk.api.model.activity.PolarActiveTime -> mapOf(
                "hours" to data.hours,
                "minutes" to data.minutes,
                "seconds" to data.seconds,
                "millis" to data.millis
            )
            else -> null
        }
    }

    private fun activeTimeToMinutes(data: com.polar.sdk.api.model.activity.PolarActiveTime): Int =
        (data.hours * 60) + data.minutes + if (data.seconds >= 30) 1 else 0

    private fun durationMinutes(start: java.time.ZonedDateTime?, end: java.time.ZonedDateTime?): Long? {
        if (start == null || end == null) return null
        return Duration.between(start, end).toMinutes()
    }
}
