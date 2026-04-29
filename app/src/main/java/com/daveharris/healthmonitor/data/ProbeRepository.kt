package com.daveharris.healthmonitor.data

import android.content.Context
import com.daveharris.healthmonitor.util.ExportManager
import com.daveharris.healthmonitor.util.GsonProvider
import com.daveharris.healthmonitor.polar.PolarProbeManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.PolarExerciseSession
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.PolarOfflineRecordingResult
import com.polar.sdk.api.model.PolarPpgData
import com.polar.sdk.api.model.activity.Polar247HrSamplesData
import com.polar.sdk.api.model.activity.Polar247PPiSamplesData
import com.polar.sdk.api.model.activity.PolarActivitySamplesDayData
import com.polar.sdk.api.model.activity.PolarDailySummaryData
import com.polar.sdk.api.model.sleep.PolarNightlyRechargeData
import com.polar.sdk.api.model.sleep.PolarSleepData
import com.polar.sdk.api.model.PolarSkinTemperatureData
import com.polar.sdk.api.model.trainingsession.PolarTrainingSession
import com.polar.sdk.api.model.trainingsession.PolarTrainingSessionFetchResult
import com.polar.sdk.api.model.trainingsession.PolarTrainingSessionReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val POLAR_TIMESTAMP_EPOCH: Instant = Instant.parse("2000-01-01T00:00:00Z")
class ProbeRepository(
    private val database: AppDatabase,
    private val polarManager: PolarProbeManager
) {
    private val dao = database.probeDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val runtimeState = polarManager.runtimeState
    val deviceProfile = dao.observeLatestDeviceProfile()
    val ftuProfile = dao.observeLatestFtuProfile()
    val observedCapabilities = dao.observeObservedCapabilities()
    val syncRuns = dao.observeSyncRuns()
    val syncDomainResults = dao.observeSyncDomainResults()
    val inspectorRows = dao.observeInspectorRows()
    val appSettings = dao.observeAppSettings()
    val latestOfflinePpiNightSummary = dao.observeLatestOfflinePpiNightSummary()
    val morningRead = combine(
        dao.observeLatestSleepRecord(),
        dao.observeLatestNightlyRechargeRecord(),
        dao.observeRecentOfflinePpiEpochs(),
        dao.observeRecentPpi247Epochs()
    ) { sleep, nightly, offlinePpiEpochs, ppi247Epochs ->
        deriveMorningRead(sleep, nightly, offlinePpiEpochs, ppi247Epochs)
    }

    suspend fun search(prefix: String? = "Polar"): List<com.polar.sdk.api.model.PolarDeviceInfo> {
        val found = linkedMapOf<String, com.polar.sdk.api.model.PolarDeviceInfo>()
        withTimeoutOrNull(6_000) {
            polarManager.searchForDevices(prefix).collect { device ->
                polarManager.recordSearchResult(device)
                found[device.deviceId] = device
            }
        }
        return found.values.toList()
    }

    suspend fun connect(deviceId: String) = polarManager.connectToDevice(deviceId)

    suspend fun disconnect(deviceId: String) = polarManager.disconnectDevice(deviceId)

    suspend fun saveAppSettings(
        selectedDeviceId: String?,
        syncWindowConfig: SyncWindowConfig,
        lastKnownFirmwareBySelectedDevice: String?
    ) {
        val normalizedConfig = syncWindowConfig.normalized()
        dao.upsertAppSettings(
            AppSettingsEntity(
                selectedDeviceId = selectedDeviceId,
                sleepDays = normalizedConfig.sleepDays,
                nightlyRechargeDays = normalizedConfig.nightlyRechargeDays,
                hrDays = normalizedConfig.hrDays,
                ppiDays = normalizedConfig.ppiDays,
                lastKnownFirmwareBySelectedDevice = lastKnownFirmwareBySelectedDevice
            )
        )
    }

    suspend fun getAppSettings(): AppSettingsEntity? = dao.getAppSettings()

    suspend fun hasSleepRecordForDate(sourceDate: String): Boolean =
        dao.getLatestSleepRecordForDate(sourceDate)?.hasResolvedSleepWindow() == true

    suspend fun recordWakeMarker(
        sourceDate: String,
        markerEpochMs: Long = System.currentTimeMillis(),
        markerSource: String = "manual_im_awake",
        deviceId: String?,
        notes: String? = null,
        dedupeWindowMs: Long = 15 * 60 * 1000L
    ): Long {
        val latest = dao.getLatestWakeMarker(sourceDate, markerSource)
        if (latest != null && kotlin.math.abs(markerEpochMs - latest.markerEpochMs) <= dedupeWindowMs) {
            return latest.id
        }
        return dao.insertWakeMarker(
            WakeMarkerEntity(
                sourceDate = sourceDate,
                markerEpochMs = markerEpochMs,
                markerSource = markerSource,
                deviceId = deviceId,
                notes = notes
            )
        )
    }

    suspend fun refreshFtuStatus(deviceId: String): Result<Boolean> = runCatching {
        val isDone = polarManager.isFtuDone(deviceId)
        val existing = dao.getFtuProfile(deviceId)
        if (existing != null) {
            dao.upsertFtuProfile(existing.copy(isCompleted = isDone, lastKnownDeviceState = "checked"))
        }
        isDone
    }

    suspend fun runCapabilityDiscovery(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val runtime = runtimeState.value
            val firmwareVersion = runtime.firmwareVersion
            val now = System.currentTimeMillis()
            val probeDate = LocalDate.now(ZoneOffset.UTC).minusDays(1)

            val ready = runtime.readyFeatures
            val unavailable = runtime.unavailableFeatures

            PolarBleApi.PolarBleSdkFeature.entries.forEach { feature ->
                val observed = when {
                    ready.contains(feature) -> ProbeStatus.SUPPORTED
                    unavailable.contains(feature) -> ProbeStatus.UNSUPPORTED
                    else -> ProbeStatus.UNKNOWN
                }
                dao.upsertObservedCapability(
                    ObservedCapabilityEntity(
                        deviceId = deviceId,
                        domain = feature.name,
                        status = observed.name,
                        source = "feature_readiness",
                        requestedRange = "-",
                        firmwareVersion = firmwareVersion,
                        readyFeature = feature.name.takeIf { ready.contains(feature) },
                        unavailableFeature = feature.name.takeIf { unavailable.contains(feature) },
                        details = "ready/unavailable callback snapshot",
                        lastObservedAtEpochMs = now
                    )
                )
            }

            runWithinSyncSession(deviceId, "capability_discovery") {
                observeDomainCapability(deviceId, ProbeDomain.SLEEP, "getSleep", "$probeDate..$probeDate") {
                    polarManager.fetchSleep(deviceId, probeDate, probeDate)
                }
                observeDomainCapability(deviceId, ProbeDomain.NIGHTLY_RECHARGE, "getNightlyRecharge", "$probeDate..$probeDate") {
                    polarManager.fetchNightlyRecharge(deviceId, probeDate, probeDate)
                }
                observeDomainCapability(deviceId, ProbeDomain.HR_247, "get247HrSamples", "$probeDate..$probeDate") {
                    polarManager.fetch247Hr(deviceId, probeDate, probeDate)
                }
                observeDomainCapability(deviceId, ProbeDomain.PPI_247, "get247PPiSamples", "$probeDate..$probeDate") {
                    polarManager.fetch247Ppi(deviceId, probeDate, probeDate)
                }
                observeDomainCapability(deviceId, ProbeDomain.SKIN_TEMPERATURE, "getSkinTemperature", "$probeDate..$probeDate") {
                    polarManager.fetchSkinTemperature(deviceId, probeDate, probeDate)
                }
                observeDomainCapability(deviceId, ProbeDomain.DAILY_SUMMARY, "getDailySummaryData", "$probeDate..$probeDate") {
                    polarManager.fetchDailySummary(deviceId, probeDate, probeDate)
                }
                observeDomainCapability(deviceId, ProbeDomain.ACTIVITY_SAMPLES, "getActivitySampleData", "$probeDate..$probeDate") {
                    polarManager.fetchActivitySamples(deviceId, probeDate, probeDate)
                }
            }
        }
    }

    suspend fun runManualSync(deviceId: String, config: SyncWindowConfig): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedConfig = config.normalized()
            val runtime = runtimeState.value
            if (dao.countCapabilities(deviceId) == 0 || hasFirmwareChanged(deviceId, runtime.firmwareVersion)) {
                runCapabilityDiscovery(deviceId).getOrThrow()
            }

            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtime.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = null,
                    status = "running",
                    notes = "manual sync"
                )
            )

            try {
                runWithinSyncSession(deviceId, "manual_sync") {
                    val now = LocalDate.now(ZoneOffset.UTC)
                    syncDomain(syncRunId, deviceId, ProbeDomain.SLEEP, now.minusDays(normalizedConfig.sleepDays.toLong()), now) {
                        val data = polarManager.fetchSleep(deviceId, it.first, it.second)
                        persistSleep(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForSleep(data), PayloadMappers.sleepList(data))
                    }
                    syncDomain(syncRunId, deviceId, ProbeDomain.NIGHTLY_RECHARGE, now.minusDays(normalizedConfig.nightlyRechargeDays.toLong()), now) {
                        val data = polarManager.fetchNightlyRecharge(deviceId, it.first, it.second)
                        persistNightlyRecharge(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForNightlyRecharge(data), PayloadMappers.nightlyRechargeList(data))
                    }
                    syncDomain(syncRunId, deviceId, ProbeDomain.HR_247, now.minusDays(normalizedConfig.hrDays.toLong()), now) {
                        val data = polarManager.fetch247Hr(deviceId, it.first, it.second)
                        persistHr(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForHr(data), PayloadMappers.hrList(data))
                    }
                    syncDomain(syncRunId, deviceId, ProbeDomain.PPI_247, now.minusDays(normalizedConfig.ppiDays.toLong()), now) {
                        val data = polarManager.fetch247Ppi(deviceId, it.first, it.second)
                        persistPpi(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForPpi(data), PayloadMappers.ppiList(data))
                    }
                    syncDomain(syncRunId, deviceId, ProbeDomain.SKIN_TEMPERATURE, now.minusDays(normalizedConfig.hrDays.toLong()), now) {
                        val data = polarManager.fetchSkinTemperature(deviceId, it.first, it.second)
                        persistSkinTemperature(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForSkinTemperature(data), PayloadMappers.skinTemperatureList(data))
                    }
                    syncDomain(syncRunId, deviceId, ProbeDomain.DAILY_SUMMARY, now.minusDays(normalizedConfig.sleepDays.toLong()), now) {
                        val data = polarManager.fetchDailySummary(deviceId, it.first, it.second)
                        persistDailySummary(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForDailySummary(data), PayloadMappers.dailySummaryList(data))
                    }
                    syncDomain(syncRunId, deviceId, ProbeDomain.ACTIVITY_SAMPLES, now.minusDays(normalizedConfig.hrDays.toLong()), now) {
                        val data = polarManager.fetchActivitySamples(deviceId, it.first, it.second)
                        persistActivitySamples(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForActivitySamples(data), PayloadMappers.activitySamplesList(data))
                    }
                }

                val existingRun = dao.getSyncRun(syncRunId)
                if (existingRun != null) {
                    dao.updateSyncRun(
                        existingRun.copy(
                            endedAtEpochMs = System.currentTimeMillis(),
                            status = "success",
                            notes = "manual sync completed"
                        )
                    )
                }
            } catch (error: Throwable) {
                val existingRun = dao.getSyncRun(syncRunId)
                if (existingRun != null) {
                    dao.updateSyncRun(
                        existingRun.copy(
                            endedAtEpochMs = System.currentTimeMillis(),
                            status = "partial_failure",
                            notes = error.message
                        )
                    )
                }
                throw error
            }

            syncRunId
        }
    }

    suspend fun runTrainingSessionSmokeTest(
        deviceId: String,
        durationSeconds: Int = 300,
        minimumBatteryPercent: Int = 20
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedDurationSeconds = durationSeconds.coerceIn(30, 900)
            val runtime = runtimeState.value
            val battery = runtime.batteryLevel
            check(battery == null || battery >= minimumBatteryPercent) {
                "Training smoke test blocked: Loop battery is $battery%, below the $minimumBatteryPercent% safety threshold."
            }

            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtime.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = null,
                    status = "running",
                    notes = "training session smoke test"
                )
            )

            val startedAt = System.currentTimeMillis()
            var stopError: Throwable? = null
            var startStatus: String? = null
            var stopStatus: String? = null
            val payload = linkedMapOf<String, Any?>(
                "purpose" to "validation_only_training_session_smoke_test",
                "deviceId" to deviceId,
                "firmwareVersion" to runtime.firmwareVersion,
                "startedAtEpochMs" to startedAt,
                "requestedDurationSeconds" to normalizedDurationSeconds,
                "minimumBatteryPercent" to minimumBatteryPercent,
                "batteryAtStart" to battery,
                "note" to "Checks whether manual training sessions expose RR/PPI-like intervals and whether normal Loop sync lanes still respond before/after."
            )

            try {
                payload["preSessionDataSnapshot"] = collectDaytimeInterferenceSnapshot(deviceId)
                startStatus = runCatching { polarManager.getExerciseStatus(deviceId).toString() }.getOrNull()
                payload["exerciseStatusBeforeStart"] = startStatus

                polarManager.startExercise(deviceId, PolarExerciseSession.SportProfile.OTHER_OUTDOOR)
                payload["exerciseStartedAtEpochMs"] = System.currentTimeMillis()
                delay(normalizedDurationSeconds * 1_000L)
            } finally {
                stopError = runCatching { polarManager.stopExercise(deviceId) }.exceptionOrNull()
                payload["exerciseStoppedAtEpochMs"] = System.currentTimeMillis()
                payload["stopError"] = stopError?.let { it.message ?: it.javaClass.simpleName }
            }

            delay(5_000)
            stopStatus = runCatching { polarManager.getExerciseStatus(deviceId).toString() }.getOrNull()
            payload["exerciseStatusAfterStop"] = stopStatus
            payload["postSessionDataSnapshot"] = collectDaytimeInterferenceSnapshot(deviceId)

            val today = LocalDate.now(ZoneOffset.UTC)
            val references = polarManager.fetchTrainingSessionReferences(deviceId, today.minusDays(1), today)
            val fetches = references.map { reference -> fetchTrainingSessionWithProgressSummary(deviceId, reference) }
            val sessions = fetches.mapNotNull { it.session }
            val sessionSummaries = sessions.map(::trainingSessionSummary)
            val totalRrIntervals = sessionSummaries.sumOf { (it["rrIntervalCount"] as? Int) ?: 0 }
            val totalHrSamples = sessionSummaries.sumOf { (it["heartRateSampleCount"] as? Int) ?: 0 }
            payload["referenceCount"] = references.size
            payload["references"] = references.map(::trainingReferenceSummary)
            payload["fetchProgress"] = fetches.map { it.progressSummary }
            payload["fetchedSessionCount"] = sessions.size
            payload["sessions"] = sessionSummaries
            payload["valueAssessment"] = mapOf(
                "rrIntervalsPresent" to (totalRrIntervals > 0),
                "heartRateSamplesPresent" to (totalHrSamples > 0),
                "overnightCandidateIfRrPresent" to (totalRrIntervals > 0),
                "normalDataInterferenceAssessment" to "daytime smoke only; can show sync lanes still respond, but cannot prove sleep/Nightly Recharge survives overnight training mode"
            )

            val shape = "refs=${references.size}, sessions=${sessions.size}, hrSamples=$totalHrSamples, rrIntervals=$totalRrIntervals"
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.TRAINING_SESSION_SMOKE.name,
                    requestedRange = "duration=${normalizedDurationSeconds}s",
                    status = if (totalRrIntervals > 0 || totalHrSamples > 0) ProbeStatus.SUPPORTED.name else ProbeStatus.EMPTY.name,
                    recordCount = sessions.size,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.PARSED.name,
                    detailSummary = shape,
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = null,
                    errorMessage = stopError?.let { it.message ?: it.javaClass.simpleName }
                )
            )

            dao.updateSyncRun(
                requireNotNull(dao.getSyncRun(syncRunId)).copy(
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = if (stopError == null) "success" else "partial_failure",
                    notes = "training smoke completed: $shape"
                )
            )
            syncRunId
        }
    }

    suspend fun startTrainingSessionSmoke(
        deviceId: String,
        minimumBatteryPercent: Int = 20
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val runtime = runtimeState.value
            val battery = runtime.batteryLevel
            check(battery == null || battery >= minimumBatteryPercent) {
                "Training smoke test blocked: Loop battery is $battery%, below the $minimumBatteryPercent% safety threshold."
            }
            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtime.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = null,
                    status = "running",
                    notes = "training session smoke start"
                )
            )
            polarManager.startExercise(deviceId, PolarExerciseSession.SportProfile.OTHER_OUTDOOR)
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.TRAINING_SESSION_SMOKE.name,
                    requestedRange = "manual_start",
                    status = ProbeStatus.PARTIAL.name,
                    recordCount = 0,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "started manual OTHER_OUTDOOR training smoke",
                    rawPayloadJson = GsonProvider.gson.toJson(
                        mapOf(
                            "purpose" to "validation_only_training_session_smoke_start",
                            "deviceId" to deviceId,
                            "firmwareVersion" to runtime.firmwareVersion,
                            "startedAtEpochMs" to System.currentTimeMillis(),
                            "batteryAtStart" to battery,
                            "minimumBatteryPercent" to minimumBatteryPercent
                        )
                    ),
                    manualNotes = null,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = null,
                    errorMessage = null
                )
            )
            syncRunId
        }
    }

    suspend fun stopAndFetchTrainingSessionSmoke(deviceId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val runningRun = dao.getLatestRunningTrainingSmokeRun()
            val syncRunId = runningRun?.id ?: dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = null,
                    status = "running",
                    notes = "training session smoke stop/fetch"
                )
            )
            val startedAt = System.currentTimeMillis()
            val payload = linkedMapOf<String, Any?>(
                "purpose" to "validation_only_training_session_smoke_stop_fetch",
                "deviceId" to deviceId,
                "firmwareVersion" to runtimeState.value.firmwareVersion,
                "fetchStartedAtEpochMs" to startedAt,
                "startedByRunId" to runningRun?.id,
                "startedByRunStartedAtEpochMs" to runningRun?.startedAtEpochMs
            )
            val stopError = runCatching { polarManager.stopExercise(deviceId) }.exceptionOrNull()
            payload["exerciseStoppedAtEpochMs"] = System.currentTimeMillis()
            payload["stopError"] = stopError?.let { it.message ?: it.javaClass.simpleName }
            delay(5_000)
            payload["exerciseStatusAfterStop"] = runCatching { polarManager.getExerciseStatus(deviceId).toString() }.getOrNull()
            payload["postSessionDataSnapshot"] = collectDaytimeInterferenceSnapshot(deviceId)

            val today = LocalDate.now(ZoneOffset.UTC)
            val references = polarManager.fetchTrainingSessionReferences(deviceId, today.minusDays(1), today)
            val fetches = references.map { reference -> fetchTrainingSessionWithProgressSummary(deviceId, reference) }
            val sessions = fetches.mapNotNull { it.session }
            val sessionSummaries = sessions.map(::trainingSessionSummary)
            val totalRrIntervals = sessionSummaries.sumOf { (it["rrIntervalCount"] as? Int) ?: 0 }
            val totalHrSamples = sessionSummaries.sumOf { (it["heartRateSampleCount"] as? Int) ?: 0 }
            payload["referenceCount"] = references.size
            payload["references"] = references.map(::trainingReferenceSummary)
            payload["fetchProgress"] = fetches.map { it.progressSummary }
            payload["fetchedSessionCount"] = sessions.size
            payload["sessions"] = sessionSummaries
            payload["valueAssessment"] = mapOf(
                "rrIntervalsPresent" to (totalRrIntervals > 0),
                "heartRateSamplesPresent" to (totalHrSamples > 0),
                "overnightCandidateIfRrPresent" to (totalRrIntervals > 0),
                "normalDataInterferenceAssessment" to "post-stop daytime snapshot only; overnight sleep/Nightly Recharge impact still requires overnight validation"
            )

            val shape = "refs=${references.size}, sessions=${sessions.size}, hrSamples=$totalHrSamples, rrIntervals=$totalRrIntervals"
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.TRAINING_SESSION_SMOKE.name,
                    requestedRange = "manual_stop_fetch",
                    status = if (totalRrIntervals > 0 || totalHrSamples > 0) ProbeStatus.SUPPORTED.name else ProbeStatus.EMPTY.name,
                    recordCount = sessions.size,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.PARSED.name,
                    detailSummary = shape,
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = null,
                    errorMessage = stopError?.let { it.message ?: it.javaClass.simpleName }
                )
            )
            dao.updateSyncRun(
                requireNotNull(dao.getSyncRun(syncRunId)).copy(
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = if (stopError == null) "success" else "partial_failure",
                    notes = "training smoke stop/fetch completed: $shape"
                )
            )
            syncRunId
        }
    }

    suspend fun startNormalOfflineRecordingSmoke(
        deviceId: String,
        dataType: PolarBleApi.PolarDeviceDataType = PolarBleApi.PolarDeviceDataType.PPI,
        minimumBatteryPercent: Int = 20
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val runtime = runtimeState.value
            val battery = runtime.batteryLevel
            check(battery == null || battery >= minimumBatteryPercent) {
                "Offline recording smoke blocked: Loop battery is $battery%, below the $minimumBatteryPercent% safety threshold."
            }
            check(dataType != PolarBleApi.PolarDeviceDataType.PPG || battery == null || battery >= 35) {
                "Offline PPG smoke blocked: Loop battery is $battery%, below the 35% safety threshold for heavier PPG recording."
            }

            val availableTypes = polarManager.getAvailableOfflineRecordingDataTypes(deviceId)
            check(dataType in availableTypes) {
                "Offline recording type ${dataType.name} is not available. Available: ${availableTypes.joinToString { it.name }}"
            }
            val offeredSettings = runCatching {
                polarManager.requestOfflineRecordingSettings(deviceId, dataType)
            }.getOrNull()
            val activeBefore = runCatching { polarManager.getOfflineRecordingStatus(deviceId) }.getOrDefault(emptyList())
            check(dataType !in activeBefore) {
                "Offline ${dataType.name} recording is already active."
            }

            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtime.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = null,
                    status = "running",
                    notes = "normal offline recording smoke start ${dataType.name}"
                )
            )
            val startedAt = System.currentTimeMillis()
            val preSnapshot = collectDaytimeInterferenceSnapshot(deviceId)

            polarManager.startOfflineRecording(deviceId, dataType)
            val activeAfter = runCatching { polarManager.getOfflineRecordingStatus(deviceId) }.getOrDefault(emptyList())
            val payload = linkedMapOf<String, Any?>(
                "purpose" to "validation_only_normal_mode_offline_recording_smoke_start",
                "deviceId" to deviceId,
                "dataType" to dataType.name,
                "firmwareVersion" to runtime.firmwareVersion,
                "startedAtEpochMs" to startedAt,
                "batteryAtStart" to battery,
                "minimumBatteryPercent" to minimumBatteryPercent,
                "mode" to "normal_mode_no_sdk_mode",
                "sdkModeUsed" to false,
                "availableOfflineTypes" to availableTypes.map { it.name },
                "requestedSettings" to offeredSettings?.let(::sensorSettingsSummary),
                "activeRecordingsBeforeStart" to activeBefore.map { it.name },
                "activeRecordingsAfterStart" to activeAfter.map { it.name },
                "preStartNormalLaneSnapshot" to preSnapshot,
                "note" to "Start only. Let this run for the intended duration, then call offline_stop_fetch. No SDK mode is enabled, so this tests whether normal Loop lanes can coexist with extra offline recording."
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.OFFLINE_RECORDING.name,
                    requestedRange = "normal_mode_start:${dataType.name}",
                    status = ProbeStatus.PARTIAL.name,
                    recordCount = 0,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "started normal-mode offline ${dataType.name}",
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = null,
                    errorMessage = null
                )
            )
            syncRunId
        }
    }

    suspend fun stopAndFetchNormalOfflineRecordingSmoke(
        deviceId: String,
        dataType: PolarBleApi.PolarDeviceDataType = PolarBleApi.PolarDeviceDataType.PPI
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val recentRunCutoffEpochMs = System.currentTimeMillis() - 36 * 60 * 60 * 1000L
            val matchedRun = (
                dao.getLatestRunningOfflineRecordingSmokeRunForType(dataType.name)
                    ?: dao.getLatestOfflineRecordingSmokeRunForType(dataType.name)
                )?.takeIf { it.startedAtEpochMs >= recentRunCutoffEpochMs }
            val syncRunId = matchedRun?.id ?: dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = null,
                    status = "running",
                    notes = "normal offline recording smoke stop/fetch ${dataType.name}"
                )
            )
            val fetchStartedAt = System.currentTimeMillis()
            val runStartedAt = matchedRun?.startedAtEpochMs ?: fetchStartedAt
            val stopError = runCatching { polarManager.stopOfflineRecording(deviceId, dataType) }.exceptionOrNull()
            delay(2_000)

            val regularEntries = runCatching { polarManager.listOfflineRecordings(deviceId) }.getOrDefault(emptyList())
            val splitEntries = runCatching { polarManager.listSplitOfflineRecordings(deviceId) }.getOrDefault(emptyList())
            val candidateEntries = selectOfflineEntriesForRun(
                dataType = dataType,
                startedAtEpochMs = runStartedAt,
                regularEntries = regularEntries,
                splitEntries = splitEntries
            )
            val fetchedCandidates = candidateEntries.map { candidate ->
                val result = when (candidate.kind) {
                    "split" -> runCatching { polarManager.fetchSplitOfflineRecord(deviceId, candidate.entry) }
                    else -> runCatching { polarManager.fetchOfflineRecord(deviceId, candidate.entry) }
                }
                OfflineFetchedCandidate(candidate.kind, candidate.entry, result)
            }
            val fetched = fetchedCandidates.map { candidate ->
                offlineFetchSummary(candidate.kind, candidate.entry, candidate.result)
            }
            val totalSamples = fetched.sumOf { (it["sampleCount"] as? Int) ?: 0 }
            val postSnapshot = collectDaytimeInterferenceSnapshot(deviceId)
            val activeAfter = runCatching { polarManager.getOfflineRecordingStatus(deviceId) }.getOrDefault(emptyList())

            val payload = linkedMapOf<String, Any?>(
                "purpose" to "validation_only_normal_mode_offline_recording_smoke_stop_fetch",
                "deviceId" to deviceId,
                "dataType" to dataType.name,
                "firmwareVersion" to runtimeState.value.firmwareVersion,
                "startedByRunId" to matchedRun?.id,
                "startedByRunStartedAtEpochMs" to matchedRun?.startedAtEpochMs,
                "fetchStartedAtEpochMs" to fetchStartedAt,
                "stopError" to stopError?.let { it.message ?: it.javaClass.simpleName },
                "mode" to "normal_mode_no_sdk_mode",
                "sdkModeUsed" to false,
                "activeRecordingsAfterStop" to activeAfter.map { it.name },
                "regularEntries" to regularEntries.map(::offlineEntrySummary),
                "splitEntries" to splitEntries.map(::offlineEntrySummary),
                "candidateEntries" to candidateEntries.map { offlineEntrySummary(it.entry) + ("recordingListKind" to it.kind) },
                "fetchedRecords" to fetched,
                "postStopNormalLaneSnapshot" to postSnapshot,
                "coexistenceAssessment" to mapOf(
                    "offlineSamplesPresent" to (totalSamples > 0),
                    "normalHr247Responded" to ((postSnapshot["hr247"] as? Map<*, *>)?.get("status") == ProbeStatus.SUPPORTED.name),
                    "normalPpi247Responded" to ((postSnapshot["ppi247"] as? Map<*, *>)?.get("status") == ProbeStatus.SUPPORTED.name),
                    "normalSkinTemperatureResponded" to ((postSnapshot["skinTemperature"] as? Map<*, *>)?.get("status") == ProbeStatus.SUPPORTED.name),
                    "overnightSleepImpactStillRequiresOvernightRun" to true
                )
            )
            val shape = "type=${dataType.name}, candidates=${candidateEntries.size}, samples=$totalSamples"
            val syncDomainResultId = dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.OFFLINE_RECORDING.name,
                    requestedRange = "normal_mode_stop_fetch:${dataType.name}",
                    status = when {
                        totalSamples > 0 -> ProbeStatus.SUPPORTED.name
                        stopError != null -> ProbeStatus.PARTIAL.name
                        else -> ProbeStatus.EMPTY.name
                    },
                    recordCount = candidateEntries.size,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.PARSED.name,
                    detailSummary = shape,
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = null,
                    startedAtEpochMs = fetchStartedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = null,
                    errorMessage = stopError?.let { it.message ?: it.javaClass.simpleName }
                )
            )
            persistOfflinePpiEpochs(
                syncDomainResultId = syncDomainResultId,
                syncRunId = syncRunId,
                deviceId = deviceId,
                dataType = dataType,
                fetchedAtEpochMs = fetchStartedAt,
                fetchedCandidates = fetchedCandidates
            )
            dao.updateSyncRun(
                requireNotNull(dao.getSyncRun(syncRunId)).copy(
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = if (stopError == null && totalSamples > 0) "success" else "partial_failure",
                    notes = "normal offline recording smoke completed: $shape"
                )
            )
            syncRunId
        }
    }

    private suspend fun <T> runWithinSyncSession(
        deviceId: String,
        sessionLabel: String,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val started = polarManager.startSyncNotifications(deviceId)
                check(started) { "Sync notifications not enabled" }
                return try {
                    block()
                } finally {
                    runCatching { polarManager.stopSyncNotifications(deviceId) }
                }
            } catch (error: Throwable) {
                lastError = error
                if (error.javaClass.simpleName != "PolarNotificationNotEnabled" || attempt == 2) {
                    throw error
                }
                delay(1_500L * (attempt + 1))
            }
        }
        throw requireNotNull(lastError)
    }

    private suspend fun collectDaytimeInterferenceSnapshot(deviceId: String): Map<String, Any?> {
        val today = LocalDate.now(ZoneOffset.UTC)
        val snapshot = linkedMapOf<String, Any?>(
            "capturedAtEpochMs" to System.currentTimeMillis(),
            "sourceDate" to today.toString()
        )
        return runCatching {
            runWithinSyncSession(deviceId, "training_smoke_snapshot") {
                val hr = runCatching { polarManager.fetch247Hr(deviceId, today, today) }
                val ppi = runCatching { polarManager.fetch247Ppi(deviceId, today, today) }
                val skinTemperature = runCatching { polarManager.fetchSkinTemperature(deviceId, today, today) }
                val dailySummary = runCatching { polarManager.fetchDailySummary(deviceId, today, today) }
                val activitySamples = runCatching { polarManager.fetchActivitySamples(deviceId, today, today) }

                snapshot["hr247"] = laneSnapshot(hr, ::shapeForHr)
                snapshot["ppi247"] = laneSnapshot(ppi, ::shapeForPpi)
                snapshot["skinTemperature"] = laneSnapshot(skinTemperature, ::shapeForSkinTemperature)
                snapshot["dailySummary"] = laneSnapshot(dailySummary, ::shapeForDailySummary)
                snapshot["activitySamples"] = laneSnapshot(activitySamples, ::shapeForActivitySamples)
                snapshot["respiration"] = mapOf(
                    "availableInDaytimeBleSnapshot" to false,
                    "note" to "Loop respiration is currently exposed through Nightly Recharge summaries, so daytime smoke testing cannot prove overnight respiration continuity."
                )
            }
            snapshot
        }.getOrElse { error ->
            snapshot["snapshotError"] = error.message ?: error.javaClass.simpleName
            snapshot
        }
    }

    private fun <T> laneSnapshot(result: Result<List<T>>, shape: (List<T>) -> String): Map<String, Any?> =
        result.fold(
            onSuccess = {
                mapOf(
                    "status" to if (it.isEmpty()) ProbeStatus.EMPTY.name else ProbeStatus.SUPPORTED.name,
                    "recordCount" to it.size,
                    "shape" to shape(it)
                )
            },
            onFailure = {
                mapOf(
                    "status" to ProbeStatus.ERROR.name,
                    "error" to (it.message ?: it.javaClass.simpleName)
                )
            }
        )

    private fun sensorSettingsSummary(settings: com.polar.sdk.api.model.PolarSensorSetting): Map<String, Any?> =
        settings.settings.entries.associate { (key, values) ->
            key.name to values.sorted()
        }

    private fun offlineEntrySummary(entry: PolarOfflineRecordingEntry): Map<String, Any?> =
        mapOf(
            "path" to entry.path,
            "size" to entry.size,
            "date" to entry.date.toString(),
            "type" to entry.type.name
        )

    private fun selectOfflineEntriesForRun(
        dataType: PolarBleApi.PolarDeviceDataType,
        startedAtEpochMs: Long,
        regularEntries: List<PolarOfflineRecordingEntry>,
        splitEntries: List<PolarOfflineRecordingEntry>
    ): List<OfflineRecordingCandidate> {
        val localStartedAt = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(startedAtEpochMs),
            ZoneId.systemDefault()
        ).minusMinutes(15)
        val dateToken = DateTimeFormatter.BASIC_ISO_DATE.format(localStartedAt.toLocalDate())
        fun isCandidate(entry: PolarOfflineRecordingEntry): Boolean {
            return entry.type == dataType &&
                (
                    !entry.date.isBefore(localStartedAt) ||
                        entry.path.contains(dateToken)
                    )
        }
        val split = splitEntries.filter(::isCandidate).map { OfflineRecordingCandidate("split", it) }
        val regular = regularEntries.filter(::isCandidate).map { OfflineRecordingCandidate("regular", it) }
        return (split + regular)
            .distinctBy { "${it.kind}:${it.entry.path}:${it.entry.size}" }
            .sortedByDescending { it.entry.date }
    }

    private fun offlineFetchSummary(
        kind: String,
        entry: PolarOfflineRecordingEntry,
        result: Result<PolarOfflineRecordingData>
    ): Map<String, Any?> =
        result.fold(
            onSuccess = { data ->
                val dataSummary = offlineRecordingDataSummary(data)
                offlineEntrySummary(entry) + mapOf(
                    "recordingListKind" to kind,
                    "fetchStatus" to ProbeStatus.SUPPORTED.name,
                    "dataClass" to data.javaClass.simpleName,
                    "startTime" to data.startTime.toString(),
                    "settings" to data.settings?.let(::sensorSettingsSummary),
                    "sampleCount" to dataSummary.sampleCount,
                    "samplePreview" to dataSummary.samplePreview,
                    "samples" to dataSummary.samples,
                    "payloadNotes" to dataSummary.notes
                )
            },
            onFailure = { error ->
                offlineEntrySummary(entry) + mapOf(
                    "recordingListKind" to kind,
                    "fetchStatus" to ProbeStatus.ERROR.name,
                    "error" to (error.message ?: error.javaClass.simpleName)
                )
            }
        )

    private fun offlineRecordingDataSummary(data: PolarOfflineRecordingData): OfflineRecordingDataSummary =
        when (data) {
            is PolarOfflineRecordingData.PpiOfflineRecording -> OfflineRecordingDataSummary(
                sampleCount = data.data.samples.size,
                samplePreview = data.data.samples.take(20).map(::ppiSampleSummary),
                samples = data.data.samples.map(::ppiSampleSummary),
                notes = mapOf("primaryValue" to "ppi_ms", "hrvCandidate" to true)
            )
            is PolarOfflineRecordingData.PpgOfflineRecording -> OfflineRecordingDataSummary(
                sampleCount = data.data.samples.size,
                samplePreview = data.data.samples.take(10).map {
                    mapOf(
                        "timeStamp" to it.timeStamp,
                        "channelSamples" to it.channelSamples,
                        "statusBits" to it.statusBits
                    )
                },
                samples = null,
                notes = mapOf("primaryValue" to "raw_ppg_waveform", "requiresPostProcessingForHrv" to true, "ppgType" to data.data.type.name)
            )
            is PolarOfflineRecordingData.HrOfflineRecording -> OfflineRecordingDataSummary(
                sampleCount = data.data.samples.size,
                samplePreview = data.data.samples.take(20).map {
                    mapOf(
                        "hr" to it.hr,
                        "correctedHr" to it.correctedHr,
                        "ppgQuality" to it.ppgQuality,
                        "rrsMs" to it.rrsMs,
                        "rrAvailable" to it.rrAvailable,
                        "contactStatus" to it.contactStatus
                    )
                },
                samples = null,
                notes = mapOf("primaryValue" to "heart_rate", "hrvCandidate" to data.data.samples.any { it.rrAvailable && it.rrsMs.isNotEmpty() })
            )
            is PolarOfflineRecordingData.AccOfflineRecording -> OfflineRecordingDataSummary(
                sampleCount = data.data.samples.size,
                samplePreview = data.data.samples.take(10).map { it.toString() },
                samples = null,
                notes = mapOf("primaryValue" to "accelerometer", "secondaryValue" to "artefact_and_motion_context")
            )
            is PolarOfflineRecordingData.SkinTemperatureOfflineRecording -> OfflineRecordingDataSummary(
                sampleCount = data.data.samples.size,
                samplePreview = data.data.samples.take(20).map {
                    mapOf("timeStamp" to it.timeStamp, "temperature" to it.temperature)
                },
                samples = null,
                notes = mapOf("primaryValue" to "skin_temperature", "secondaryValue" to "overnight_context_if_aligned")
            )
            else -> OfflineRecordingDataSummary(
                sampleCount = 0,
                samplePreview = listOf(data.toString().take(1_000)),
                samples = null,
                notes = mapOf("unsupportedSummaryType" to data.javaClass.name)
            )
        }

    private suspend fun persistOfflinePpiEpochs(
        syncDomainResultId: Long,
        syncRunId: Long,
        deviceId: String,
        dataType: PolarBleApi.PolarDeviceDataType,
        fetchedAtEpochMs: Long,
        fetchedCandidates: List<OfflineFetchedCandidate>
    ) {
        if (dataType != PolarBleApi.PolarDeviceDataType.PPI) return

        val sessions = mutableListOf<OfflineRecordingSessionEntity>()
        val epochs = mutableListOf<OfflinePpiEpochEntity>()
        fetchedCandidates.forEach { candidate ->
            val data = candidate.result.getOrNull() as? PolarOfflineRecordingData.PpiOfflineRecording ?: return@forEach
            val samples = data.data.samples
            if (samples.isEmpty()) return@forEach

            val sampleSummaries = samples.map { sample ->
                OfflinePpiEpochBuilder.Sample(
                    timestampEpochMs = polarTimestampToEpochMs(sample.timeStamp.toLong()),
                    ppiMs = sample.ppi,
                    errorEstimateMs = sample.errorEstimate,
                    hrBpm = sample.hr,
                    blockerBit = sample.blockerBit,
                    skinContactStatus = sample.skinContactStatus
                )
            }.sortedBy { it.timestampEpochMs }
            val usableCount = sampleSummaries.count { it.isUsable }
            val recordingStart = sampleSummaries.firstOrNull()?.timestampEpochMs
            val recordingEnd = sampleSummaries.lastOrNull()?.timestampEpochMs
            sessions += OfflineRecordingSessionEntity(
                recordingPath = candidate.entry.path,
                syncDomainResultId = syncDomainResultId,
                syncRunId = syncRunId,
                deviceId = deviceId,
                dataType = dataType.name,
                recordingListKind = candidate.kind,
                firmwareVersion = runtimeState.value.firmwareVersion,
                recordingDateLocal = candidate.entry.date.toString(),
                recordingStartEpochMs = recordingStart,
                recordingEndEpochMs = recordingEnd,
                fetchedAtEpochMs = fetchedAtEpochMs,
                sampleCount = samples.size,
                usableSampleCount = usableCount,
                mode = "normal_mode_no_sdk_mode",
                payloadSummaryJson = GsonProvider.gson.toJson(
                    mapOf(
                        "path" to candidate.entry.path,
                        "size" to candidate.entry.size,
                        "entryDate" to candidate.entry.date.toString(),
                        "dataClass" to data.javaClass.simpleName,
                        "startTime" to data.startTime.toString(),
                        "settings" to data.settings?.let(::sensorSettingsSummary),
                        "sampleCount" to samples.size,
                        "usableSampleCount" to usableCount,
                        "notes" to mapOf("primaryValue" to "ppi_ms", "derivedEpochMinutes" to OfflinePpiEpochBuilder.EPOCH_MINUTES)
                    )
                )
            )
            epochs += OfflinePpiEpochBuilder.derive(
                recordingPath = candidate.entry.path,
                syncDomainResultId = syncDomainResultId,
                syncRunId = syncRunId,
                deviceId = deviceId,
                samples = sampleSummaries
            )
        }

        if (sessions.isNotEmpty()) {
            dao.upsertOfflineRecordingSessions(sessions)
        }
        if (epochs.isNotEmpty()) {
            dao.upsertOfflinePpiEpochs(epochs)
        }
    }

    private suspend fun persistOfflinePpiEpochsFromPayload(result: SyncDomainResultEntity, payload: String): Int {
        val root = runCatching { GsonProvider.gson.fromJson(payload, JsonObject::class.java) }.getOrNull() ?: return 0
        val fetchedRecords = root.getAsJsonArray("fetchedRecords") ?: return 0
        val sessions = mutableListOf<OfflineRecordingSessionEntity>()
        val epochs = mutableListOf<OfflinePpiEpochEntity>()
        fetchedRecords
            .mapNotNull { it.asJsonObjectOrNull() }
            .filter { it.stringOrNull("type") == PolarBleApi.PolarDeviceDataType.PPI.name }
            .forEach { record ->
                val path = record.stringOrNull("path") ?: return@forEach
                val samples = record.getAsJsonArray("samples")
                    ?.mapNotNull { it.asJsonObjectOrNull()?.toOfflinePpiSampleForEpoch() }
                    ?.sortedBy { it.timestampEpochMs }
                    .orEmpty()
                if (samples.isEmpty()) return@forEach

                val usableCount = samples.count { it.isUsable }
                sessions += OfflineRecordingSessionEntity(
                    recordingPath = path,
                    syncDomainResultId = result.id,
                    syncRunId = result.syncRunId,
                    deviceId = result.deviceId,
                    dataType = PolarBleApi.PolarDeviceDataType.PPI.name,
                    recordingListKind = record.stringOrNull("recordingListKind") ?: "unknown",
                    firmwareVersion = root.stringOrNull("firmwareVersion"),
                    recordingDateLocal = record.stringOrNull("date"),
                    recordingStartEpochMs = samples.firstOrNull()?.timestampEpochMs,
                    recordingEndEpochMs = samples.lastOrNull()?.timestampEpochMs,
                    fetchedAtEpochMs = result.startedAtEpochMs,
                    sampleCount = samples.size,
                    usableSampleCount = usableCount,
                    mode = root.stringOrNull("mode") ?: "unknown",
                    payloadSummaryJson = GsonProvider.gson.toJson(
                        mapOf(
                            "path" to path,
                            "size" to record.intOrNull("size"),
                            "entryDate" to record.stringOrNull("date"),
                            "dataClass" to record.stringOrNull("dataClass"),
                            "startTime" to record.stringOrNull("startTime"),
                            "sampleCount" to samples.size,
                            "usableSampleCount" to usableCount,
                            "backfilledFromSyncDomainResultId" to result.id,
                            "notes" to mapOf("primaryValue" to "ppi_ms", "derivedEpochMinutes" to OfflinePpiEpochBuilder.EPOCH_MINUTES)
                        )
                    )
                )
                epochs += OfflinePpiEpochBuilder.derive(
                    recordingPath = path,
                    syncDomainResultId = result.id,
                    syncRunId = result.syncRunId,
                    deviceId = result.deviceId,
                    samples = samples
                )
            }
        if (sessions.isNotEmpty()) {
            dao.upsertOfflineRecordingSessions(sessions)
        }
        if (epochs.isNotEmpty()) {
            dao.upsertOfflinePpiEpochs(epochs)
        }
        return epochs.size
    }

    private fun polarTimestampToEpochMs(timestampNanosSince2000: Long): Long =
        POLAR_TIMESTAMP_EPOCH.plusNanos(timestampNanosSince2000).toEpochMilli()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asIntOrNull(): Int? =
        runCatching { takeUnless { it.isJsonNull }?.asInt }.getOrNull()

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { takeUnless { it.isJsonNull }?.asString }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeUnless { it.isJsonNull }?.asInt

    private fun JsonObject.longOrNull(key: String): Long? =
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()

    private fun JsonObject.doubleOrNull(key: String): Double? =
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asDouble }.getOrNull()

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        get(key)?.takeUnless { it.isJsonNull }?.asBoolean

    private fun JsonObject.toOfflinePpiSampleForEpoch(): OfflinePpiEpochBuilder.Sample? {
        val timestampData = getAsJsonObject("timeStamp")
            ?.get("data")
            ?.takeUnless { it.isJsonNull }
            ?.asLong
            ?: return null
        return OfflinePpiEpochBuilder.Sample(
            timestampEpochMs = polarTimestampToEpochMs(timestampData),
            ppiMs = intOrNull("ppi") ?: return null,
            errorEstimateMs = intOrNull("errorEstimate") ?: Int.MAX_VALUE,
            hrBpm = intOrNull("hr") ?: 0,
            blockerBit = booleanOrNull("blockerBit") ?: false,
            skinContactStatus = booleanOrNull("skinContactStatus") ?: false
        )
    }

    private fun ppiSampleSummary(sample: com.polar.sdk.api.model.PolarPpiData.PolarPpiSample): Map<String, Any?> =
        mapOf(
            "ppi" to sample.ppi,
            "errorEstimate" to sample.errorEstimate,
            "hr" to sample.hr,
            "blockerBit" to sample.blockerBit,
            "skinContactStatus" to sample.skinContactStatus,
            "skinContactSupported" to sample.skinContactSupported,
            "timeStamp" to sample.timeStamp
        )

    private fun trainingReferenceSummary(reference: PolarTrainingSessionReference): Map<String, Any?> =
        mapOf(
            "date" to reference.date,
            "path" to reference.path,
            "fileSize" to reference.fileSize,
            "trainingDataTypes" to reference.trainingDataTypes.map { it.name },
            "exerciseCount" to reference.exercises.size,
            "exercisePaths" to reference.exercises.map { it.path }
        )

    private suspend fun fetchTrainingSessionWithProgressSummary(
        deviceId: String,
        reference: PolarTrainingSessionReference
    ): TrainingFetchSummary {
        return runCatching {
            val events = polarManager.fetchTrainingSessionWithProgress(deviceId, reference)
            var session: PolarTrainingSession? = null
            val progressEvents = events.mapNotNull { event ->
                when (event) {
                    is PolarTrainingSessionFetchResult.Progress -> mapOf(
                        "totalBytes" to event.progress.totalBytes,
                        "completedBytes" to event.progress.completedBytes,
                        "progressPercent" to event.progress.progressPercent,
                        "currentFileName" to event.progress.currentFileName
                    )
                    is PolarTrainingSessionFetchResult.Complete -> {
                        session = event.session
                        null
                    }
                }
            }
            TrainingFetchSummary(
                session = session,
                progressSummary = mapOf(
                    "reference" to trainingReferenceSummary(reference),
                    "status" to if (session != null) ProbeStatus.SUPPORTED.name else ProbeStatus.EMPTY.name,
                    "eventCount" to events.size,
                    "progressEventCount" to progressEvents.size,
                    "firstProgress" to progressEvents.firstOrNull(),
                    "lastProgress" to progressEvents.lastOrNull(),
                    "allProgress" to progressEvents.take(20)
                )
            )
        }.getOrElse { error ->
            TrainingFetchSummary(
                session = null,
                progressSummary = mapOf(
                    "reference" to trainingReferenceSummary(reference),
                    "status" to ProbeStatus.ERROR.name,
                    "error" to (error.message ?: error.javaClass.simpleName)
                )
            )
        }
    }

    private fun trainingSessionSummary(session: PolarTrainingSession): Map<String, Any?> {
        val exercises = session.exercises.map { exercise ->
            val samples = exercise.samples
            val advanced = exercise.samplesAdvanced
            val rrIntervals = if (samples?.hasRrSamples() == true) samples.rrSamples.rrIntervalsList else emptyList()
            mapOf(
                "index" to exercise.index,
                "path" to exercise.path,
                "exerciseDataTypes" to exercise.exerciseDataTypes.map { it.name },
                "fileSizes" to exercise.fileSizes,
                "summaryText" to exercise.exerciseSummary?.toString()?.take(2_000),
                "sampleSerializedSize" to samples?.serializedSize,
                "advancedSampleSerializedSize" to advanced?.serializedSize,
                "heartRateSampleCount" to (samples?.heartRateSamplesCount ?: 0),
                "heartRateSamplesFirst20" to samples?.heartRateSamplesList.orEmpty().take(20),
                "rrIntervalCount" to rrIntervals.size,
                "rrIntervalsFirst20" to rrIntervals.take(20),
                "temperatureSampleCount" to (samples?.temperatureSamplesCount ?: 0),
                "bodyTemperatureSampleCount" to (samples?.bodyTemperatureCount ?: 0),
                "intervalledSampleListCount" to (samples?.exerciseIntervalledSampleListCount ?: 0)
            )
        }
        return mapOf(
            "reference" to trainingReferenceSummary(session.reference),
            "sessionSummaryText" to session.sessionSummary.toString().take(2_000),
            "exerciseCount" to session.exercises.size,
            "heartRateSampleCount" to exercises.sumOf { (it["heartRateSampleCount"] as? Int) ?: 0 },
            "rrIntervalCount" to exercises.sumOf { (it["rrIntervalCount"] as? Int) ?: 0 },
            "exercises" to exercises
        )
    }

    private suspend fun hasFirmwareChanged(deviceId: String, runtimeFirmware: String?): Boolean {
        val appSettings = dao.getAppSettings()
        val persistedFirmware = appSettings?.takeIf { it.selectedDeviceId == deviceId }?.lastKnownFirmwareBySelectedDevice
        if (persistedFirmware != null && runtimeFirmware != null) {
            return persistedFirmware != runtimeFirmware
        }
        val device = dao.getDeviceProfile(deviceId) ?: return false
        return device.firmwareVersion != null && runtimeFirmware != null && device.firmwareVersion != runtimeFirmware
    }

    suspend fun hasSelectedDeviceFirmwareChanged(deviceId: String?, runtimeFirmware: String?): Boolean {
        if (deviceId == null || runtimeFirmware == null) return false
        return hasFirmwareChanged(deviceId, runtimeFirmware)
    }

    private suspend fun observeDomainCapability(
        deviceId: String,
        domain: ProbeDomain,
        sourceMethod: String,
        requestedRange: String,
        block: suspend () -> List<*>
    ) {
        val firmware = runtimeState.value.firmwareVersion
        try {
            val result = block()
            val status = when {
                result.isEmpty() && domain in listOf(ProbeDomain.SLEEP, ProbeDomain.NIGHTLY_RECHARGE) -> ProbeStatus.DELAYED
                result.isEmpty() -> ProbeStatus.EMPTY
                else -> ProbeStatus.SUPPORTED
            }
            val shape = inferShape(result)
            dao.upsertObservedCapability(
                ObservedCapabilityEntity(
                    deviceId = deviceId,
                    domain = domain.name,
                    status = status.name,
                    source = sourceMethod,
                    requestedRange = requestedRange,
                    firmwareVersion = firmware,
                    readyFeature = null,
                    unavailableFeature = null,
                    details = shape,
                    lastObservedAtEpochMs = System.currentTimeMillis()
                )
            )
        } catch (error: Throwable) {
            dao.upsertObservedCapability(
                ObservedCapabilityEntity(
                    deviceId = deviceId,
                    domain = domain.name,
                    status = ProbeStatus.ERROR.name,
                    source = sourceMethod,
                    requestedRange = requestedRange,
                    firmwareVersion = firmware,
                    readyFeature = null,
                    unavailableFeature = null,
                    details = error.message ?: error.javaClass.simpleName,
                    lastObservedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun syncDomain(
        syncRunId: Long,
        deviceId: String,
        domain: ProbeDomain,
        from: LocalDate,
        to: LocalDate,
        block: suspend (Pair<LocalDate, LocalDate>) -> DomainPersistenceResult
    ) {
        val startedAt = System.currentTimeMillis()
        val requestedRange = "$from..$to"
        try {
            val result = block(from to to)
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = domain.name,
                    requestedRange = requestedRange,
                    status = if (result.recordCount == 0) ProbeStatus.EMPTY.name else ProbeStatus.SUPPORTED.name,
                    recordCount = result.recordCount,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.PARSED.name,
                    detailSummary = result.shapeNotes,
                    rawPayloadJson = result.rawPayloadJson,
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = null,
                    errorMessage = null
                )
            )
        } catch (error: Throwable) {
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = domain.name,
                    requestedRange = requestedRange,
                    status = ProbeStatus.ERROR.name,
                    recordCount = 0,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "sync failed",
                    rawPayloadJson = null,
                    manualNotes = null,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = error.javaClass.simpleName,
                    errorMessage = error.message
                )
            )
            throw error
        }
    }

    suspend fun rebuildOfflinePpiEpochTables(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val results = dao.getSupportedOfflinePpiResultSummaries()
            var epochCount = 0
            results.forEach { result ->
                val payload = dao.getSyncDomainResultPayloadIfSmall(result.id)
                if (payload == null) {
                    // Android CursorWindow cannot safely hydrate very large legacy payload blobs.
                    return@forEach
                }
                epochCount += persistOfflinePpiEpochsFromPayload(result, payload)
            }
            epochCount
        }
    }

    private suspend fun persistSleep(deviceId: String, data: List<PolarSleepData>, requestedRange: String) {
        val records = data.map {
            SleepNightRawEntity(
                deviceId = deviceId,
                sourceDate = it.date?.toString() ?: it.result?.sleepResultDate?.toString(),
                requestedRange = requestedRange,
                syncTimestampEpochMs = System.currentTimeMillis(),
                keySummary = buildString {
                    append("date=")
                    append(it.date ?: it.result?.sleepResultDate)
                    append(", wakePhases=")
                    append(it.result?.sleepWakePhases?.size ?: 0)
                },
                rawPayloadJson = PayloadMappers.sleep(it),
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.PARSED.name
            )
        }
        val filtered = filterNewRecords(records, dao.getExistingSleepPayloads(deviceId))
        filtered.forEach { dao.deleteSleepRecordsForDate(deviceId, it.sourceDate) }
        dao.insertSleepRecords(filtered)
    }

    private suspend fun persistNightlyRecharge(deviceId: String, data: List<PolarNightlyRechargeData>, requestedRange: String) {
        val records = data.map {
            NightlyRechargeRawEntity(
                deviceId = deviceId,
                sourceDate = it.sleepResultDate?.toString(),
                requestedRange = requestedRange,
                syncTimestampEpochMs = System.currentTimeMillis(),
                keySummary = "ans=${it.ansStatus}, indicator=${it.recoveryIndicator}, date=${it.sleepResultDate}",
                rawPayloadJson = PayloadMappers.nightlyRecharge(it),
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.PARSED.name
            )
        }
        val filtered = filterNewRecords(records, dao.getExistingNightlyRechargePayloads(deviceId))
        filtered.forEach { dao.deleteNightlyRechargeRecordsForDate(deviceId, it.sourceDate) }
        dao.insertNightlyRechargeRecords(filtered)
    }

    private suspend fun persistHr(deviceId: String, data: List<Polar247HrSamplesData>, requestedRange: String) {
        val records = data.map {
            Hr247DayRawEntity(
                deviceId = deviceId,
                sourceDate = it.date.toString(),
                requestedRange = requestedRange,
                syncTimestampEpochMs = System.currentTimeMillis(),
                keySummary = "sessions=${it.samples.size}, sampleCounts=${it.samples.sumOf { samples -> samples.hrSamples.size }}",
                rawPayloadJson = PayloadMappers.hr(it),
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.PARSED.name
            )
        }
        val filtered = filterNewRecords(records, dao.getExistingHrPayloads(deviceId))
        filtered.forEach { dao.deleteHrRecordsForDate(deviceId, it.sourceDate) }
        dao.insertHrRecords(filtered)
    }

    private suspend fun persistPpi(deviceId: String, data: List<Polar247PPiSamplesData>, requestedRange: String) {
        val records = data.map {
            Ppi247DayRawEntity(
                deviceId = deviceId,
                sourceDate = it.date.toString(),
                requestedRange = requestedRange,
                syncTimestampEpochMs = System.currentTimeMillis(),
                keySummary = "start=${it.samples.startTime}, samples=${it.samples.ppiValueList.size}, trigger=${it.samples.triggerType}",
                rawPayloadJson = PayloadMappers.ppi(it),
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.PARSED.name
            )
        }
        val filtered = filterNewRecords(records, dao.getExistingPpiPayloads(deviceId))
        filtered.forEach { dao.deletePpiRecordsForDateAndKeySummary(deviceId, it.sourceDate, it.keySummary) }
        dao.insertPpiRecords(filtered)
        rebuildPpi247EpochsForDates(records.map { it.sourceDate }.distinct())
    }

    private suspend fun rebuildPpi247EpochsForDates(sourceDates: List<String>): Int {
        val normalizedDates = sourceDates.distinct().filter { it.isNotBlank() }
        if (normalizedDates.isEmpty()) return 0
        val records = dao.getPpiRawRecordsForDates(normalizedDates)
        val epochs = records
            .groupBy { it.sourceDate }
            .flatMap { (sourceDate, dayRecords) ->
                val samples = dayRecords.flatMap { ppi247SamplesFromRaw(it, sourceDate) }.sortedBy { it.timestampEpochMs }
                Ppi247EpochBuilder.derive(samples, updatedAtEpochMs = System.currentTimeMillis())
            }
        dao.deletePpi247EpochsForDates(normalizedDates)
        if (epochs.isNotEmpty()) {
            dao.upsertPpi247Epochs(epochs)
        }
        return epochs.size
    }

    suspend fun rebuildPpi247EpochTables(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val records = dao.getAllPpiRawRecords()
            rebuildPpi247EpochsForDates(records.map { it.sourceDate }.distinct())
        }
    }

    private fun ppi247SamplesFromRaw(record: Ppi247DayRawEntity, sourceDate: String): List<Ppi247EpochBuilder.Sample> {
        val root = runCatching { GsonProvider.gson.fromJson(record.rawPayloadJson, JsonObject::class.java) }.getOrNull()
            ?: return emptyList()
        val samplesObject = root.getAsJsonObject("samples") ?: return emptyList()
        val startTime = samplesObject.get("startTime")?.takeUnless { it.isJsonNull }?.asString ?: return emptyList()
        val triggerType = samplesObject.get("triggerType")?.takeUnless { it.isJsonNull }?.asString ?: "unknown"
        val startEpochMs = runCatching {
            LocalDateTime.of(LocalDate.parse(sourceDate), LocalTime.parse(startTime))
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull() ?: return emptyList()
        val ppiValues = samplesObject.getAsJsonArray("ppiValueList")?.mapNotNull { it.asIntOrNull() }.orEmpty()
        val errorValues = samplesObject.getAsJsonArray("ppiErrorEstimateList")?.mapNotNull { it.asIntOrNull() }.orEmpty()
        val statusValues = samplesObject.getAsJsonArray("statusList")?.toList().orEmpty()
        var timestampEpochMs = startEpochMs
        return ppiValues.mapIndexed { index, ppiMs ->
            timestampEpochMs += ppiMs.toLong()
            val status = statusValues.getOrNull(index)?.takeIf { it.isJsonObject }?.asJsonObject
            Ppi247EpochBuilder.Sample(
                timestampEpochMs = timestampEpochMs,
                deviceId = record.deviceId,
                ppiMs = ppiMs,
                errorEstimateMs = errorValues.getOrNull(index) ?: 0,
                skinContactDetected = status?.get("skinContact")?.asStringOrNull() != "SKIN_CONTACT_NOT_DETECTED",
                movementDetected = status?.get("movement")?.asStringOrNull() == "MOVING_DETECTED",
                intervalOnline = status?.get("intervalStatus")?.asStringOrNull() != "INTERVAL_IS_OFFLINE",
                triggerType = triggerType
            )
        }
    }

    private suspend fun persistSkinTemperature(deviceId: String, data: List<PolarSkinTemperatureData>, requestedRange: String) {
        val records = data.map {
            val rawPayloadJson = PayloadMappers.skinTemperature(it)
            SkinTemperatureRawEntity(
                deviceId = deviceId,
                sourceDate = it.date.toString(),
                requestedRange = requestedRange,
                syncTimestampEpochMs = System.currentTimeMillis(),
                keySummary = skinTemperatureKeySummary(it),
                rawPayloadJson = rawPayloadJson,
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.PARSED.name
            )
        }
        val filtered = filterNewRecords(records, dao.getExistingSkinTemperaturePayloads(deviceId))
        filtered.forEach { dao.deleteSkinTemperatureRecordsForDate(deviceId, it.sourceDate) }
        dao.insertSkinTemperatureRecords(filtered)
        rebuildSkinTemperatureSamplesForDates(records.map { it.sourceDate }.distinct())
    }

    private suspend fun rebuildSkinTemperatureSamplesForDates(sourceDates: List<String>): Int {
        val normalizedDates = sourceDates.distinct().filter { it.isNotBlank() }
        if (normalizedDates.isEmpty()) return 0
        val records = dao.getSkinTemperatureRawRecordsForDates(normalizedDates)
        val updatedAt = System.currentTimeMillis()
        val samples = records.flatMap { skinTemperatureSamplesFromRaw(it, updatedAt) }
        dao.deleteSkinTemperatureSamplesForDates(normalizedDates)
        if (samples.isNotEmpty()) {
            dao.upsertSkinTemperatureSamples(samples)
        }
        return samples.size
    }

    suspend fun rebuildContextEpochTables(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val skinRecords = dao.getAllSkinTemperatureRawRecords()
            val activityRecords = dao.getAllActivitySampleRawRecords()
            val skinCount = rebuildSkinTemperatureSamplesForDates(skinRecords.map { it.sourceDate }.distinct())
            val activityCount = rebuildActivityEpochsForDates(activityRecords.map { it.sourceDate }.distinct())
            skinCount + activityCount
        }
    }

    private fun skinTemperatureSamplesFromRaw(
        record: SkinTemperatureRawEntity,
        updatedAtEpochMs: Long
    ): List<SkinTemperatureSampleEntity> {
        val root = runCatching { GsonProvider.gson.fromJson(record.rawPayloadJson, JsonObject::class.java) }.getOrNull()
            ?: return emptyList()
        val date = root.stringOrNull("date") ?: record.sourceDate
        val dayStartEpochMs = runCatching {
            LocalDate.parse(date).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() ?: return emptyList()
        val result = root.getAsJsonObject("result") ?: return emptyList()
        val sensorLocation = result.stringOrNull("sensorLocation")
        val measurementType = result.stringOrNull("measurementType")
        return result.getAsJsonArray("skinTemperatureList")
            ?.mapNotNull { element ->
                val sample = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val deltaMs = sample.longOrNull("recordingTimeDeltaMs") ?: return@mapNotNull null
                val temperature = sample.doubleOrNull("temperature") ?: return@mapNotNull null
                val sampleTime = dayStartEpochMs + deltaMs
                SkinTemperatureSampleEntity(
                    deviceId = record.deviceId,
                    sourceDate = Instant.ofEpochMilli(sampleTime).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                    sampleTimeEpochMs = sampleTime,
                    recordingTimeDeltaMs = deltaMs,
                    temperatureCelsius = temperature,
                    sensorLocation = sensorLocation,
                    measurementType = measurementType,
                    updatedAtEpochMs = updatedAtEpochMs
                )
            }
            .orEmpty()
    }

    private suspend fun persistDailySummary(deviceId: String, data: List<PolarDailySummaryData>, requestedRange: String) {
        val records = data.map {
            val rawPayloadJson = PayloadMappers.dailySummary(it)
            DailySummaryRawEntity(
                deviceId = deviceId,
                sourceDate = it.date.toString(),
                requestedRange = requestedRange,
                syncTimestampEpochMs = System.currentTimeMillis(),
                keySummary = dailySummaryKeySummary(it),
                rawPayloadJson = rawPayloadJson,
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.PARSED.name
            )
        }
        val filtered = filterNewRecords(records, dao.getExistingDailySummaryPayloads(deviceId))
        filtered.forEach { dao.deleteDailySummaryRecordsForDate(deviceId, it.sourceDate) }
        dao.insertDailySummaryRecords(filtered)
    }

    private suspend fun persistActivitySamples(deviceId: String, data: List<PolarActivitySamplesDayData>, requestedRange: String) {
        val records = data.map {
            val rawPayloadJson = PayloadMappers.activitySamples(it)
            val firstSample = it.polarActivitySamplesDataList.orEmpty().firstOrNull()
            ActivitySamplesRawEntity(
                deviceId = deviceId,
                sourceDate = firstSample?.startTime?.toLocalDate()?.toString() ?: requestedRange.substringBefore(".."),
                requestedRange = requestedRange,
                syncTimestampEpochMs = System.currentTimeMillis(),
                keySummary = activitySamplesKeySummary(it, requestedRange),
                rawPayloadJson = rawPayloadJson,
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.PARSED.name
            )
        }
        val filtered = filterNewRecords(records, dao.getExistingActivitySamplePayloads(deviceId))
        filtered.forEach { dao.deleteActivitySampleRecordsForDate(deviceId, it.sourceDate) }
        dao.insertActivitySampleRecords(filtered)
        rebuildActivityEpochsForDates(records.map { it.sourceDate }.distinct())
    }

    private suspend fun rebuildActivityEpochsForDates(sourceDates: List<String>): Int {
        val normalizedDates = sourceDates.distinct().filter { it.isNotBlank() }
        if (normalizedDates.isEmpty()) return 0
        val records = dao.getActivitySampleRawRecordsForDates(normalizedDates)
        val updatedAt = System.currentTimeMillis()
        val epochs = records.flatMap { activityEpochsFromRaw(it, updatedAt) }
        dao.deleteActivityEpochsForDates(normalizedDates)
        if (epochs.isNotEmpty()) {
            dao.upsertActivityEpochs(epochs)
        }
        return epochs.size
    }

    private fun activityEpochsFromRaw(
        record: ActivitySamplesRawEntity,
        updatedAtEpochMs: Long
    ): List<ActivityEpochEntity> {
        val root = runCatching { GsonProvider.gson.fromJson(record.rawPayloadJson, JsonObject::class.java) }.getOrNull()
            ?: return emptyList()
        val output = linkedMapOf<Long, ActivityEpochDraft>()
        root.getAsJsonArray("polarActivitySamplesDataList")
            ?.forEach { sessionElement ->
                val session = sessionElement.asJsonObjectOrNull() ?: return@forEach
                val start = session.stringOrNull("startTime")?.let(::parsePolarDateTimeEpochMs) ?: return@forEach
                val metInterval = session.intOrNull("metRecordingInterval")
                val stepInterval = session.intOrNull("stepRecordingInterval")
                session.getAsJsonArray("metSamples")?.forEachIndexed { index, value ->
                    val interval = metInterval ?: return@forEachIndexed
                    val epochStart = start + (index.toLong() * interval * 1_000L)
                    val draft = output.getOrPut(epochStart) { ActivityEpochDraft() }
                    draft.met = runCatching { value.takeUnless { it.isJsonNull }?.asDouble }.getOrNull()
                    draft.metInterval = interval
                }
                session.getAsJsonArray("stepSamples")?.forEachIndexed { index, value ->
                    val interval = stepInterval ?: return@forEachIndexed
                    val epochStart = start + (index.toLong() * interval * 1_000L)
                    val draft = output.getOrPut(epochStart) { ActivityEpochDraft() }
                    draft.steps = value.asIntOrNull()
                    draft.stepInterval = interval
                }
                session.getAsJsonArray("activityInfoList")?.forEach { infoElement ->
                    val info = infoElement.asJsonObjectOrNull() ?: return@forEach
                    val epochStart = info.stringOrNull("timeStamp")?.let(::parsePolarDateTimeEpochMs) ?: return@forEach
                    val draft = output.getOrPut(epochStart) { ActivityEpochDraft() }
                    draft.activityClass = info.stringOrNull("activityClass")
                    draft.activityFactor = info.doubleOrNull("factor")
                }
            }
        return output.toSortedMap().map { (epochStart, draft) ->
            val intervalSeconds = listOfNotNull(draft.metInterval, draft.stepInterval).minOrNull() ?: 30
            ActivityEpochEntity(
                deviceId = record.deviceId,
                sourceDate = Instant.ofEpochMilli(epochStart).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
                epochStartEpochMs = epochStart,
                epochEndEpochMs = epochStart + (intervalSeconds * 1_000L),
                met = draft.met,
                steps = draft.steps,
                activityClass = draft.activityClass,
                activityFactor = draft.activityFactor,
                metRecordingIntervalSeconds = draft.metInterval,
                stepRecordingIntervalSeconds = draft.stepInterval,
                updatedAtEpochMs = updatedAtEpochMs
            )
        }
    }

    private data class ActivityEpochDraft(
        var met: Double? = null,
        var steps: Int? = null,
        var activityClass: String? = null,
        var activityFactor: Double? = null,
        var metInterval: Int? = null,
        var stepInterval: Int? = null
    )

    suspend fun exportInspectorData(context: Context): Result<File> {
        val rows = withContext(Dispatchers.IO) { inspectorRows.first() }
        return ExportManager(context).exportInspectorRows(rows)
    }

    private fun inferShape(result: List<*>): String {
        if (result.isEmpty()) return "empty list"
        val nullCount = result.count { it == null }
        return "list(size=${result.size}, nulls=$nullCount, first=${result.firstOrNull()?.javaClass?.simpleName})"
    }

    private fun shapeForSleep(data: List<PolarSleepData>): String =
        "list(size=${data.size}, wakePhases=${data.sumOf { it.result?.sleepWakePhases?.size ?: 0 }})"

    private fun shapeForNightlyRecharge(data: List<PolarNightlyRechargeData>): String =
        "list(size=${data.size}, nullAns=${data.count { it.ansStatus == null }})"

    private fun shapeForHr(data: List<Polar247HrSamplesData>): String =
        "list(size=${data.size}, sessions=${data.sumOf { it.samples.size }}, batched=variable)"

    private fun shapeForPpi(data: List<Polar247PPiSamplesData>): String =
        "list(size=${data.size}, samples=${data.sumOf { it.samples.ppiValueList.size }}, batched=variable)"

    private fun shapeForSkinTemperature(data: List<PolarSkinTemperatureData>): String =
        "list(size=${data.size}, samples=${data.sumOf { it.result?.skinTemperatureList?.size ?: 0 }})"

    private fun shapeForDailySummary(data: List<PolarDailySummaryData>): String =
        "list(size=${data.size}, steps=${data.sumOf { it.steps?.toLong() ?: 0L }}, distance=${data.sumOf { it.activityDistance?.toDouble() ?: 0.0 }})"

    private fun shapeForActivitySamples(data: List<PolarActivitySamplesDayData>): String =
        "list(size=${data.size}, metSamples=${data.sumOf { day -> day.polarActivitySamplesDataList.orEmpty().sumOf { it.metSamples.size } }}, stepSamples=${data.sumOf { day -> day.polarActivitySamplesDataList.orEmpty().sumOf { it.stepSamples.size } }})"

    private fun extractSourceDate(rawPayloadJson: String, requestedRange: String): String {
        val json = runCatching { GsonProvider.gson.fromJson(rawPayloadJson, JsonObject::class.java) }.getOrNull()
        val candidates = listOf("date", "sleepResultDate")
        for (candidate in candidates) {
            val value = json?.get(candidate)
            if (value != null && !value.isJsonNull) {
                return value.toString().trim('"')
            }
        }
        return requestedRange.substringBefore("..")
    }

    private fun genericKeySummary(rawPayloadJson: String, requestedRange: String): String {
        val json = runCatching { GsonProvider.gson.fromJson(rawPayloadJson, JsonObject::class.java) }.getOrNull()
        val availableKeys = json?.entrySet()?.map { it.key }?.sorted()?.take(6)?.joinToString(",") ?: "unknown"
        return "keys=$availableKeys, range=$requestedRange"
    }

    private fun skinTemperatureKeySummary(data: PolarSkinTemperatureData): String {
        val temperatures = data.result?.skinTemperatureList.orEmpty().map { it.temperature }
        val average = temperatures.takeIf { it.isNotEmpty() }?.average()
        val averageText = average?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "n/a"
        return "date=${data.date}, samples=${temperatures.size}, avg=$averageText"
    }

    private fun dailySummaryKeySummary(data: PolarDailySummaryData): String =
        "date=${data.date}, steps=${data.steps ?: 0}, distance=${data.activityDistance ?: 0.0}, calories=${data.activityCalories ?: 0}"

    private fun activitySamplesKeySummary(data: PolarActivitySamplesDayData, requestedRange: String): String {
        val sessions = data.polarActivitySamplesDataList.orEmpty()
        val firstStart = sessions.firstOrNull()?.startTime
        val totalSteps = sessions.sumOf { it.stepSamples.sum() }
        val metCount = sessions.sumOf { it.metSamples.size }
        val startLabel = firstStart?.toString() ?: requestedRange.substringBefore("..")
        return "start=$startLabel, sessions=${sessions.size}, metSamples=$metCount, totalSteps=$totalSteps"
    }

    private fun deriveMorningRead(
        sleepRow: SleepNightRawEntity?,
        nightlyRow: NightlyRechargeRawEntity?,
        offlinePpiEpochs: List<OfflinePpiEpochEntity>,
        ppi247Epochs: List<Ppi247EpochEntity>
    ): MorningReadSnapshot? {
        if (sleepRow == null && nightlyRow == null && offlinePpiEpochs.isEmpty() && ppi247Epochs.isEmpty()) return null

        val expectedSourceDate = LocalDate.now(ZoneId.systemDefault()).toString()
        if (sleepRow?.sourceDate != expectedSourceDate) {
            val hasRawPpi = ppi247Epochs.isNotEmpty() || offlinePpiEpochs.isNotEmpty()
            return MorningReadSnapshot(
                sourceDate = expectedSourceDate,
                status = null,
                confidence = "interim",
                overnightAutonomicSource = if (hasRawPpi) "raw_ppi_pending_sleep_window" else "awaiting_sleep_data",
                sleepDurationMinutes = null,
                nightlyRmssd = null,
                baselineReady = false,
                recoveryAvailable = false,
                summary = "Interim: waiting for Polar sleep data",
                reasons = listOf(
                    "Today’s resolved sleep window is not available yet.",
                    if (hasRawPpi) {
                        "Raw PPI has been fetched, but it cannot be sleep-window scored until the sleep window is known."
                    } else {
                        "The app will keep checking for the completed sleep report."
                    }
                ),
                isInterim = true,
                sleepDataReady = false
            )
        }

        val sleepJson = sleepRow.rawPayloadJson.let {
            runCatching { GsonProvider.gson.fromJson(it, JsonObject::class.java) }.getOrNull()
        }
        val nightlyJson = nightlyRow?.rawPayloadJson?.let {
            runCatching { GsonProvider.gson.fromJson(it, JsonObject::class.java) }.getOrNull()
        }
        val sleepResult = sleepJson?.getAsJsonObject("result")
        val sleepSummary = sleepResult?.getAsJsonObject("summary")
        val nightlySummary = nightlyJson?.getAsJsonObject("summary")

        val durationMinutes = sleepSummary?.get("durationMinutes")?.takeUnless { it.isJsonNull }?.asInt
        val sleepStartEpochMs = sleepResult
            ?.get("sleepStartTime")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.let(::parsePolarDateTimeEpochMs)
        val sleepEndEpochMs = sleepResult
            ?.get("sleepEndTime")
            ?.takeUnless { it.isJsonNull }
            ?.asString
            ?.let(::parsePolarDateTimeEpochMs)
        if (durationMinutes == null || sleepStartEpochMs == null || sleepEndEpochMs == null) {
            val hasRawPpi = ppi247Epochs.isNotEmpty() || offlinePpiEpochs.isNotEmpty()
            return MorningReadSnapshot(
                sourceDate = expectedSourceDate,
                status = null,
                confidence = "interim",
                overnightAutonomicSource = if (hasRawPpi) "raw_ppi_pending_sleep_window" else "awaiting_sleep_data",
                sleepDurationMinutes = null,
                nightlyRmssd = null,
                baselineReady = false,
                recoveryAvailable = false,
                summary = "Interim: waiting for resolved Polar sleep window",
                reasons = listOf(
                    "Polar has created today’s sleep record, but the resolved start/end times are not available yet.",
                    if (hasRawPpi) {
                        "Raw PPI has been fetched, but it cannot be sleep-window scored until those times arrive."
                    } else {
                        "The app will keep checking for the completed sleep report."
                    }
                ),
                isInterim = true,
                sleepDataReady = false
            )
        }
        val cycleCount = sleepSummary?.get("cycleCount")?.takeUnless { it.isJsonNull }?.asInt
        val wakePhases = sleepSummary
            ?.getAsJsonObject("phaseCounts")
            ?.get("AWAKE")
            ?.takeUnless { it.isJsonNull }
            ?.asInt
        val rmssd = nightlySummary?.get("meanNightlyRecoveryRMSSD")?.takeUnless { it.isJsonNull }?.asDouble
        val baselineReady = nightlySummary?.get("baselineReady")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val recoveryAvailable = nightlySummary?.get("recoveryAvailable")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val ansAvailable = nightlySummary?.get("ansAvailable")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
        val ppi247Autonomic = summarizePpi247ForSleepWindow(
            sourceDate = sleepRow.sourceDate,
            sleepStartEpochMs = sleepStartEpochMs,
            sleepEndEpochMs = sleepEndEpochMs,
            epochs = ppi247Epochs
        )
        val offlineAutonomic = summarizeOfflinePpiForSleepWindow(
            sourceDate = sleepRow.sourceDate,
            sleepStartEpochMs = sleepStartEpochMs,
            sleepEndEpochMs = sleepEndEpochMs,
            epochs = offlinePpiEpochs
        )
        val autonomicRmssd = ppi247Autonomic?.averageRmssdMs ?: offlineAutonomic?.averageRmssdMs ?: rmssd
        val autonomicSource = when {
            ppi247Autonomic != null -> "ppi247_sleep_window"
            offlineAutonomic != null -> "offline_ppi_sleep_window"
            nightlyRow != null -> "nightly_recharge_summary"
            else -> "sleep_context_only"
        }

        var score = 0.0
        val reasons = mutableListOf<String>()

        when {
            durationMinutes == null -> reasons += "Sleep duration is unavailable."
            durationMinutes >= 450 -> {
                score += 1.0
                reasons += "Sleep duration looked solid at ${durationMinutes / 60}h ${durationMinutes % 60}m."
            }
            durationMinutes >= 390 -> reasons += "Sleep duration looked acceptable at ${durationMinutes / 60}h ${durationMinutes % 60}m."
            durationMinutes >= 330 -> {
                score -= 0.8
                reasons += "Sleep duration looked short at ${durationMinutes / 60}h ${durationMinutes % 60}m."
            }
            else -> {
                score -= 1.5
                reasons += "Sleep duration looked very short at ${durationMinutes.div(60)}h ${durationMinutes.rem(60)}m."
            }
        }

        when {
            autonomicRmssd == null -> reasons += "Overnight autonomic data is unavailable."
            autonomicRmssd >= 75 -> {
                score += 1.0
                reasons += "${autonomicSourceLabel(autonomicSource)} RMSSD looked strong at ${autonomicRmssd.toInt()}."
            }
            autonomicRmssd >= 60 -> reasons += "${autonomicSourceLabel(autonomicSource)} RMSSD looked broadly OK at ${autonomicRmssd.toInt()}."
            autonomicRmssd >= 45 -> {
                score -= 0.8
                reasons += "${autonomicSourceLabel(autonomicSource)} RMSSD looked somewhat suppressed at ${autonomicRmssd.toInt()}."
            }
            else -> {
                score -= 1.5
                reasons += "${autonomicSourceLabel(autonomicSource)} RMSSD looked low at ${autonomicRmssd.toInt()}."
            }
        }

        if (ppi247Autonomic != null) {
            reasons += "24/7 PPI covered ${formatHours(ppi247Autonomic.coverageHours)} of the resolved sleep window (${ppi247Autonomic.goodEpochCount} good epochs)."
            ppi247Autonomic.lateMinusEarlyRmssdMs?.let { delta ->
                when {
                    delta >= 8.0 -> {
                        score += 0.25
                        reasons += "Overnight RMSSD rose toward morning."
                    }
                    delta <= -8.0 -> {
                        score -= 0.35
                        reasons += "Overnight RMSSD fell toward morning."
                    }
                }
            }
            if (ppi247Autonomic.poorEpochCount > ppi247Autonomic.goodEpochCount / 4) {
                score -= 0.15
                reasons += "24/7 PPI had some flagged contact/error windows."
            }
        } else if (offlineAutonomic != null) {
            reasons += "Offline PPI covered ${formatHours(offlineAutonomic.coverageHours)} of the resolved sleep window (${offlineAutonomic.goodEpochCount} good epochs)."
            offlineAutonomic.lateMinusEarlyRmssdMs?.let { delta ->
                when {
                    delta >= 8.0 -> {
                        score += 0.25
                        reasons += "Overnight RMSSD rose toward morning."
                    }
                    delta <= -8.0 -> {
                        score -= 0.35
                        reasons += "Overnight RMSSD fell toward morning."
                    }
                }
            }
            if (offlineAutonomic.poorEpochCount > offlineAutonomic.goodEpochCount / 4) {
                score -= 0.15
                reasons += "Offline PPI had some flagged contact/error windows."
            }
        } else if (sleepStartEpochMs != null && sleepEndEpochMs != null) {
            reasons += "No usable raw PPI overlapped the resolved sleep window."
        }

        if (wakePhases != null && wakePhases >= 40) {
            score -= 0.4
            reasons += "Sleep looked fragmented with many wake phases."
        }
        if (cycleCount != null && cycleCount >= 6) {
            score += 0.2
        }

        if (baselineReady) {
            score += 0.25
        } else {
            reasons += "Baseline history is not fully ready yet."
        }
        if (ppi247Autonomic == null && offlineAutonomic == null && (!recoveryAvailable || !ansAvailable)) {
            score -= 0.15
            reasons += "Polar's higher-level overnight interpretation is still immature."
        } else if ((ppi247Autonomic != null || offlineAutonomic != null) && (!recoveryAvailable || !ansAvailable)) {
            reasons += "Nightly Recharge interpretation is immature, but raw PPI is available."
        }

        val status = when {
            score >= 1.5 -> TrafficLightStatus.GOOD
            score >= 0.0 -> TrafficLightStatus.OK
            score >= -1.25 -> TrafficLightStatus.UNSTEADY
            else -> TrafficLightStatus.CRASH
        }
        val confidence = when {
            ppi247Autonomic != null && ppi247Autonomic.goodEpochCount >= 48 && baselineReady -> "high"
            ppi247Autonomic != null && ppi247Autonomic.goodEpochCount >= 12 -> "medium"
            offlineAutonomic != null && offlineAutonomic.goodEpochCount >= 48 && baselineReady -> "high"
            offlineAutonomic != null && offlineAutonomic.goodEpochCount >= 12 -> "medium"
            nightlyRow == null -> "low"
            !baselineReady || !recoveryAvailable || !ansAvailable -> "medium"
            else -> "high"
        }
        val sourceDate = listOfNotNull(
            sleepRow.sourceDate,
            nightlyRow?.sourceDate,
            sleepSummary?.get("sleepResultDate")?.takeUnless { it.isJsonNull }?.asString,
            nightlySummary?.get("sleepResultDate")?.takeUnless { it.isJsonNull }?.asString
        ).firstOrNull()

        return MorningReadSnapshot(
            sourceDate = sourceDate,
            status = status,
            confidence = confidence,
            overnightAutonomicSource = autonomicSource,
            sleepDurationMinutes = durationMinutes,
            nightlyRmssd = autonomicRmssd,
            baselineReady = baselineReady,
            recoveryAvailable = recoveryAvailable,
            summary = "${status.name.lowercase().replaceFirstChar { it.titlecase() }} ($confidence confidence)",
            reasons = reasons,
            isInterim = false,
            sleepDataReady = true,
            offlinePpiGoodEpochCount = ppi247Autonomic?.goodEpochCount ?: offlineAutonomic?.goodEpochCount,
            offlinePpiPoorEpochCount = ppi247Autonomic?.poorEpochCount ?: offlineAutonomic?.poorEpochCount,
            offlinePpiCoverageHours = ppi247Autonomic?.coverageHours ?: offlineAutonomic?.coverageHours
        )
    }

    private fun summarizeOfflinePpiForSleepWindow(
        sourceDate: String?,
        sleepStartEpochMs: Long?,
        sleepEndEpochMs: Long?,
        epochs: List<OfflinePpiEpochEntity>
    ): OfflinePpiWindowSummary? {
        if (sleepStartEpochMs == null || sleepEndEpochMs == null || sleepEndEpochMs <= sleepStartEpochMs) return null
        val windowEpochs = epochs
            .asSequence()
            .filter { epoch ->
                epoch.epochStartEpochMs >= sleepStartEpochMs &&
                    epoch.epochEndEpochMs <= sleepEndEpochMs &&
                    (sourceDate == null || epoch.sourceDate == sourceDate || epoch.epochStartEpochMs >= sleepStartEpochMs)
            }
            .sortedBy { it.epochStartEpochMs }
            .toList()
        val goodEpochs = windowEpochs.filter { it.epochQuality == "good" && it.rmssdMs != null }
        if (goodEpochs.size < 12) return null
        val rmssdValues = goodEpochs.mapNotNull { it.rmssdMs }
        val firstChunkSize = max(1, goodEpochs.size / 3)
        val earlyAverage = goodEpochs.take(firstChunkSize).mapNotNull { it.rmssdMs }.averageOrNull()
        val lateAverage = goodEpochs.takeLast(firstChunkSize).mapNotNull { it.rmssdMs }.averageOrNull()
        return OfflinePpiWindowSummary(
            averageRmssdMs = rmssdValues.average(),
            minRmssdMs = rmssdValues.minOrNull(),
            maxRmssdMs = rmssdValues.maxOrNull(),
            goodEpochCount = goodEpochs.size,
            poorEpochCount = windowEpochs.count { it.epochQuality.startsWith("poor") },
            coverageHours = (goodEpochs.sumOf { (it.epochEndEpochMs - it.epochStartEpochMs).coerceAtLeast(0L) } / 3_600_000.0),
            lateMinusEarlyRmssdMs = if (earlyAverage != null && lateAverage != null) lateAverage - earlyAverage else null
        )
    }

    private fun summarizePpi247ForSleepWindow(
        sourceDate: String?,
        sleepStartEpochMs: Long?,
        sleepEndEpochMs: Long?,
        epochs: List<Ppi247EpochEntity>
    ): Ppi247WindowSummary? {
        if (sleepStartEpochMs == null || sleepEndEpochMs == null || sleepEndEpochMs <= sleepStartEpochMs) return null
        val windowEpochs = epochs
            .asSequence()
            .filter { epoch ->
                epoch.epochStartEpochMs >= sleepStartEpochMs &&
                    epoch.epochEndEpochMs <= sleepEndEpochMs &&
                    (sourceDate == null || epoch.sourceDate == sourceDate || epoch.epochStartEpochMs >= sleepStartEpochMs)
            }
            .sortedBy { it.epochStartEpochMs }
            .toList()
        val goodEpochs = windowEpochs.filter { it.epochQuality == "good" && it.rmssdMs != null }
        if (goodEpochs.size < 12) return null
        val rmssdValues = goodEpochs.mapNotNull { it.rmssdMs }
        val firstChunkSize = max(1, goodEpochs.size / 3)
        val earlyAverage = goodEpochs.take(firstChunkSize).mapNotNull { it.rmssdMs }.averageOrNull()
        val lateAverage = goodEpochs.takeLast(firstChunkSize).mapNotNull { it.rmssdMs }.averageOrNull()
        return Ppi247WindowSummary(
            averageRmssdMs = rmssdValues.average(),
            minRmssdMs = rmssdValues.minOrNull(),
            maxRmssdMs = rmssdValues.maxOrNull(),
            goodEpochCount = goodEpochs.size,
            poorEpochCount = windowEpochs.count { it.epochQuality.startsWith("poor") },
            coverageHours = (goodEpochs.sumOf { (it.epochEndEpochMs - it.epochStartEpochMs).coerceAtLeast(0L) } / 3_600_000.0),
            lateMinusEarlyRmssdMs = if (earlyAverage != null && lateAverage != null) lateAverage - earlyAverage else null
        )
    }

    private fun parsePolarDateTimeEpochMs(value: String): Long? =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrNull()

    private fun List<Double>.averageOrNull(): Double? =
        if (isEmpty()) null else average()

    private fun autonomicSourceLabel(source: String): String =
        when (source) {
            "ppi247_sleep_window" -> "24/7 PPI"
            "offline_ppi_sleep_window" -> "Offline PPI"
            "nightly_recharge_summary" -> "Nightly Recharge"
            else -> "Overnight"
        }

    private fun formatHours(hours: Double): String =
        "${String.format(java.util.Locale.UK, "%.1f", hours)}h"

    private fun <T> filterNewRecords(records: List<T>, existingPayloads: List<String>): List<T> where T : Any {
        if (records.isEmpty()) return emptyList()
        val seenPayloads = existingPayloads.toMutableSet()
        val filtered = ArrayList<T>(records.size)
        for (record in records) {
            val payload = when (record) {
                is SleepNightRawEntity -> record.rawPayloadJson
                is NightlyRechargeRawEntity -> record.rawPayloadJson
                is Hr247DayRawEntity -> record.rawPayloadJson
                is Ppi247DayRawEntity -> record.rawPayloadJson
                is SkinTemperatureRawEntity -> record.rawPayloadJson
                is DailySummaryRawEntity -> record.rawPayloadJson
                is ActivitySamplesRawEntity -> record.rawPayloadJson
                else -> null
            } ?: continue
            if (seenPayloads.add(payload)) {
                filtered.add(record)
            }
        }
        return filtered
    }
}

