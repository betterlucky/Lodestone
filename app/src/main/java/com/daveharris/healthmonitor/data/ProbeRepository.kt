package com.daveharris.healthmonitor.data

import android.content.Context
import androidx.room.withTransaction
import com.daveharris.healthmonitor.util.ExportManager
import com.daveharris.healthmonitor.util.GsonProvider
import com.daveharris.healthmonitor.polar.PolarProbeManager
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.model.PolarDiskSpaceData
import com.polar.sdk.api.model.PolarOfflineRecordingData
import com.polar.sdk.api.model.PolarOfflineRecordingEntry
import com.polar.sdk.api.model.activity.Polar247HrSamplesData
import com.polar.sdk.api.model.activity.Polar247PPiSamplesData
import com.polar.sdk.api.model.activity.PolarActivitySamplesDayData
import com.polar.sdk.api.model.activity.PolarDailySummaryData
import com.polar.sdk.api.model.sleep.PolarNightlyRechargeData
import com.polar.sdk.api.model.sleep.PolarSleepData
import com.polar.sdk.api.model.PolarSkinTemperatureData
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
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
    val morningRead = combine(
        dao.observeLatestSleepRecord(),
        dao.observeLatestNightlyRechargeRecord(),
        dao.observeRecentPpi247Epochs(),
        dao.observeRecentWakeMarkers()
    ) { sleep, nightly, ppi247Epochs, wakeMarkers ->
        deriveMorningRead(sleep, nightly, ppi247Epochs, wakeMarkers)
    }
    val recentWakeMarkers = dao.observeRecentWakeMarkers()

    init {
        scope.launch {
            recoverStaleRunningSyncRuns()
            rebuildHr247EpochTables(pruneRaw = true)
            rebuildContextEpochTables(pruneRaw = true)
            pruneOversizedSyncResultPayloads()
        }
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

    suspend fun hasPpiRecordForDate(sourceDate: String): Boolean =
        dao.countPpi247EpochsForDate(sourceDate) > 0

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

    suspend fun runManualSync(
        deviceId: String,
        config: SyncWindowConfig,
        profile: SyncRunProfile = SyncRunProfile.FULL
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedConfig = config.normalized()
            val runtime = runtimeState.value
            if (dao.countCapabilities(deviceId) == 0 || hasFirmwareChanged(deviceId, runtime.firmwareVersion)) {
                runCapabilityDiscovery(deviceId).getOrThrow()
            }

            val syncRunId = acquireManualSyncRun(deviceId, runtime.firmwareVersion, profile)
            val domainFailures = mutableListOf<SyncDomainFailure>()

            try {
                withTimeout(MANUAL_SYNC_TIMEOUT_MS) {
                    runWithinSyncSession(deviceId, "manual_sync") {
                        val now = LocalDate.now(ZoneOffset.UTC)
                        buildSyncTasks(deviceId, normalizedConfig, profile, now).forEach { task ->
                            syncDomain(
                                syncRunId = syncRunId,
                                deviceId = deviceId,
                                domain = task.domain,
                                from = task.from,
                                to = task.to,
                                timeoutMs = task.timeoutMs,
                                block = task.block
                            )?.let { error ->
                                domainFailures += SyncDomainFailure(task.domain, error)
                            }
                        }
                    }
                }

                withContext(NonCancellable) {
                    dao.pruneLargeSyncDomainResultPayloads()
                    val existingRun = dao.getSyncRun(syncRunId)
                    if (existingRun != null) {
                        val status = if (domainFailures.isEmpty()) "success" else "partial_failure"
                        dao.updateSyncRun(
                            existingRun.copy(
                                endedAtEpochMs = System.currentTimeMillis(),
                                status = status,
                                notes = syncCompletionNotes(profile, domainFailures)
                            )
                        )
                    }
                }
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    val existingRun = dao.getSyncRun(syncRunId)
                    if (existingRun != null) {
                        dao.updateSyncRun(
                            existingRun.copy(
                                endedAtEpochMs = System.currentTimeMillis(),
                                status = "partial_failure",
                                notes = error.message ?: error.javaClass.simpleName
                            )
                        )
                    }
                }
                throw error
            }

            syncRunId
        }
    }

    private suspend fun recoverStaleRunningSyncRuns() {
        val now = System.currentTimeMillis()
        dao.markStaleRunningSyncRuns(
            cutoffEpochMs = now - STALE_RUNNING_SYNC_AFTER_MS,
            endedAtEpochMs = now,
            status = "partial_failure",
            notes = "stale running sync marked interrupted before new sync"
        )
    }

    private suspend fun hasRecentRunningSyncRun(): Boolean {
        val cutoff = System.currentTimeMillis() - STALE_RUNNING_SYNC_AFTER_MS
        return dao.countRecentRunningSyncRuns(cutoff) > 0
    }

    private fun buildSyncTasks(
        deviceId: String,
        config: SyncWindowConfig,
        profile: SyncRunProfile,
        now: LocalDate
    ): List<SyncDomainTask> = buildList {
        fun addSleepTask() {
            add(
                SyncDomainTask(
                    domain = ProbeDomain.SLEEP,
                    from = now.minusDays(config.sleepDays.toLong()),
                    to = now,
                    timeoutMs = SLEEP_SYNC_TIMEOUT_MS
                ) {
                    val data = polarManager.fetchSleep(deviceId, it.first, it.second)
                    persistSleep(deviceId, data, "${it.first}..${it.second}")
                    DomainPersistenceResult(data.size, shapeForSleep(data))
                }
            )
        }

        fun addNightlyRechargeTask() {
            add(
                SyncDomainTask(
                    domain = ProbeDomain.NIGHTLY_RECHARGE,
                    from = now.minusDays(config.nightlyRechargeDays.toLong()),
                    to = now,
                    timeoutMs = NIGHTLY_RECHARGE_SYNC_TIMEOUT_MS
                ) {
                    val data = polarManager.fetchNightlyRecharge(deviceId, it.first, it.second)
                    persistNightlyRecharge(deviceId, data, "${it.first}..${it.second}")
                    DomainPersistenceResult(data.size, shapeForNightlyRecharge(data))
                }
            )
        }

        fun addPpiTask() {
            // PPI is the most valuable morning trajectory lane, so it runs before
            // less critical background HR. A flaky HR fetch must not block PPI.
            add(
                SyncDomainTask(
                    domain = ProbeDomain.PPI_247,
                    from = now.minusDays(config.ppiDays.toLong()),
                    to = now,
                    timeoutMs = PPI_SYNC_TIMEOUT_MS
                ) {
                    val data = polarManager.fetch247Ppi(deviceId, it.first, it.second)
                    persistPpi(deviceId, data, "${it.first}..${it.second}")
                    DomainPersistenceResult(data.size, shapeForPpi(data))
                }
            )
        }

        fun addHrTask() {
            add(
                SyncDomainTask(
                    domain = ProbeDomain.HR_247,
                    from = now.minusDays(config.hrDays.toLong()),
                    to = now,
                    timeoutMs = HR_SYNC_TIMEOUT_MS
                ) {
                    val data = polarManager.fetch247Hr(deviceId, it.first, it.second)
                    persistHr(deviceId, data, "${it.first}..${it.second}")
                    DomainPersistenceResult(data.size, shapeForHr(data))
                }
            )
        }

        when (profile) {
            SyncRunProfile.MORNING_PPI_RETRY -> addPpiTask()
            SyncRunProfile.MORNING_SLEEP_RETRY -> {
                addSleepTask()
                addNightlyRechargeTask()
            }
            SyncRunProfile.MORNING_CORE,
            SyncRunProfile.FULL -> {
                addSleepTask()
                addNightlyRechargeTask()
                addPpiTask()
                addHrTask()
            }
        }

        if (profile == SyncRunProfile.FULL) {
            add(
                SyncDomainTask(
                    domain = ProbeDomain.SKIN_TEMPERATURE,
                    from = now.minusDays(config.hrDays.toLong()),
                    to = now,
                    timeoutMs = SKIN_TEMPERATURE_SYNC_TIMEOUT_MS
                ) {
                    val data = polarManager.fetchSkinTemperature(deviceId, it.first, it.second)
                    persistSkinTemperature(deviceId, data, "${it.first}..${it.second}")
                    DomainPersistenceResult(data.size, shapeForSkinTemperature(data))
                }
            )
            add(
                SyncDomainTask(
                    domain = ProbeDomain.DAILY_SUMMARY,
                    from = now.minusDays(config.sleepDays.toLong()),
                    to = now,
                    timeoutMs = DAILY_SUMMARY_SYNC_TIMEOUT_MS
                ) {
                    val data = polarManager.fetchDailySummary(deviceId, it.first, it.second)
                    persistDailySummary(deviceId, data, "${it.first}..${it.second}")
                    DomainPersistenceResult(data.size, shapeForDailySummary(data))
                }
            )
            if (ENABLE_ACTIVITY_SAMPLE_SYNC) {
                add(
                    SyncDomainTask(
                        domain = ProbeDomain.ACTIVITY_SAMPLES,
                        from = now.minusDays(config.hrDays.toLong()),
                        to = now,
                        timeoutMs = ACTIVITY_SAMPLE_SYNC_TIMEOUT_MS
                    ) {
                        val data = polarManager.fetchActivitySamples(deviceId, it.first, it.second)
                        persistActivitySamples(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForActivitySamples(data))
                    }
                )
            }
        }
    }

    private fun syncCompletionNotes(
        profile: SyncRunProfile,
        failures: List<SyncDomainFailure>
    ): String =
        if (failures.isEmpty()) {
            profile.successNotes
        } else {
            val failedDomains = failures.joinToString(",") { failure ->
                "${failure.domain.name}:${failure.error.javaClass.simpleName}"
            }
            "${profile.runNotes} completed with domain failures: $failedDomains"
        }

    private suspend fun pruneOversizedSyncResultPayloads() = withContext(Dispatchers.IO) {
        val prunedRows = dao.pruneLargeSyncDomainResultPayloads()
        val dbFile = database.openHelper.writableDatabase.path?.let(::File)
        if (prunedRows > 0 || (dbFile?.length() ?: 0L) > DATABASE_COMPACTION_THRESHOLD_BYTES) {
            compactDatabase()
        }
    }

    private fun compactDatabase() {
        val db = database.openHelper.writableDatabase
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        runCatching { db.execSQL("VACUUM") }
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
    }

    private suspend fun acquireManualSyncRun(
        deviceId: String,
        firmwareVersion: String?,
        profile: SyncRunProfile
    ): Long = database.withTransaction {
        recoverStaleRunningSyncRuns()
        check(!hasRecentRunningSyncRun()) { "Sync already running" }
        dao.insertSyncRun(
            SyncRunEntity(
                deviceId = deviceId,
                firmwareVersion = firmwareVersion,
                appVersion = APP_VERSION,
                startedAtEpochMs = System.currentTimeMillis(),
                endedAtEpochMs = null,
                status = "running",
                notes = profile.runNotes
            )
        )
    }

    suspend fun runPpi247RangeProbe(deviceId: String, from: LocalDate, to: LocalDate): Result<Long> = withContext(Dispatchers.IO) {
        require(!to.isBefore(from)) { "PPI range end must be on or after start" }
        runCatching {
            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = System.currentTimeMillis(),
                    endedAtEpochMs = null,
                    status = "running",
                    notes = "explicit PPI_247 range probe"
                )
            )

            try {
                runWithinSyncSession(deviceId, "ppi247_range_probe") {
                    syncDomain(
                        syncRunId = syncRunId,
                        deviceId = deviceId,
                        domain = ProbeDomain.PPI_247,
                        from = from,
                        to = to,
                        timeoutMs = PPI_SYNC_TIMEOUT_MS
                    ) {
                        val data = polarManager.fetch247Ppi(deviceId, it.first, it.second)
                        persistPpi(deviceId, data, "${it.first}..${it.second}")
                        DomainPersistenceResult(data.size, shapeForPpi(data))
                    }?.let { throw it }
                }

                val existingRun = dao.getSyncRun(syncRunId)
                if (existingRun != null) {
                    dao.updateSyncRun(
                        existingRun.copy(
                            endedAtEpochMs = System.currentTimeMillis(),
                            status = "success",
                            notes = "explicit PPI_247 range probe completed"
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

    suspend fun startOfflinePpiProbe(deviceId: String, minimumBatteryPercent: Int = 20): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val battery = runtimeState.value.batteryLevel
            check(battery == null || battery >= minimumBatteryPercent) {
                "Offline PPI start blocked: Loop battery is $battery%, below $minimumBatteryPercent%."
            }

            val availableTypes = polarManager.getAvailableOfflineRecordingDataTypes(deviceId)
            check(PolarBleApi.PolarDeviceDataType.PPI in availableTypes) {
                "Offline PPI is not available. Available: ${availableTypes.joinToString { it.name }}"
            }
            val activeBefore = runCatching { polarManager.getOfflineRecordingStatus(deviceId) }.getOrDefault(emptyList())
            check(PolarBleApi.PolarDeviceDataType.PPI !in activeBefore) {
                "Offline PPI recording is already active."
            }
            val settings = runCatching {
                polarManager.requestOfflineRecordingSettings(deviceId, PolarBleApi.PolarDeviceDataType.PPI).toString()
            }.getOrNull()

            val startedAt = System.currentTimeMillis()
            polarManager.startOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.PPI)
            val activeAfter = runCatching { polarManager.getOfflineRecordingStatus(deviceId) }.getOrDefault(emptyList())
            val payload = mapOf(
                "purpose" to "overnight_safety_net_offline_ppi_start",
                "deviceId" to deviceId,
                "startedAtEpochMs" to startedAt,
                "minimumBatteryPercent" to minimumBatteryPercent,
                "batteryPercent" to battery,
                "availableTypes" to availableTypes.map { it.name },
                "activeBefore" to activeBefore.map { it.name },
                "activeAfter" to activeAfter.map { it.name },
                "settings" to settings,
                "mode" to "normal_mode_no_sdk_mode",
                "sdkModeUsed" to false
            )
            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = "running",
                    notes = "offline PPI start scheduled/manual"
                )
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.PPI_247.name,
                    requestedRange = "offline_ppi_start",
                    status = ProbeStatus.SUPPORTED.name,
                    recordCount = 0,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "started normal-mode offline PPI",
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

    suspend fun stopAndFetchOfflinePpiProbe(deviceId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val matchedRun = dao.getLatestOfflinePpiStartRun(deviceId)
            val fetchStartedAt = System.currentTimeMillis()
            val runStartedAt = matchedRun?.startedAtEpochMs ?: (fetchStartedAt - 18 * 60 * 60 * 1000L)
            val syncRunId = matchedRun?.id ?: dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = fetchStartedAt,
                    endedAtEpochMs = null,
                    status = "running",
                    notes = "offline PPI stop/fetch without matched start"
                )
            )

            val stopError = runCatching {
                polarManager.stopOfflineRecording(deviceId, PolarBleApi.PolarDeviceDataType.PPI)
            }.exceptionOrNull()
            delay(2_000)

            val regularEntries = runCatching { polarManager.listOfflineRecordings(deviceId) }.getOrDefault(emptyList())
            val splitEntries = runCatching { polarManager.listSplitOfflineRecordings(deviceId) }.getOrDefault(emptyList())
            val candidates = selectOfflinePpiEntriesForRun(runStartedAt, regularEntries, splitEntries)
            val fetched = candidates.map { candidate ->
                val result = runCatching {
                    if (candidate.kind == "split") {
                        polarManager.fetchSplitOfflineRecord(deviceId, candidate.entry)
                    } else {
                        polarManager.fetchOfflineRecord(deviceId, candidate.entry)
                    }
                }
                offlineFetchSummary(candidate.kind, candidate.entry, result)
            }
            val totalSamples = fetched.sumOf { (it["sampleCount"] as? Int) ?: 0 }
            val payload = mapOf(
                "purpose" to "overnight_safety_net_offline_ppi_stop_fetch",
                "deviceId" to deviceId,
                "startedAtEpochMs" to runStartedAt,
                "stoppedAtEpochMs" to fetchStartedAt,
                "stopError" to (stopError?.message ?: stopError?.javaClass?.simpleName),
                "regularEntries" to regularEntries.map(::offlineEntrySummary),
                "splitEntries" to splitEntries.map(::offlineEntrySummary),
                "candidateEntries" to candidates.map { offlineEntrySummary(it.entry) + ("recordingListKind" to it.kind) },
                "fetchedRecords" to fetched,
                "totalSamples" to totalSamples,
                "mode" to "normal_mode_no_sdk_mode",
                "sdkModeUsed" to false
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.PPI_247.name,
                    requestedRange = "offline_ppi_stop_fetch",
                    status = if (totalSamples > 0) ProbeStatus.SUPPORTED.name else ProbeStatus.EMPTY.name,
                    recordCount = candidates.size,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "offline PPI candidates=${candidates.size}, samples=$totalSamples",
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = null,
                    startedAtEpochMs = fetchStartedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = stopError?.javaClass?.simpleName,
                    errorMessage = stopError?.message
                )
            )
            val existingRun = dao.getSyncRun(syncRunId)
            if (existingRun != null) {
                dao.updateSyncRun(
                    existingRun.copy(
                        endedAtEpochMs = System.currentTimeMillis(),
                        status = if (totalSamples > 0) "success" else "partial_failure",
                        notes = "offline PPI stop/fetch completed"
                    )
                )
            }
            syncRunId
        }
    }

    suspend fun runDiskSpaceProbe(deviceId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = System.currentTimeMillis()
            val diskSpace = polarManager.getDiskSpace(deviceId)
            val usedSpace = diskSpace.totalSpace - diskSpace.freeSpace
            val usedPercent = if (diskSpace.totalSpace > 0L) {
                usedSpace.toDouble() / diskSpace.totalSpace.toDouble() * 100.0
            } else {
                null
            }
            val payload = mapOf(
                "deviceId" to deviceId,
                "totalSpaceBytes" to diskSpace.totalSpace,
                "freeSpaceBytes" to diskSpace.freeSpace,
                "usedSpaceBytes" to usedSpace,
                "usedPercent" to usedPercent,
                "checkedAtEpochMs" to startedAt
            )
            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = "success",
                    notes = "disk space probe"
                )
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.DAILY_SUMMARY.name,
                    requestedRange = "disk_space",
                    status = ProbeStatus.SUPPORTED.name,
                    recordCount = 1,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "free=${diskSpace.freeSpace}, total=${diskSpace.totalSpace}, usedPercent=${usedPercent?.let { String.format(java.util.Locale.UK, "%.1f", it) } ?: "unknown"}",
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

    suspend fun removeOfflinePpgExperimentRecords(deviceId: String): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val startedAt = System.currentTimeMillis()
            val diskBefore = polarManager.getDiskSpace(deviceId)
            val regularEntries = runCatching { polarManager.listOfflineRecordings(deviceId) }.getOrDefault(emptyList())
            val splitEntries = runCatching { polarManager.listSplitOfflineRecordings(deviceId) }.getOrDefault(emptyList())
            val ppgEntries = (regularEntries + splitEntries)
                .filter { it.type == PolarBleApi.PolarDeviceDataType.PPG }
                .distinctBy { it.path }
                .sortedBy { it.date }

            val removals = ppgEntries.map { entry ->
                val result = runCatching { polarManager.removeOfflineRecord(deviceId, entry) }
                offlineEntrySummary(entry) + mapOf(
                    "removed" to result.isSuccess,
                    "error" to result.exceptionOrNull()?.message
                )
            }
            val diskAfter = polarManager.getDiskSpace(deviceId)
            val removedBytes = ppgEntries.sumOf { it.size }
            val failedCount = removals.count { it["removed"] != true }
            val status = if (failedCount == 0) "success" else "partial_failure"
            val payload = mapOf(
                "purpose" to "remove_offline_ppg_experiment_records",
                "deviceId" to deviceId,
                "startedAtEpochMs" to startedAt,
                "diskBefore" to diskSpacePayload(diskBefore, startedAt),
                "diskAfter" to diskSpacePayload(diskAfter, System.currentTimeMillis()),
                "regularEntriesBefore" to regularEntries.map(::offlineEntrySummary),
                "splitEntriesBefore" to splitEntries.map(::offlineEntrySummary),
                "removedEntries" to removals,
                "removedEntryCount" to ppgEntries.size,
                "removedBytesAdvertisedByEntries" to removedBytes,
                "failedRemovalCount" to failedCount
            )
            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = status,
                    notes = "offline PPG experiment cleanup"
                )
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.DAILY_SUMMARY.name,
                    requestedRange = "offline_ppg_cleanup",
                    status = if (failedCount == 0) ProbeStatus.SUPPORTED.name else ProbeStatus.PARTIAL.name,
                    recordCount = ppgEntries.size,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "removed=${ppgEntries.size}, failed=$failedCount, advertisedBytes=$removedBytes, freeBefore=${diskBefore.freeSpace}, freeAfter=${diskAfter.freeSpace}",
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = if (failedCount == 0) null else "PARTIAL_REMOVE_FAILURE",
                    errorMessage = if (failedCount == 0) null else "$failedCount offline PPG entries could not be removed"
                )
            )
            syncRunId
        }
    }

    private suspend fun runPostSyncStorageMaintenance(syncRunId: Long, deviceId: String) {
        val startedAt = System.currentTimeMillis()
        runCatching {
            val diskBefore = polarManager.getDiskSpace(deviceId)
            val beforePayload = diskSpacePayload(diskBefore, startedAt)
            val usedPercent = beforePayload["usedPercent"] as? Double
            val cleanupPolicy = storageMaintenancePolicy(usedPercent)
            val regularEntries = runCatching { polarManager.listOfflineRecordings(deviceId) }.getOrDefault(emptyList())
            val splitEntries = runCatching { polarManager.listSplitOfflineRecordings(deviceId) }.getOrDefault(emptyList())
            val removalCandidates = (regularEntries + splitEntries)
                .distinctBy { it.path }
                .filter { entry ->
                    when (entry.type) {
                        PolarBleApi.PolarDeviceDataType.PPG -> entry.date.isBefore(cleanupPolicy.ppgCutoff)
                        PolarBleApi.PolarDeviceDataType.ACC,
                        PolarBleApi.PolarDeviceDataType.SKIN_TEMPERATURE -> entry.date.isBefore(cleanupPolicy.secondaryValidationCutoff)
                        PolarBleApi.PolarDeviceDataType.PPI -> entry.date.isBefore(cleanupPolicy.ppiCutoff)
                        else -> entry.date.isBefore(cleanupPolicy.genericOfflineCutoff)
                    }
                }
                .sortedBy { it.date }
            val removals = removalCandidates.map { entry ->
                val result = runCatching { polarManager.removeOfflineRecord(deviceId, entry) }
                offlineEntrySummary(entry) + mapOf(
                    "removed" to result.isSuccess,
                    "error" to result.exceptionOrNull()?.message
                )
            }
            val diskAfter = polarManager.getDiskSpace(deviceId)
            val failedCount = removals.count { it["removed"] != true }
            val removedBytes = removalCandidates.sumOf { it.size }
            val payload = mapOf(
                "purpose" to "post_sync_storage_maintenance",
                "deviceId" to deviceId,
                "startedAtEpochMs" to startedAt,
                "softUsedPercentThreshold" to SOFT_STORAGE_MAINTENANCE_USED_PERCENT,
                "hardUsedPercentThreshold" to HARD_STORAGE_MAINTENANCE_USED_PERCENT,
                "ppgRetentionHours" to cleanupPolicy.ppgRetentionHours,
                "secondaryValidationRetentionDays" to cleanupPolicy.secondaryValidationRetentionDays,
                "ppiRetentionDays" to cleanupPolicy.ppiRetentionDays,
                "genericOfflineRetentionDays" to cleanupPolicy.genericOfflineRetentionDays,
                "diskBefore" to beforePayload,
                "diskAfter" to diskSpacePayload(diskAfter, System.currentTimeMillis()),
                "ppgCutoff" to cleanupPolicy.ppgCutoff.toString(),
                "secondaryValidationCutoff" to cleanupPolicy.secondaryValidationCutoff.toString(),
                "ppiCutoff" to cleanupPolicy.ppiCutoff.toString(),
                "genericOfflineCutoff" to cleanupPolicy.genericOfflineCutoff.toString(),
                "candidateEntries" to removalCandidates.map(::offlineEntrySummary),
                "removedEntries" to removals,
                "removedEntryCount" to removalCandidates.size,
                "removedBytesAdvertisedByEntries" to removedBytes,
                "failedRemovalCount" to failedCount
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.DAILY_SUMMARY.name,
                    requestedRange = "storage_maintenance",
                    status = if (failedCount == 0) ProbeStatus.SUPPORTED.name else ProbeStatus.PARTIAL.name,
                    recordCount = removalCandidates.size,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = storageMaintenanceSummary(usedPercent, removalCandidates.size, failedCount, removedBytes, diskBefore.freeSpace, diskAfter.freeSpace),
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = if (failedCount == 0) null else "PARTIAL_REMOVE_FAILURE",
                    errorMessage = if (failedCount == 0) null else "$failedCount offline entries could not be removed"
                )
            )
        }.onFailure { error ->
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.DAILY_SUMMARY.name,
                    requestedRange = "storage_maintenance",
                    status = ProbeStatus.ERROR.name,
                    recordCount = 0,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "storage maintenance failed: ${error.message ?: error.javaClass.simpleName}",
                    rawPayloadJson = GsonProvider.gson.toJson(
                        mapOf(
                            "purpose" to "post_sync_storage_maintenance",
                            "deviceId" to deviceId,
                            "startedAtEpochMs" to startedAt,
                            "error" to (error.message ?: error.javaClass.simpleName)
                        )
                    ),
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = error.javaClass.simpleName,
                    errorMessage = error.message
                )
            )
        }
    }

    private suspend fun runPostSyncDeviceHistoryMaintenance(syncRunId: Long, deviceId: String) {
        val startedAt = System.currentTimeMillis()
        val cutoffDate = LocalDate.now(ZoneOffset.UTC).minusDays(DEVICE_STORED_DATA_RETENTION_DAYS)
        runCatching {
            val diskBefore = polarManager.getDiskSpace(deviceId)
            val archivedDates = dao.getArchivedDeviceSourceDatesAtOrBefore(deviceId, cutoffDate.toString())
                .mapNotNull { date -> runCatching { LocalDate.parse(date) }.getOrNull() }
                .distinct()
                .sorted()
            val removals = archivedDates.map { date ->
                val result = runCatching { polarManager.deleteDeviceDateFolders(deviceId, date, date) }
                mapOf(
                    "date" to date.toString(),
                    "removed" to result.isSuccess,
                    "error" to result.exceptionOrNull()?.message
                )
            }
            val failedCount = removals.count { it["removed"] != true }
            val diskAfter = polarManager.getDiskSpace(deviceId)
            val payload = mapOf(
                "purpose" to "post_sync_device_history_maintenance",
                "deviceId" to deviceId,
                "startedAtEpochMs" to startedAt,
                "retentionDays" to DEVICE_STORED_DATA_RETENTION_DAYS,
                "cutoffDate" to cutoffDate.toString(),
                "diskBefore" to diskSpacePayload(diskBefore, startedAt),
                "diskAfter" to diskSpacePayload(diskAfter, System.currentTimeMillis()),
                "archivedDates" to archivedDates.map { it.toString() },
                "removedDates" to removals,
                "removedDateCount" to archivedDates.size,
                "failedRemovalCount" to failedCount
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.DAILY_SUMMARY.name,
                    requestedRange = "device_history_maintenance",
                    status = if (failedCount == 0) ProbeStatus.SUPPORTED.name else ProbeStatus.PARTIAL.name,
                    recordCount = archivedDates.size,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = deviceHistoryMaintenanceSummary(cutoffDate, archivedDates.size, failedCount, diskBefore.freeSpace, diskAfter.freeSpace),
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = if (failedCount == 0) null else "PARTIAL_DEVICE_HISTORY_REMOVE_FAILURE",
                    errorMessage = if (failedCount == 0) null else "$failedCount device date folders could not be removed"
                )
            )
        }.onFailure { error ->
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.DAILY_SUMMARY.name,
                    requestedRange = "device_history_maintenance",
                    status = ProbeStatus.ERROR.name,
                    recordCount = 0,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "device history maintenance failed: ${error.message ?: error.javaClass.simpleName}",
                    rawPayloadJson = GsonProvider.gson.toJson(
                        mapOf(
                            "purpose" to "post_sync_device_history_maintenance",
                            "deviceId" to deviceId,
                            "startedAtEpochMs" to startedAt,
                            "retentionDays" to DEVICE_STORED_DATA_RETENTION_DAYS,
                            "cutoffDate" to cutoffDate.toString(),
                            "error" to (error.message ?: error.javaClass.simpleName)
                        )
                    ),
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = error.javaClass.simpleName,
                    errorMessage = error.message
                )
            )
        }
    }

    private suspend fun <T> runWithinSyncSession(
        deviceId: String,
        sessionLabel: String,
        block: suspend () -> T
    ): T {
        var lastError: Throwable? = null
        repeat(SYNC_NOTIFICATION_START_ATTEMPTS) { attempt ->
            try {
                val started = polarManager.startSyncNotifications(deviceId)
                if (!started) {
                    throw SyncNotificationsNotReadyException()
                }
                return try {
                    block()
                } finally {
                    withTimeoutOrNull(SYNC_NOTIFICATION_STOP_TIMEOUT_MS) {
                        runCatching { polarManager.stopSyncNotifications(deviceId) }
                    }
                }
            } catch (error: Throwable) {
                lastError = error
                val isNotificationStartupRace =
                    error is SyncNotificationsNotReadyException ||
                        error.javaClass.simpleName == "PolarNotificationNotEnabled"
                if (!isNotificationStartupRace || attempt == SYNC_NOTIFICATION_START_ATTEMPTS - 1) {
                    throw error
                }
                delay(SYNC_NOTIFICATION_START_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        throw requireNotNull(lastError)
    }

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
        runCatching { get(key)?.takeUnless { it.isJsonNull }?.asBoolean }.getOrNull()

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
        timeoutMs: Long,
        block: suspend (Pair<LocalDate, LocalDate>) -> DomainPersistenceResult
    ): Throwable? {
        val startedAt = System.currentTimeMillis()
        val requestedRange = "$from..$to"
        try {
            val result = withTimeout(timeoutMs) {
                block(from to to)
            }
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
                    rawPayloadJson = null,
                    manualNotes = null,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = null,
                    errorMessage = null
                )
            )
            return null
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
            return error
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
        val safeRecords = filterSleepRecordsThatShouldReplaceExisting(deviceId, filtered)
        safeRecords.forEach { dao.deleteSleepRecordsForDate(deviceId, it.sourceDate) }
        dao.insertSleepRecords(safeRecords)
    }

    private suspend fun filterSleepRecordsThatShouldReplaceExisting(
        deviceId: String,
        incoming: List<SleepNightRawEntity>
    ): List<SleepNightRawEntity> {
        val sourceDates = incoming.mapNotNull { it.sourceDate }.distinct()
        if (sourceDates.isEmpty()) return incoming

        val existingByDate = dao.getSleepRecordsForDates(deviceId, sourceDates)
            .groupBy { it.sourceDate }
        return incoming.filter { record ->
            val existingRows = existingByDate[record.sourceDate].orEmpty()
            val incomingResolved = record.hasResolvedSleepWindow()
            val existingResolved = existingRows.any { it.hasResolvedSleepWindow() }

            // The SDK can return historical placeholder sleep rows after a day
            // was previously resolved. Never let those stubs erase useful data.
            incomingResolved || !existingResolved
        }
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
        if (records.isEmpty()) return
        database.withTransaction {
            records.forEach { dao.deleteHrRecordsForDate(deviceId, it.sourceDate) }
            dao.insertHrRecords(records)
            val rebuild = rebuildHr247EpochsForDates(deviceId, records.map { it.sourceDate }.distinct())
            if (rebuild.rawSourceDatesWithEpochs.isNotEmpty()) {
                dao.deleteHrRecordsForDates(deviceId, rebuild.rawSourceDatesWithEpochs)
            }
        }
    }

    private suspend fun rebuildHr247EpochsForDates(
        deviceId: String?,
        sourceDates: List<String>
    ): Hr247EpochRebuildResult {
        val normalizedDates = sourceDates.distinct().filter { it.isNotBlank() }
        if (normalizedDates.isEmpty()) return Hr247EpochRebuildResult(epochCount = 0, rawSourceDatesWithEpochs = emptyList())
        val records = dao.getHrRawRecordsForDates(deviceId, normalizedDates)
        val updatedAt = System.currentTimeMillis()
        val rawSourceDatesWithEpochs = mutableListOf<String>()
        val epochs = records
            .groupBy { it.sourceDate }
            .flatMap { (sourceDate, dayRecords) ->
                val samples = dayRecords.flatMap { hr247SamplesFromRaw(it, sourceDate) }.sortedBy { it.timestampEpochMs }
                val dayEpochs = Hr247EpochBuilder.derive(samples, updatedAtEpochMs = updatedAt)
                if (dayEpochs.isNotEmpty()) {
                    rawSourceDatesWithEpochs += sourceDate
                }
                dayEpochs
            }
        dao.deleteHr247EpochsForDates(deviceId, normalizedDates)
        if (epochs.isNotEmpty()) {
            dao.upsertHr247Epochs(epochs)
        }
        return Hr247EpochRebuildResult(
            epochCount = epochs.size,
            rawSourceDatesWithEpochs = rawSourceDatesWithEpochs.distinct()
        )
    }

    suspend fun rebuildHr247EpochTables(pruneRaw: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val records = dao.getAllHrRawRecords()
            val sourceDates = records.map { it.sourceDate }.distinct()
            val rebuild = rebuildHr247EpochsForDates(deviceId = null, sourceDates = sourceDates)
            if (pruneRaw && rebuild.rawSourceDatesWithEpochs.isNotEmpty()) {
                dao.deleteHrRecordsForDates(deviceId = null, sourceDates = rebuild.rawSourceDatesWithEpochs)
            }
            rebuild.epochCount
        }
    }

    private fun hr247SamplesFromRaw(record: Hr247DayRawEntity, sourceDate: String): List<Hr247EpochBuilder.Sample> {
        val root = runCatching { GsonProvider.gson.fromJson(record.rawPayloadJson, JsonObject::class.java) }.getOrNull()
            ?: return emptyList()
        val date = root.stringOrNull("date") ?: sourceDate
        val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return emptyList()
        return root.getAsJsonArray("samples")
            ?.mapNotNull { element ->
                val sample = element.asJsonObjectOrNull() ?: return@mapNotNull null
                val startTime = sample.stringOrNull("startTime") ?: return@mapNotNull null
                val startEpochMs = runCatching {
                    LocalDateTime.of(parsedDate, LocalTime.parse(startTime))
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                }.getOrNull() ?: return@mapNotNull null
                val hrValues = sample.getAsJsonArray("hrSamples")
                    ?.mapNotNull { it.asIntOrNull() }
                    .orEmpty()
                if (hrValues.isEmpty()) return@mapNotNull null
                val averagedHr = hrValues.average().toInt()
                Hr247EpochBuilder.Sample(
                    timestampEpochMs = startEpochMs,
                    deviceId = record.deviceId,
                    hrBpm = averagedHr,
                    triggerType = sample.stringOrNull("triggerType") ?: "unknown"
                )
            }
            .orEmpty()
    }

    private suspend fun persistPpi(deviceId: String, data: List<Polar247PPiSamplesData>, requestedRange: String) {
        val existingKeys = dao.getExistingPpiRecordKeys(deviceId).toHashSet()
        val records = data.mapNotNull {
            val sourceDate = it.date.toString()
            val keySummary = "start=${it.samples.startTime}, samples=${it.samples.ppiValueList.size}, trigger=${it.samples.triggerType}"
            val recordKey = "$sourceDate|$keySummary"
            if (!existingKeys.add(recordKey)) {
                return@mapNotNull null
            }
            Ppi247DayRawEntity(
                deviceId = deviceId,
                sourceDate = sourceDate,
                requestedRange = requestedRange,
                syncTimestampEpochMs = System.currentTimeMillis(),
                keySummary = keySummary,
                rawPayloadJson = PayloadMappers.ppi(it),
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.PARSED.name
            )
        }
        records.forEach { dao.deletePpiRecordsForDateAndKeySummary(deviceId, it.sourceDate, it.keySummary) }
        dao.insertPpiRecords(records)
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
        if (records.isEmpty()) return
        database.withTransaction {
            records.forEach { dao.deleteSkinTemperatureRecordsForDate(deviceId, it.sourceDate) }
            dao.insertSkinTemperatureRecords(records)
            val rebuild = rebuildSkinTemperatureSamplesForDates(records.map { it.sourceDate }.distinct())
            val safeToPrune = rebuild.rawSourceDatesWithDerived + rebuild.emptyRawSourceDates
            if (safeToPrune.isNotEmpty()) {
                dao.deleteSkinTemperatureRecordsForDates(deviceId, safeToPrune)
            }
        }
    }

    private suspend fun rebuildSkinTemperatureSamplesForDates(sourceDates: List<String>): DerivedTableRebuildResult {
        val normalizedDates = sourceDates.distinct().filter { it.isNotBlank() }
        if (normalizedDates.isEmpty()) return DerivedTableRebuildResult(
            rowCount = 0,
            rawSourceDatesWithDerived = emptyList(),
            emptyRawSourceDates = emptyList()
        )
        val records = dao.getSkinTemperatureRawRecordsForDates(normalizedDates)
        val updatedAt = System.currentTimeMillis()
        val rawSourceDatesWithDerived = mutableListOf<String>()
        val emptyRawSourceDates = mutableListOf<String>()
        val samples = records
            .groupBy { it.sourceDate }
            .flatMap { (sourceDate, dayRecords) ->
                val daySamples = dayRecords.flatMap { skinTemperatureSamplesFromRaw(it, updatedAt) }
                if (daySamples.isNotEmpty()) {
                    rawSourceDatesWithDerived += sourceDate
                } else if (dayRecords.all(::skinTemperatureRawIsEmpty)) {
                    emptyRawSourceDates += sourceDate
                }
                daySamples
            }
        if (rawSourceDatesWithDerived.isNotEmpty()) {
            dao.deleteSkinTemperatureSamplesForDates(rawSourceDatesWithDerived)
        }
        if (samples.isNotEmpty()) {
            dao.upsertSkinTemperatureSamples(samples)
        }
        return DerivedTableRebuildResult(
            rowCount = samples.size,
            rawSourceDatesWithDerived = rawSourceDatesWithDerived.distinct(),
            emptyRawSourceDates = emptyRawSourceDates.distinct()
        )
    }

    suspend fun rebuildContextEpochTables(pruneRaw: Boolean = false): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val skinRecords = dao.getAllSkinTemperatureRawRecords()
            val activityRecords = dao.getAllActivitySampleRawRecords()
            val skinRebuild = rebuildSkinTemperatureSamplesForDates(skinRecords.map { it.sourceDate }.distinct())
            val activityRebuild = rebuildActivityEpochsForDates(activityRecords.map { it.sourceDate }.distinct())
            if (pruneRaw) {
                val skinSafeToPrune = skinRebuild.rawSourceDatesWithDerived + skinRebuild.emptyRawSourceDates
                val activitySafeToPrune = activityRebuild.rawSourceDatesWithDerived + activityRebuild.emptyRawSourceDates
                if (skinSafeToPrune.isNotEmpty()) {
                    dao.deleteSkinTemperatureRecordsForDates(deviceId = null, sourceDates = skinSafeToPrune)
                }
                if (activitySafeToPrune.isNotEmpty()) {
                    dao.deleteActivitySampleRecordsForDates(deviceId = null, sourceDates = activitySafeToPrune)
                }
            }
            skinRebuild.rowCount + activityRebuild.rowCount
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

    private fun skinTemperatureRawIsEmpty(record: SkinTemperatureRawEntity): Boolean {
        val root = runCatching { GsonProvider.gson.fromJson(record.rawPayloadJson, JsonObject::class.java) }.getOrNull()
            ?: return false
        val result = root.getAsJsonObject("result") ?: return false
        return result.getAsJsonArray("skinTemperatureList")?.isEmpty == true
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
        if (records.isEmpty()) return
        database.withTransaction {
            records.forEach { dao.deleteActivitySampleRecordsForDate(deviceId, it.sourceDate) }
            dao.insertActivitySampleRecords(records)
            val rebuild = rebuildActivityEpochsForDates(records.map { it.sourceDate }.distinct())
            val safeToPrune = rebuild.rawSourceDatesWithDerived + rebuild.emptyRawSourceDates
            if (safeToPrune.isNotEmpty()) {
                dao.deleteActivitySampleRecordsForDates(deviceId, safeToPrune)
            }
        }
    }

    private suspend fun rebuildActivityEpochsForDates(sourceDates: List<String>): DerivedTableRebuildResult {
        val normalizedDates = sourceDates.distinct().filter { it.isNotBlank() }
        if (normalizedDates.isEmpty()) return DerivedTableRebuildResult(
            rowCount = 0,
            rawSourceDatesWithDerived = emptyList(),
            emptyRawSourceDates = emptyList()
        )
        val records = dao.getActivitySampleRawRecordsForDates(normalizedDates)
        val updatedAt = System.currentTimeMillis()
        val rawSourceDatesWithDerived = mutableListOf<String>()
        val emptyRawSourceDates = mutableListOf<String>()
        val epochs = records
            .groupBy { it.sourceDate }
            .flatMap { (sourceDate, dayRecords) ->
                val dayEpochs = dayRecords.flatMap { activityEpochsFromRaw(it, updatedAt) }
                if (dayEpochs.isNotEmpty()) {
                    rawSourceDatesWithDerived += sourceDate
                } else if (dayRecords.all(::activitySamplesRawIsEmpty)) {
                    emptyRawSourceDates += sourceDate
                }
                dayEpochs
            }
        if (rawSourceDatesWithDerived.isNotEmpty()) {
            dao.deleteActivityEpochsForDates(rawSourceDatesWithDerived)
        }
        if (epochs.isNotEmpty()) {
            dao.upsertActivityEpochs(epochs)
        }
        return DerivedTableRebuildResult(
            rowCount = epochs.size,
            rawSourceDatesWithDerived = rawSourceDatesWithDerived.distinct(),
            emptyRawSourceDates = emptyRawSourceDates.distinct()
        )
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

    private fun activitySamplesRawIsEmpty(record: ActivitySamplesRawEntity): Boolean {
        val root = runCatching { GsonProvider.gson.fromJson(record.rawPayloadJson, JsonObject::class.java) }.getOrNull()
            ?: return false
        return root.getAsJsonArray("polarActivitySamplesDataList")?.isEmpty == true
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

    private fun offlineEntrySummary(entry: PolarOfflineRecordingEntry): Map<String, Any?> =
        mapOf(
            "path" to entry.path,
            "size" to entry.size,
            "date" to entry.date.toString(),
            "type" to entry.type.name
        )

    private fun storageMaintenanceSummary(
        usedPercent: Double?,
        removedCount: Int,
        failedCount: Int,
        removedBytes: Long,
        freeBefore: Long,
        freeAfter: Long
    ): String {
        val used = usedPercent?.let { String.format(java.util.Locale.UK, "%.1f", it) } ?: "unknown"
        return "usedPercent=$used, removed=$removedCount, failed=$failedCount, advertisedBytes=$removedBytes, freeBefore=$freeBefore, freeAfter=$freeAfter"
    }

    private fun deviceHistoryMaintenanceSummary(
        cutoffDate: LocalDate,
        removedCount: Int,
        failedCount: Int,
        freeBefore: Long,
        freeAfter: Long
    ): String =
        "retentionDays=$DEVICE_STORED_DATA_RETENTION_DAYS, cutoffDate=$cutoffDate, removedDates=$removedCount, failed=$failedCount, freeBefore=$freeBefore, freeAfter=$freeAfter"

    private fun storageMaintenancePolicy(usedPercent: Double?): StorageMaintenancePolicy {
        val now = LocalDateTime.now()
        val ppgRetentionHours = PPG_EXPERIMENT_RETENTION_HOURS
        val secondaryValidationRetentionDays = when {
            usedPercent != null && usedPercent >= HARD_STORAGE_MAINTENANCE_USED_PERCENT -> 1L
            usedPercent != null && usedPercent >= SOFT_STORAGE_MAINTENANCE_USED_PERCENT -> 3L
            else -> SECONDARY_VALIDATION_RETENTION_DAYS
        }
        val ppiRetentionDays = when {
            usedPercent != null && usedPercent >= HARD_STORAGE_MAINTENANCE_USED_PERCENT -> 3L
            usedPercent != null && usedPercent >= SOFT_STORAGE_MAINTENANCE_USED_PERCENT -> 7L
            else -> OFFLINE_PPI_RETENTION_DAYS
        }
        val genericOfflineRetentionDays = when {
            usedPercent != null && usedPercent >= HARD_STORAGE_MAINTENANCE_USED_PERCENT -> 3L
            usedPercent != null && usedPercent >= SOFT_STORAGE_MAINTENANCE_USED_PERCENT -> 7L
            else -> GENERIC_OFFLINE_RETENTION_DAYS
        }
        return StorageMaintenancePolicy(
            ppgRetentionHours = ppgRetentionHours,
            secondaryValidationRetentionDays = secondaryValidationRetentionDays,
            ppiRetentionDays = ppiRetentionDays,
            genericOfflineRetentionDays = genericOfflineRetentionDays,
            ppgCutoff = now.minusHours(ppgRetentionHours),
            secondaryValidationCutoff = now.minusDays(secondaryValidationRetentionDays),
            ppiCutoff = now.minusDays(ppiRetentionDays),
            genericOfflineCutoff = now.minusDays(genericOfflineRetentionDays)
        )
    }

    private fun diskSpacePayload(diskSpace: PolarDiskSpaceData, checkedAtEpochMs: Long): Map<String, Any?> {
        val usedSpace = diskSpace.totalSpace - diskSpace.freeSpace
        val usedPercent = if (diskSpace.totalSpace > 0L) {
            usedSpace.toDouble() / diskSpace.totalSpace.toDouble() * 100.0
        } else {
            null
        }
        return mapOf(
            "totalSpaceBytes" to diskSpace.totalSpace,
            "freeSpaceBytes" to diskSpace.freeSpace,
            "usedSpaceBytes" to usedSpace,
            "usedPercent" to usedPercent,
            "checkedAtEpochMs" to checkedAtEpochMs
        )
    }

    private fun selectOfflinePpiEntriesForRun(
        startedAtEpochMs: Long,
        regularEntries: List<PolarOfflineRecordingEntry>,
        splitEntries: List<PolarOfflineRecordingEntry>
    ): List<OfflineRecordingCandidate> {
        val localStartedAt = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(startedAtEpochMs),
            ZoneId.systemDefault()
        ).minusMinutes(15)
        val dateToken = DateTimeFormatter.BASIC_ISO_DATE.format(localStartedAt.toLocalDate())

        fun isCandidate(entry: PolarOfflineRecordingEntry): Boolean =
            entry.type == PolarBleApi.PolarDeviceDataType.PPI &&
                (!entry.date.isBefore(localStartedAt) || entry.path.contains(dateToken))

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
                val sampleSummary = offlineRecordingDataSummary(data)
                offlineEntrySummary(entry) + mapOf(
                    "recordingListKind" to kind,
                    "fetchStatus" to ProbeStatus.SUPPORTED.name,
                    "dataClass" to data.javaClass.simpleName,
                    "sampleCount" to sampleSummary.sampleCount,
                    "samplePreview" to sampleSummary.samplePreview,
                    "samples" to sampleSummary.samples,
                    "notes" to sampleSummary.notes
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
            else -> OfflineRecordingDataSummary(
                sampleCount = 0,
                samplePreview = listOf(data.toString().take(1_000)),
                samples = null,
                notes = mapOf("unsupportedSummaryType" to data.javaClass.simpleName)
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
        ppi247Epochs: List<Ppi247EpochEntity>,
        wakeMarkers: List<WakeMarkerEntity>
    ): MorningReadSnapshot? {
        if (sleepRow == null && nightlyRow == null && ppi247Epochs.isEmpty()) return null

        val expectedSourceDate = LocalDate.now(ZoneId.systemDefault()).toString()
        val todayPpiEpochs = ppi247Epochs.filter { it.sourceDate == expectedSourceDate }
        val latestNightlyJson = nightlyRow?.rawPayloadJson?.let {
            runCatching { GsonProvider.gson.fromJson(it, JsonObject::class.java) }.getOrNull()
        }
        val latestNightlySummary = latestNightlyJson?.getAsJsonObject("summary")
        val latestBaselineReady = latestNightlySummary?.booleanOrNull("baselineReady") ?: false
        if (sleepRow?.sourceDate != expectedSourceDate) {
            val manualWindow = manualSleepWindowForToday(wakeMarkers)
            val ppi247Autonomic = manualWindow?.let {
                summarizePpi247ForSleepWindow(
                    sourceDate = null,
                    sleepStartEpochMs = it.first,
                    sleepEndEpochMs = it.second,
                    epochs = ppi247Epochs
                )
            }
            val hasRawPpi = todayPpiEpochs.isNotEmpty() || ppi247Autonomic != null
            val manualDurationMinutes = manualWindow?.let { ((it.second - it.first) / 60_000L).toInt() }
            val pendingSource = when {
                ppi247Autonomic != null -> "raw_ppi_manual_window_pending_sleep_report"
                hasRawPpi && manualWindow == null -> "raw_ppi_pending_manual_sleep_window"
                hasRawPpi -> "raw_ppi_pending_sleep_window"
                else -> "awaiting_sleep_data"
            }
            val scoreResult = scoreMorningRead(
                durationMinutes = manualDurationMinutes,
                autonomicRmssd = ppi247Autonomic?.averageRmssdMs,
                autonomicSource = pendingSource,
                ppi247Autonomic = ppi247Autonomic,
                cycleCount = null,
                wakePhases = null,
                baselineReady = latestBaselineReady,
                recoveryAvailable = false,
                ansAvailable = false,
                ppiWindowLabel = "manual bed/wake window",
                missingPpiReason = "No usable raw PPI is available yet."
            )
            return MorningReadSnapshot(
                sourceDate = expectedSourceDate,
                status = scoreResult.status,
                confidence = if (scoreResult.status != null) "interim" else "pending",
                overnightAutonomicSource = pendingSource,
                sleepDurationMinutes = manualDurationMinutes,
                nightlyRmssd = ppi247Autonomic?.averageRmssdMs,
                baselineReady = latestBaselineReady,
                recoveryAvailable = false,
                summary = "Interim: waiting for Polar sleep data",
                reasons = listOf(
                    "Today’s resolved sleep window is not available yet.",
                    if (ppi247Autonomic != null) {
                        "A provisional PPI read is available from the manual bed/wake window."
                    } else if (hasRawPpi && manualWindow == null) {
                        "Raw PPI has been fetched, but no manual bedtime marker is available to define the provisional sleep window."
                    } else if (hasRawPpi) {
                        "Raw PPI has been fetched, but the final sleep-window score is waiting for Polar’s sleep report."
                    } else {
                        "The app will keep checking for the completed sleep report."
                    }
                ) + scoreResult.reasons,
                isInterim = true,
                sleepDataReady = false,
                rawPpiGoodEpochCount = ppi247Autonomic?.goodEpochCount,
                rawPpiPoorEpochCount = ppi247Autonomic?.poorEpochCount,
                rawPpiCoverageHours = ppi247Autonomic?.coverageHours
            )
        }

        val sleepJson = sleepRow.rawPayloadJson.let {
            runCatching { GsonProvider.gson.fromJson(it, JsonObject::class.java) }.getOrNull()
        }
        val sleepResult = sleepJson?.getAsJsonObject("result")
        val sleepSummary = sleepResult?.getAsJsonObject("summary")
        val nightlySummary = latestNightlySummary?.takeIf { nightlyRow.sourceDate == expectedSourceDate }

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
            val manualWindow = manualSleepWindowForToday(wakeMarkers)
            val ppi247Autonomic = manualWindow?.let {
                summarizePpi247ForSleepWindow(
                    sourceDate = null,
                    sleepStartEpochMs = it.first,
                    sleepEndEpochMs = it.second,
                    epochs = ppi247Epochs
                )
            }
            val hasRawPpi = todayPpiEpochs.isNotEmpty() || ppi247Autonomic != null
            val manualDurationMinutes = manualWindow?.let { ((it.second - it.first) / 60_000L).toInt() }
            val pendingSource = when {
                ppi247Autonomic != null -> "raw_ppi_manual_window_pending_sleep_report"
                hasRawPpi && manualWindow == null -> "raw_ppi_pending_manual_sleep_window"
                hasRawPpi -> "raw_ppi_pending_sleep_window"
                else -> "awaiting_sleep_data"
            }
            val scoreResult = scoreMorningRead(
                durationMinutes = manualDurationMinutes,
                autonomicRmssd = ppi247Autonomic?.averageRmssdMs,
                autonomicSource = pendingSource,
                ppi247Autonomic = ppi247Autonomic,
                cycleCount = null,
                wakePhases = null,
                baselineReady = latestBaselineReady,
                recoveryAvailable = false,
                ansAvailable = false,
                ppiWindowLabel = "manual bed/wake window",
                missingPpiReason = "No usable raw PPI is available yet."
            )
            return MorningReadSnapshot(
                sourceDate = expectedSourceDate,
                status = scoreResult.status,
                confidence = if (scoreResult.status != null) "interim" else "pending",
                overnightAutonomicSource = pendingSource,
                sleepDurationMinutes = manualDurationMinutes,
                nightlyRmssd = ppi247Autonomic?.averageRmssdMs,
                baselineReady = latestBaselineReady,
                recoveryAvailable = false,
                summary = "Interim: waiting for resolved Polar sleep window",
                reasons = listOf(
                    "Polar has created today’s sleep record, but the resolved start/end times are not available yet.",
                    if (ppi247Autonomic != null) {
                        "A provisional PPI read is available from the manual bed/wake window."
                    } else if (hasRawPpi && manualWindow == null) {
                        "Raw PPI has been fetched, but no manual bedtime marker is available to define the provisional sleep window."
                    } else if (hasRawPpi) {
                        "Raw PPI has been fetched, but the final sleep-window score is waiting for those times."
                    } else {
                        "The app will keep checking for the completed sleep report."
                    }
                ) + scoreResult.reasons,
                isInterim = true,
                sleepDataReady = false,
                rawPpiGoodEpochCount = ppi247Autonomic?.goodEpochCount,
                rawPpiPoorEpochCount = ppi247Autonomic?.poorEpochCount,
                rawPpiCoverageHours = ppi247Autonomic?.coverageHours
            )
        }
        val cycleCount = sleepSummary.intOrNull("cycleCount")
        val wakePhases = sleepSummary
            .get("phaseCounts")
            ?.asJsonObjectOrNull()
            ?.get("AWAKE")
            ?.takeUnless { it.isJsonNull }
            ?.asInt
        val rmssd = nightlySummary?.doubleOrNull("meanNightlyRecoveryRMSSD")
        val baselineReady = nightlySummary?.booleanOrNull("baselineReady") ?: latestBaselineReady
        val recoveryAvailable = nightlySummary?.booleanOrNull("recoveryAvailable") ?: false
        val ansAvailable = nightlySummary?.booleanOrNull("ansAvailable") ?: false
        val ppi247Autonomic = summarizePpi247ForSleepWindow(
            sourceDate = sleepRow.sourceDate,
            sleepStartEpochMs = sleepStartEpochMs,
            sleepEndEpochMs = sleepEndEpochMs,
            epochs = ppi247Epochs
        )
        val autonomicRmssd = ppi247Autonomic?.averageRmssdMs ?: rmssd
        val autonomicSource = when {
            ppi247Autonomic != null -> "ppi247_sleep_window"
            nightlySummary != null -> "nightly_recharge_summary"
            else -> "sleep_context_only"
        }

        val scoreResult = scoreMorningRead(
            durationMinutes = durationMinutes,
            autonomicRmssd = autonomicRmssd,
            autonomicSource = autonomicSource,
            ppi247Autonomic = ppi247Autonomic,
            cycleCount = cycleCount,
            wakePhases = wakePhases,
            baselineReady = baselineReady,
            recoveryAvailable = recoveryAvailable,
            ansAvailable = ansAvailable,
            ppiWindowLabel = "resolved sleep window",
            missingPpiReason = "No usable raw PPI overlapped the resolved sleep window."
        )
        val status = scoreResult.status ?: TrafficLightStatus.UNSTEADY
        val reasons = scoreResult.reasons
        val confidence = when {
            ppi247Autonomic != null && ppi247Autonomic.goodEpochCount >= 48 && baselineReady -> "high"
            ppi247Autonomic != null && ppi247Autonomic.goodEpochCount >= 12 -> "medium"
            nightlyRow == null -> "low"
            !baselineReady || !recoveryAvailable || !ansAvailable -> "medium"
            else -> "high"
        }
        val sourceDate = listOfNotNull(
            sleepRow.sourceDate,
            nightlyRow?.sourceDate,
            sleepSummary.stringOrNull("sleepResultDate"),
            nightlySummary?.stringOrNull("sleepResultDate")
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
            rawPpiGoodEpochCount = ppi247Autonomic?.goodEpochCount,
            rawPpiPoorEpochCount = ppi247Autonomic?.poorEpochCount,
            rawPpiCoverageHours = ppi247Autonomic?.coverageHours
        )
    }

    private fun scoreMorningRead(
        durationMinutes: Int?,
        autonomicRmssd: Double?,
        autonomicSource: String,
        ppi247Autonomic: Ppi247WindowSummary?,
        cycleCount: Int?,
        wakePhases: Int?,
        baselineReady: Boolean,
        recoveryAvailable: Boolean,
        ansAvailable: Boolean,
        ppiWindowLabel: String,
        missingPpiReason: String
    ): MorningScoreResult {
        var score = 0.0
        var scoredInputs = 0
        val reasons = mutableListOf<String>()

        if (durationMinutes == null) {
            reasons += "Sleep duration is not available yet."
        } else {
            scoredInputs += 1
            when {
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
        }

        if (autonomicRmssd == null) {
            reasons += "Overnight autonomic data is unavailable."
        } else {
            scoredInputs += 1
            when {
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
        }

        if (ppi247Autonomic != null) {
            reasons += "24/7 PPI covered ${formatHours(ppi247Autonomic.coverageHours)} of the $ppiWindowLabel (${ppi247Autonomic.goodEpochCount} good epochs)."
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
        } else {
            reasons += missingPpiReason
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
        if (ppi247Autonomic == null && (!recoveryAvailable || !ansAvailable)) {
            score -= 0.15
            reasons += "Polar's higher-level overnight interpretation is still immature."
        } else if (ppi247Autonomic != null && (!recoveryAvailable || !ansAvailable)) {
            reasons += "Nightly Recharge interpretation is immature, but raw PPI is available."
        }

        val status = if (scoredInputs == 0) {
            null
        } else {
            when {
                score >= 1.5 -> TrafficLightStatus.GOOD
                score >= 0.0 -> TrafficLightStatus.OK
                score >= -1.25 -> TrafficLightStatus.UNSTEADY
                else -> TrafficLightStatus.CRASH
            }
        }
        return MorningScoreResult(status = status, reasons = reasons)
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

    private fun manualSleepWindowForToday(wakeMarkers: List<WakeMarkerEntity>): Pair<Long, Long>? {
        val now = System.currentTimeMillis()
        val earliestUsefulMarker = LocalDate.now(ZoneId.systemDefault())
            .minusDays(1)
            .atTime(12, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val markers = wakeMarkers
            .asSequence()
            .filter { it.markerEpochMs >= earliestUsefulMarker }
            .filterNot { it.notes == "manual awake command" }
            .sortedByDescending { it.markerEpochMs }
            .toList()
        val bed = markers.firstOrNull { it.markerSource == "manual_going_to_bed" } ?: return null
        val awake = markers
            .firstOrNull { it.markerSource == "manual_im_awake" && it.markerEpochMs > bed.markerEpochMs }
            ?.markerEpochMs
            ?: now
        if (awake <= bed.markerEpochMs) return null
        return bed.markerEpochMs to awake
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
            "raw_ppi_manual_window_pending_sleep_report" -> "Manual-window PPI"
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
    val shapeNotes: String
)

private data class SyncDomainTask(
    val domain: ProbeDomain,
    val from: LocalDate,
    val to: LocalDate,
    val timeoutMs: Long,
    val block: suspend (Pair<LocalDate, LocalDate>) -> DomainPersistenceResult
)

private data class SyncDomainFailure(
    val domain: ProbeDomain,
    val error: Throwable
)

private const val SOFT_STORAGE_MAINTENANCE_USED_PERCENT = 70.0
private const val HARD_STORAGE_MAINTENANCE_USED_PERCENT = 85.0
private const val PPG_EXPERIMENT_RETENTION_HOURS = 6L
private const val SECONDARY_VALIDATION_RETENTION_DAYS = 14L
private const val OFFLINE_PPI_RETENTION_DAYS = 14L
private const val GENERIC_OFFLINE_RETENTION_DAYS = 14L
private const val DEVICE_STORED_DATA_RETENTION_DAYS = 14L
private const val STALE_RUNNING_SYNC_AFTER_MS = 15 * 60 * 1000L
private const val MANUAL_SYNC_TIMEOUT_MS = 7 * 60 * 1000L
private const val SYNC_NOTIFICATION_START_ATTEMPTS = 5
private const val SYNC_NOTIFICATION_START_RETRY_DELAY_MS = 1_500L
private const val SYNC_NOTIFICATION_STOP_TIMEOUT_MS = 5_000L
private const val DATABASE_COMPACTION_THRESHOLD_BYTES = 300L * 1024L * 1024L
private const val ENABLE_ACTIVITY_SAMPLE_SYNC = false
private const val SLEEP_SYNC_TIMEOUT_MS = 45_000L
private const val NIGHTLY_RECHARGE_SYNC_TIMEOUT_MS = 45_000L
private const val PPI_SYNC_TIMEOUT_MS = 4 * 60 * 1000L
private const val HR_SYNC_TIMEOUT_MS = 2 * 60 * 1000L
private const val SKIN_TEMPERATURE_SYNC_TIMEOUT_MS = 60_000L
private const val DAILY_SUMMARY_SYNC_TIMEOUT_MS = 60_000L
private const val ACTIVITY_SAMPLE_SYNC_TIMEOUT_MS = 2 * 60 * 1000L

private class SyncNotificationsNotReadyException :
    IllegalStateException("Sync notifications not enabled")

private data class MorningScoreResult(
    val status: TrafficLightStatus?,
    val reasons: List<String>
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

private data class Hr247EpochRebuildResult(
    val epochCount: Int,
    val rawSourceDatesWithEpochs: List<String>
)

private data class DerivedTableRebuildResult(
    val rowCount: Int,
    val rawSourceDatesWithDerived: List<String>,
    val emptyRawSourceDates: List<String>
)

private data class StorageMaintenancePolicy(
    val ppgRetentionHours: Long,
    val secondaryValidationRetentionDays: Long,
    val ppiRetentionDays: Long,
    val genericOfflineRetentionDays: Long,
    val ppgCutoff: LocalDateTime,
    val secondaryValidationCutoff: LocalDateTime,
    val ppiCutoff: LocalDateTime,
    val genericOfflineCutoff: LocalDateTime
)

private data class OfflineRecordingCandidate(
    val kind: String,
    val entry: PolarOfflineRecordingEntry
)

private data class OfflineRecordingDataSummary(
    val sampleCount: Int,
    val samplePreview: List<Any?>,
    val samples: List<Any?>?,
    val notes: Map<String, Any?>
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