data class DomainPersistenceResult(
    val recordCount: Int,
    val shapeNotes: String,
    val rawPayloadJson: String
)

private data class TrainingFetchSummary(
    val session: PolarTrainingSession?,
    val progressSummary: Map<String, Any?>
)

private data class OfflineRecordingCandidate(
    val kind: String,
    val entry: PolarOfflineRecordingEntry
)

private data class OfflineFetchedCandidate(
    val kind: String,
    val entry: PolarOfflineRecordingEntry,
    val result: Result<PolarOfflineRecordingData>
)

private data class OfflineRecordingDataSummary(
    val sampleCount: Int,
    val samplePreview: List<Any?>,
    val samples: List<Any?>?,
    val notes: Map<String, Any?>
)

private data class OfflinePpiWindowSummary(
    val averageRmssdMs: Double,
    val minRmssdMs: Double?,
    val maxRmssdMs: Double?,
    val goodEpochCount: Int,
    val poorEpochCount: Int,
    val coverageHours: Double,
    val lateMinusEarlyRmssdMs: Double?
)

private data class Ppi247WindowSummary(
    val averageRmssdMs: Double,
    val minRmssdMs: Double?,
    val maxRmssdMs: Double?,
    val goodEpochCount: Int,
    val poorEpochCount: Int,
    val coverageHours: Double,
    val lateMinusEarlyRmssdMs: Double?
)

private fun SleepNightRawEntity.hasResolvedSleepWindow(): Boolean {
    val json = runCatching { GsonProvider.gson.fromJson(rawPayloadJson, JsonObject::class.java) }.getOrNull()
    val result = json?.getAsJsonObject("result")
    val summary = result?.getAsJsonObject("summary")
    val durationMinutes = summary?.get("durationMinutes")?.takeUnless { it.isJsonNull }?.asInt
    val sleepStart = result?.get("sleepStartTime")?.takeUnless { it.isJsonNull }?.asString
    val sleepEnd = result?.get("sleepEndTime")?.takeUnless { it.isJsonNull }?.asString
    return durationMinutes != null && sleepStart != null && sleepEnd != null
}
