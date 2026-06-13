package com.daveharris.healthmonitor.data

import android.content.Context
import androidx.room.withTransaction
import com.daveharris.healthmonitor.wakeTargetDateForMarker
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
import fi.polar.remote.representation.protobuf.AutomaticSamples.PbAutomaticSampleSessions
import fi.polar.remote.representation.protobuf.Types.PbDate
import fi.polar.remote.representation.protobuf.Types.PbTime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.sqrt

class ProbeRepository(
    private val database: AppDatabase,
    private val polarManager: PolarProbeManager
) {
    private val dao = database.probeDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val predictionSnapshotMutex = Mutex()

    val runtimeState = polarManager.runtimeState
    val deviceProfile = dao.observeLatestDeviceProfile()
    val ftuProfile = dao.observeLatestFtuProfile()
    val observedCapabilities = dao.observeObservedCapabilities()
    val syncRuns = dao.observeSyncRuns()
    val syncDomainResults = dao.observeSyncDomainResults()
    val inspectorRows = dao.observeInspectorRows()
    val appSettings = dao.observeAppSettings()
    val morningPredictionSnapshots = dao.observeMorningPredictionSnapshots()
    val recentSleepEpisodes = dao.observeRecentSleepEpisodes()
    val morningRead = combine(
        dao.observeLatestSleepRecord(),
        dao.observeLatestNightlyRechargeRecord(),
        dao.observeRecentPpi247Epochs(),
        dao.observeRecentWakeMarkers(),
        dao.observeRecentSleepEpisodes()
    ) { sleep, nightly, ppi247Epochs, wakeMarkers, sleepEpisodes ->
        val modelRead = runCatching { deriveCurrentState() }.getOrNull()
        deriveMorningRead(sleep, nightly, ppi247Epochs, wakeMarkers, sleepEpisodes, currentState = modelRead)
    }
    val recentWakeMarkers = dao.observeRecentWakeMarkers()
    val recentPpi247Epochs = dao.observeRecentPpi247Epochs()

    /**
     * Lodestone model-v1 read (persistence spine + caution + capped confidence).
     * Recomputed when lived outcomes or autonomic data change. This is the honest
     * forecast that supersedes the legacy sleep-recovery score; the legacy
     * [morningRead] flow now only carries sleep/PPI provenance for Signals.
     */
    val currentState: Flow<CurrentStateRead?> = combine(
        dao.observeDailyCheckIns(),
        dao.observeRecentPpi247Epochs()
    ) { _, _ -> runCatching { deriveCurrentState() }.getOrNull() }

    init {
        scope.launch {
            recoverStaleRunningSyncRuns()
            rebuildHr247EpochTables(pruneRaw = true)
            rebuildContextEpochTables(pruneRaw = true)
            pruneOversizedSyncResultPayloads()
            dao.pruneDuplicateMorningPredictionSnapshots()
            backfillMorningPredictionSnapshots()
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
        lastKnownFirmwareBySelectedDevice: String?,
        markerMode: String = "BEDTIME_AND_WAKING",
        journalFocusMode: String = "AUTO_FROM_WAKE",
        journalFocusFixedTimeMinutes: Int = 18 * 60
    ) {
        val normalizedConfig = syncWindowConfig.normalized()
        dao.upsertAppSettings(
            AppSettingsEntity(
                selectedDeviceId = selectedDeviceId,
                sleepDays = normalizedConfig.sleepDays,
                nightlyRechargeDays = normalizedConfig.nightlyRechargeDays,
                hrDays = normalizedConfig.hrDays,
                ppiDays = normalizedConfig.ppiDays,
                markerMode = markerMode,
                journalFocusMode = journalFocusMode,
                journalFocusFixedTimeMinutes = journalFocusFixedTimeMinutes.coerceIn(0, 23 * 60 + 59),
                lastKnownFirmwareBySelectedDevice = lastKnownFirmwareBySelectedDevice
            )
        )
    }

    suspend fun getAppSettings(): AppSettingsEntity? = dao.getAppSettings()

    suspend fun hasSleepRecordForDate(sourceDate: String): Boolean =
        dao.getLatestSleepRecordForDate(sourceDate)?.hasResolvedSleepWindow() == true

    suspend fun hasPpiRecordForDate(sourceDate: String): Boolean =
        dao.countPpi247EpochsForDate(sourceDate) > 0

    suspend fun hasMorningPpiSignalForDate(sourceDate: String): Boolean =
        deriveMorningReadForDate(sourceDate, allowProvisional = true).hasUsableMorningPpiSignal()

    suspend fun getSleepEpisodesForDate(sourceDate: String): List<SleepEpisodeEntity> =
        dao.getSleepEpisodesForDate(sourceDate)

    fun observeSleepEpisodesForDate(sourceDate: String): Flow<List<SleepEpisodeEntity>> =
        dao.observeSleepEpisodesForDate(sourceDate)

    suspend fun getPrimarySleepEpisodeForDate(sourceDate: String): SleepEpisodeEntity? =
        dao.getPrimarySleepEpisodeForDate(sourceDate)

    suspend fun insertSleepEpisode(entity: SleepEpisodeEntity): Long =
        dao.insertSleepEpisode(entity)

    suspend fun updateSleepEpisode(entity: SleepEpisodeEntity) =
        dao.updateSleepEpisode(entity)

    suspend fun deleteSleepEpisode(id: Long) =
        dao.deleteSleepEpisode(id)

    suspend fun confirmSleepEpisode(
        id: Long,
        episodeKind: String,
        source: String,
        isPrimaryForReadiness: Boolean,
        notes: String? = null
    ): Boolean {
        val existing = dao.getSleepEpisodeById(id) ?: return false
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (isPrimaryForReadiness) {
                dao.clearPrimarySleepEpisodeForDate(existing.sourceDate, now)
            }
            dao.updateSleepEpisode(
                existing.copy(
                    episodeKind = episodeKind,
                    source = source,
                    confidence = SleepEpisodeConfidences.USER_CONFIRMED,
                    isPrimaryForReadiness = isPrimaryForReadiness,
                    notes = notes ?: existing.notes,
                    updatedAtEpochMs = now
                )
            )
        }
        return true
    }

    suspend fun editSleepEpisodeWindow(
        id: Long,
        startEpochMs: Long,
        endEpochMs: Long,
        notes: String? = null
    ): Boolean {
        val existing = dao.getSleepEpisodeById(id) ?: return false
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.updateSleepEpisode(
                existing.copy(
                    startEpochMs = startEpochMs,
                    endEpochMs = endEpochMs,
                    source = SleepEpisodeSources.EDITED,
                    confidence = SleepEpisodeConfidences.USER_CONFIRMED,
                    notes = notes ?: existing.notes,
                    updatedAtEpochMs = now
                )
            )
        }
        return true
    }

    suspend fun addManualSleepWindow(
        sourceDate: String,
        startEpochMs: Long,
        endEpochMs: Long
    ): Long {
        require(endEpochMs > startEpochMs) {
            "Manual sleep window end must be after start"
        }
        val now = System.currentTimeMillis()
        val evidenceJson = GsonProvider.gson.toJson(
            mapOf(
                "userDecision" to "manual_sleep_window",
                "durationMinutes" to ((endEpochMs - startEpochMs) / 60_000L).coerceAtLeast(0L)
            )
        )
        var rowId = 0L
        database.withTransaction {
            dao.clearPrimarySleepEpisodeForDate(sourceDate, now)
            dao.deleteSleepEpisodesForDateAndKind(
                sourceDate = sourceDate,
                episodeKind = SleepEpisodeKinds.NO_SLEEP
            )
            dao.deleteUnconfirmedSleepEpisodeCandidatesForDateAndKind(
                sourceDate = sourceDate,
                source = SleepEpisodeSources.PPI_INFERRED,
                episodeKind = SleepEpisodeKinds.MAIN_SLEEP,
                confirmedConfidence = SleepEpisodeConfidences.USER_CONFIRMED
            )
            rowId = dao.insertSleepEpisode(
                SleepEpisodeEntity(
                    sourceDate = sourceDate,
                    startEpochMs = startEpochMs,
                    endEpochMs = endEpochMs,
                    episodeKind = SleepEpisodeKinds.MAIN_SLEEP,
                    source = SleepEpisodeSources.MANUAL,
                    confidence = SleepEpisodeConfidences.USER_CONFIRMED,
                    isPrimaryForReadiness = true,
                    deviceId = null,
                    linkedSleepRawId = null,
                    evidenceJson = evidenceJson,
                    notes = "Added manual sleep window",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
            )
        }
        return rowId
    }

    suspend fun markNoMainSleep(sourceDate: String): Long {
        val now = System.currentTimeMillis()
        val evidenceJson = GsonProvider.gson.toJson(
            mapOf(
                "userDecision" to "no_main_sleep",
                "candidateOnly" to false
            )
        )
        var rowId = 0L
        database.withTransaction {
            dao.clearPrimarySleepEpisodeForDate(sourceDate, now)
            dao.deleteUnconfirmedSleepEpisodeCandidatesForDateAndKind(
                sourceDate = sourceDate,
                source = SleepEpisodeSources.PPI_INFERRED,
                episodeKind = SleepEpisodeKinds.MAIN_SLEEP,
                confirmedConfidence = SleepEpisodeConfidences.USER_CONFIRMED
            )
            val existing = dao.getLatestSleepEpisodeForDateAndKind(sourceDate, SleepEpisodeKinds.NO_SLEEP)
            if (existing == null) {
                rowId = dao.insertSleepEpisode(
                    SleepEpisodeEntity(
                        sourceDate = sourceDate,
                        startEpochMs = null,
                        endEpochMs = null,
                        episodeKind = SleepEpisodeKinds.NO_SLEEP,
                        source = SleepEpisodeSources.MANUAL,
                        confidence = SleepEpisodeConfidences.USER_CONFIRMED,
                        isPrimaryForReadiness = false,
                        deviceId = null,
                        linkedSleepRawId = null,
                        evidenceJson = evidenceJson,
                        notes = "No main sleep for this day",
                        createdAtEpochMs = now,
                        updatedAtEpochMs = now
                    )
                )
            } else {
                rowId = existing.id
                dao.updateSleepEpisode(
                    existing.copy(
                        startEpochMs = null,
                        endEpochMs = null,
                        source = SleepEpisodeSources.MANUAL,
                        confidence = SleepEpisodeConfidences.USER_CONFIRMED,
                        isPrimaryForReadiness = false,
                        evidenceJson = existing.evidenceJson ?: evidenceJson,
                        notes = existing.notes ?: "No main sleep for this day",
                        updatedAtEpochMs = now
                    )
                )
            }
        }
        return rowId
    }

    suspend fun inferSleepEpisodeCandidatesForDate(sourceDate: String): List<SleepEpisodeEntity> {
        val bounds = sleepSearchBoundsForDate(sourceDate)
        val targetDate = runCatching { LocalDate.parse(sourceDate) }
            .getOrDefault(LocalDate.now(ZoneId.systemDefault()))
        val sourceDates = listOf(targetDate.minusDays(1).toString(), targetDate.toString())
        val markers = dao.getWakeMarkersBetween(bounds.startEpochMs, bounds.endEpochMs)
        val epochs = dao.getPpi247EpochsForDates(sourceDates)
        val now = System.currentTimeMillis()
        return sleepWindowEstimatesForDate(
            sourceDate = sourceDate,
            wakeMarkers = markers,
            ppi247Epochs = epochs,
            includeRestCandidates = true
        ).map { estimate ->
            estimate.toSleepEpisodeCandidate(
                sourceDate = sourceDate,
                nowEpochMs = now
            )
        }
    }

    suspend fun refreshInferredSleepEpisodeCandidatesForDate(sourceDate: String): Int {
        val candidates = inferSleepEpisodeCandidatesForDate(sourceDate)
        var insertedCount = 0
        database.withTransaction {
            dao.deleteUnconfirmedSleepEpisodeCandidatesForDate(
                sourceDate = sourceDate,
                source = SleepEpisodeSources.PPI_INFERRED,
                confirmedConfidence = SleepEpisodeConfidences.USER_CONFIRMED
            )
            val confirmedOrEditedEpisodes = dao.getSleepEpisodesForDate(sourceDate)
            val insertableCandidates = candidates.filterNot { candidate ->
                confirmedOrEditedEpisodes.any { it.blocksInferredCandidate(candidate) }
            }
            if (insertableCandidates.isNotEmpty()) {
                dao.upsertSleepEpisodes(insertableCandidates)
                insertedCount = insertableCandidates.size
            }
        }
        return insertedCount
    }

    /**
     * Model-v1 feature extraction + rule. Pure model in [CurrentStateModel]; this
     * method owns the I/O. See docs/lodestone-model-v1.md §Inputs.
     */
    suspend fun deriveCurrentState(
        today: String = LocalDate.now(ZoneId.systemDefault()).toString()
    ): CurrentStateRead {
        val todayDate = runCatching { LocalDate.parse(today) }.getOrNull()

        // --- Persistence spine: recent lived outcomes, newest first ---
        val checkIns = dao.getRecentDailyCheckIns(CURRENT_STATE_RECENT_OUTCOME_LOOKBACK_DAYS)
            .sortedByDescending { it.sourceDate }
        val recentOutcomes = checkIns.mapNotNull { CurrentStateFeatures.outcomeLevel(it.eveningOutcome) }
        val latestCheckIn = checkIns.firstOrNull { CurrentStateFeatures.outcomeLevel(it.eveningOutcome) != null }
        val latestOutcome = latestCheckIn?.let { CurrentStateFeatures.outcomeLevel(it.eveningOutcome) }
        val latestOutcomeDate = latestCheckIn?.sourceDate
        val outcomeAgeDays = CurrentStateFeatures.daysBetween(latestOutcomeDate, today)

        // --- Exertion: moderate/vigorous active minutes on D-1 / D-2 ---
        val d1 = todayDate?.minusDays(1)?.toString()
        val d2 = todayDate?.minusDays(2)?.toString()
        val recentSummaries = dao.getDailySummariesForDates(listOfNotNull(d1, d2)).groupBy { it.sourceDate }
        fun mvFor(date: String?): Int? = date?.let { d ->
            recentSummaries[d]?.firstNotNullOfOrNull { CurrentStateFeatures.mvActiveMinutes(it.rawPayloadJson) }
        }
        val mvD1 = mvFor(d1)
        val mvD2 = mvFor(d2)

        // --- 24h HRV CV (today, whole-day good epochs; no sleep window) ---
        val todayEpochs = dao.getPpi247EpochsForDate(today)
        val goodRmssdToday = todayEpochs.filter { it.epochQuality == "good" && it.rmssdMs != null }.map { it.rmssdMs!! }
        val hrvCv24h = CurrentStateFeatures.hrvCv24h(goodRmssdToday)

        // --- Adaptive personal thresholds over a trailing window ---
        val trailingDates = if (todayDate != null) {
            (1..CurrentStateFeatures.ADAPTIVE_TRAILING_DAYS).map { todayDate.minusDays(it.toLong()).toString() }
        } else emptyList()
        val trailingExertion = dao.getDailySummariesForDates(trailingDates)
            .groupBy { it.sourceDate }
            .values
            .mapNotNull { rows -> rows.firstNotNullOfOrNull { CurrentStateFeatures.mvActiveMinutes(it.rawPayloadJson) } }
            .map { it.toDouble() }
        val trailingCv = dao.getPpi247EpochsForDates(trailingDates)
            .filter { it.epochQuality == "good" && it.rmssdMs != null }
            .groupBy { it.sourceDate }
            .values
            .mapNotNull { epochs -> CurrentStateFeatures.hrvCv24h(epochs.map { it.rmssdMs!! }) }
        val exertionThreshold = CurrentStateModel.adaptiveUpperTier(
            trailingExertion, CurrentStateFeatures.PROVISIONAL_MV_MINUTES_THRESHOLD
        )?.toInt()
        val cvThreshold = CurrentStateModel.adaptiveUpperTier(
            trailingCv, CurrentStateFeatures.PROVISIONAL_HRV_CV_THRESHOLD
        )

        // --- Coverage for confidence ---
        val goodEpochCount = goodRmssdToday.size
        val coverageHours = todayEpochs
            .filter { it.epochQuality == "good" }
            .sumOf { (it.epochEndEpochMs - it.epochStartEpochMs).coerceAtLeast(0L) } / 3_600_000.0
        // Proxy for "we have enough personal autonomic history to have a baseline".
        val baselineReady = trailingCv.size >= CurrentStateModel.MIN_HISTORY_FOR_ADAPTIVE
        val lastSyncAgeHours = todayEpochs.maxOfOrNull { it.updatedAtEpochMs }
            ?.let { (System.currentTimeMillis() - it) / 3_600_000.0 }

        val coverage = DataCoverage(
            hasRecentOutcome = latestOutcome != null &&
                (outcomeAgeDays == null || outcomeAgeDays <= CURRENT_STATE_RECENT_OUTCOME_LOOKBACK_DAYS),
            outcomeAgeDays = outcomeAgeDays,
            hasAutonomic = goodEpochCount >= CurrentStateFeatures.MIN_GOOD_EPOCHS_FOR_CV,
            ppiCoverageHours = if (todayEpochs.isEmpty()) null else coverageHours,
            baselineReady = baselineReady,
            lastSyncAgeHours = lastSyncAgeHours
        )

        return CurrentStateModel.deriveCurrentStateRead(
            CurrentStateInputs(
                today = today,
                latestOutcome = latestOutcome,
                latestOutcomeDate = latestOutcomeDate,
                recentOutcomes = recentOutcomes,
                exertionMvMinutesD1 = mvD1,
                exertionMvMinutesD2 = mvD2,
                exertionThresholdMvMinutes = exertionThreshold,
                hrvCv24h = hrvCv24h,
                hrvCvThreshold = cvThreshold,
                coverage = coverage
            )
        )
    }

    suspend fun recordCurrentStateSnapshot(
        read: CurrentStateRead,
        snapshotOrigin: String = CURRENT_STATE_ORIGIN_OBSERVED
    ) {
        predictionSnapshotMutex.withLock {
            val entity = read.toCurrentStateSnapshotEntity(
                snapshotOrigin = snapshotOrigin,
                issuedAtEpochMs = System.currentTimeMillis()
            )
            val latest = dao.getLatestCurrentStateSnapshot(read.sourceDate, snapshotOrigin)
            if (latest?.isSameRead(entity) == true) return@withLock
            dao.insertCurrentStateSnapshot(entity)
        }
    }

    suspend fun recordMorningPredictionSnapshot(
        snapshot: MorningReadSnapshot,
        snapshotOrigin: String = MORNING_PREDICTION_ORIGIN_OBSERVED
    ) {
        val sourceDate = snapshot.sourceDate ?: return
        if (snapshot.status == null) return
        predictionSnapshotMutex.withLock {
            val entity = snapshot.toMorningPredictionSnapshotEntity(
                snapshotOrigin = snapshotOrigin,
                issuedAtEpochMs = System.currentTimeMillis()
            )
            val latest = dao.getLatestMorningPredictionSnapshot(sourceDate, snapshotOrigin)
            if (latest?.isSamePrediction(entity) == true) return@withLock
            dao.insertMorningPredictionSnapshot(entity)
            dao.pruneDuplicateMorningPredictionSnapshots()
        }
    }

    private suspend fun backfillMorningPredictionSnapshots() {
        val today = LocalDate.now(ZoneId.systemDefault()).toString()
        dao.getMorningPredictionBackfillCandidateDates()
            .asSequence()
            .filter { it <= today }
            .forEach { sourceDate ->
                if (
                    dao.countMorningPredictionSnapshots(
                        sourceDate,
                        MORNING_PREDICTION_ORIGIN_BACKFILLED,
                        MORNING_MODEL_VERSION
                    ) > 0
                ) {
                    return@forEach
                }
                val snapshot = deriveMorningReadForDate(sourceDate, allowProvisional = false) ?: return@forEach
                recordMorningPredictionSnapshot(snapshot, MORNING_PREDICTION_ORIGIN_BACKFILLED)
            }
    }

    private suspend fun deriveMorningReadForDate(
        sourceDate: String,
        allowProvisional: Boolean
    ): MorningReadSnapshot? {
        val date = runCatching { LocalDate.parse(sourceDate) }.getOrNull() ?: return null
        val sleep = dao.getLatestSleepRecordForDate(sourceDate)
        val nightly = dao.getLatestNightlyRechargeRecordForDate(sourceDate)
        val ppiSourceDates = listOf(date.minusDays(1).toString(), sourceDate)
        val ppiEpochs = dao.getPpi247EpochsForDates(ppiSourceDates)
        val sleepEpisodes = dao.getSleepEpisodesForDate(sourceDate)
        val markerStart = date
            .minusDays(1)
            .atTime(LocalTime.NOON)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val markerEnd = date
            .plusDays(1)
            .atTime(LocalTime.NOON)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val wakeMarkers = dao.getWakeMarkersBetween(markerStart, markerEnd)
        return deriveMorningRead(
            sleepRow = sleep,
            nightlyRow = nightly,
            ppi247Epochs = ppiEpochs,
            wakeMarkers = wakeMarkers,
            sleepEpisodes = sleepEpisodes,
            expectedSourceDate = sourceDate,
            allowProvisional = allowProvisional,
            currentState = runCatching { deriveCurrentState(sourceDate) }.getOrNull()
        )
    }

    suspend fun recordWakeMarker(
        sourceDate: String,
        markerEpochMs: Long = System.currentTimeMillis(),
        markerSource: String = WakeMarkerSources.IM_AWAKE,
        deviceId: String?,
        notes: String? = null,
        dedupeWindowMs: Long = 15 * 60 * 1000L
    ): Long {
        val markerSourceDate = if (markerSource == WakeMarkerSources.IM_AWAKE) {
            wakeTargetDateForMarker(markerEpochMs).toString()
        } else {
            sourceDate
        }
        val latest = dao.getLatestWakeMarker(markerSourceDate, markerSource)
        if (latest != null && kotlin.math.abs(markerEpochMs - latest.markerEpochMs) <= dedupeWindowMs) {
            return latest.id
        }
        return dao.insertWakeMarker(
            WakeMarkerEntity(
                sourceDate = markerSourceDate,
                markerEpochMs = markerEpochMs,
                markerSource = markerSource,
                deviceId = deviceId,
                notes = notes
            )
        )
    }

    suspend fun updateWakeMarkerTime(id: Long, sourceDate: String, markerEpochMs: Long): Boolean =
        dao.updateWakeMarkerTime(id = id, sourceDate = sourceDate, markerEpochMs = markerEpochMs) > 0

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
                        val coverage = loadDomainCoverage(deviceId)
                        buildSyncTasks(deviceId, normalizedConfig, profile, now, coverage).forEach { task ->
                            syncDomain(
                                syncRunId = syncRunId,
                                deviceId = deviceId,
                                domain = task.domain,
                                from = task.from,
                                to = task.to,
                                timeoutMs = task.timeoutMs,
                                rangeNotes = task.rangeNotes,
                                block = task.block
                            )?.let { error ->
                                domainFailures += SyncDomainFailure(task.domain, error)
                            }
                        }
                    }
                }

                withContext(NonCancellable) {
                    dao.pruneLargeSyncDomainResultPayloads()
                    pruneRawPpiBuffer(syncRunId, deviceId)
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

    private suspend fun loadDomainCoverage(deviceId: String): SyncDomainCoverage =
        SyncDomainCoverage(
            sleepLatest = dao.getLatestSleepSourceDate(deviceId),
            nightlyRechargeLatest = dao.getLatestNightlyRechargeSourceDate(deviceId),
            ppiLatest = dao.getLatestPpiEpochSourceDate(deviceId),
            hrLatest = dao.getLatestHrEpochSourceDate(deviceId),
            skinTemperatureLatest = dao.getLatestSkinTemperatureSourceDate(deviceId),
            dailySummaryLatest = dao.getLatestDailySummarySourceDate(deviceId),
            activitySamplesLatest = dao.getLatestActivitySamplesSourceDate(deviceId)
        )

    private fun buildSyncTasks(
        deviceId: String,
        config: SyncWindowConfig,
        profile: SyncRunProfile,
        now: LocalDate,
        coverage: SyncDomainCoverage
    ): List<SyncDomainTask> = buildList {
        fun plannedRange(
            maxLookbackDays: Int,
            latestStoredDate: String?,
            overlapDays: Int = SyncRangePlanner.DEFAULT_OVERLAP_DAYS
        ): SyncRangePlanner.PlannedRange =
            SyncRangePlanner.planRange(
                today = now,
                maxLookbackDays = maxLookbackDays,
                latestStoredDate = latestStoredDate,
                overlapDays = overlapDays
            )

        fun addDomainTask(
            domain: ProbeDomain,
            range: SyncRangePlanner.PlannedRange,
            timeoutMs: Long,
            block: suspend (Pair<LocalDate, LocalDate>) -> DomainPersistenceResult
        ) {
            if (range.isEmpty()) return
            add(
                SyncDomainTask(
                    domain = domain,
                    from = range.from,
                    to = range.to,
                    timeoutMs = timeoutMs,
                    rangeNotes = syncRangeNotes(range),
                    block = block
                )
            )
        }

        fun addSleepTask(maxLookbackDays: Int = config.sleepDays) {
            addDomainTask(
                domain = ProbeDomain.SLEEP,
                range = plannedRange(
                    maxLookbackDays = maxLookbackDays,
                    latestStoredDate = coverage.sleepLatest,
                    overlapDays = SyncRangePlanner.SLEEP_OVERLAP_DAYS
                ),
                timeoutMs = SLEEP_SYNC_TIMEOUT_MS
            ) {
                val data = polarManager.fetchSleep(deviceId, it.first, it.second)
                persistSleep(deviceId, data, "${it.first}..${it.second}")
                DomainPersistenceResult(data.size, shapeForSleep(data))
            }
        }

        fun addNightlyRechargeTask(maxLookbackDays: Int = config.nightlyRechargeDays) {
            addDomainTask(
                domain = ProbeDomain.NIGHTLY_RECHARGE,
                range = plannedRange(
                    maxLookbackDays = maxLookbackDays,
                    latestStoredDate = coverage.nightlyRechargeLatest,
                    overlapDays = SyncRangePlanner.SLEEP_OVERLAP_DAYS
                ),
                timeoutMs = NIGHTLY_RECHARGE_SYNC_TIMEOUT_MS
            ) {
                val data = polarManager.fetchNightlyRecharge(deviceId, it.first, it.second)
                persistNightlyRecharge(deviceId, data, "${it.first}..${it.second}")
                DomainPersistenceResult(data.size, shapeForNightlyRecharge(data))
            }
        }

        fun addPpiTask() {
            // PPI is the most valuable morning trajectory lane, so it runs before
            // less critical background HR. A flaky HR fetch must not block PPI.
            addDomainTask(
                domain = ProbeDomain.PPI_247,
                range = plannedRange(
                    maxLookbackDays = config.ppiDays,
                    latestStoredDate = coverage.ppiLatest
                ),
                timeoutMs = PPI_SYNC_TIMEOUT_MS
            ) {
                val data = polarManager.fetch247Ppi(deviceId, it.first, it.second)
                persistPpi(deviceId, data, "${it.first}..${it.second}")
                DomainPersistenceResult(data.size, shapeForPpi(data))
            }
        }

        fun addHrTask() {
            addDomainTask(
                domain = ProbeDomain.HR_247,
                range = plannedRange(
                    maxLookbackDays = config.hrDays,
                    latestStoredDate = coverage.hrLatest
                ),
                timeoutMs = HR_SYNC_TIMEOUT_MS
            ) {
                val data = polarManager.fetch247Hr(deviceId, it.first, it.second)
                persistHr(deviceId, data, "${it.first}..${it.second}")
                DomainPersistenceResult(data.size, shapeForHr(data))
            }
        }

        fun addSkinTemperatureTask() {
            addDomainTask(
                domain = ProbeDomain.SKIN_TEMPERATURE,
                range = plannedRange(
                    maxLookbackDays = config.hrDays,
                    latestStoredDate = coverage.skinTemperatureLatest
                ),
                timeoutMs = SKIN_TEMPERATURE_SYNC_TIMEOUT_MS
            ) {
                val data = polarManager.fetchSkinTemperature(deviceId, it.first, it.second)
                persistSkinTemperature(deviceId, data, "${it.first}..${it.second}")
                DomainPersistenceResult(data.size, shapeForSkinTemperature(data))
            }
        }

        fun addDailySummaryTask() {
            addDomainTask(
                domain = ProbeDomain.DAILY_SUMMARY,
                range = plannedRange(
                    maxLookbackDays = config.sleepDays,
                    latestStoredDate = coverage.dailySummaryLatest
                ),
                timeoutMs = DAILY_SUMMARY_SYNC_TIMEOUT_MS
            ) {
                val data = polarManager.fetchDailySummary(deviceId, it.first, it.second)
                persistDailySummary(deviceId, data, "${it.first}..${it.second}")
                DomainPersistenceResult(data.size, shapeForDailySummary(data))
            }
        }

        fun addActivitySamplesTask(maxLookbackDays: Int) {
            if (!ENABLE_ACTIVITY_SAMPLE_SYNC) return
            addDomainTask(
                domain = ProbeDomain.ACTIVITY_SAMPLES,
                range = plannedRange(
                    maxLookbackDays = maxLookbackDays,
                    latestStoredDate = coverage.activitySamplesLatest
                ),
                timeoutMs = ACTIVITY_SAMPLE_SYNC_TIMEOUT_MS
            ) {
                val data = polarManager.fetchActivitySamples(deviceId, it.first, it.second)
                persistActivitySamples(deviceId, data, "${it.first}..${it.second}")
                DomainPersistenceResult(data.size, shapeForActivitySamples(data))
            }
        }

        fun addPrimaryDataTasks(includeActivitySamples: Boolean, activityMaxLookbackDays: Int) {
            addSleepTask()
            addNightlyRechargeTask()
            addPpiTask()
            addHrTask()
            addSkinTemperatureTask()
            addDailySummaryTask()
            if (includeActivitySamples) {
                addActivitySamplesTask(activityMaxLookbackDays)
            }
        }

        when (profile) {
            SyncRunProfile.MORNING_SLEEP_RETRY -> {
                val retryLookback = config.sleepDays.coerceAtMost(7)
                addSleepTask(maxLookbackDays = retryLookback)
                addNightlyRechargeTask(maxLookbackDays = retryLookback.coerceAtMost(config.nightlyRechargeDays))
            }
            SyncRunProfile.MORNING_PPI_RETRY -> addPpiTask()
            SyncRunProfile.CHECK_IN,
            SyncRunProfile.MORNING_CORE -> addPrimaryDataTasks(
                includeActivitySamples = true,
                activityMaxLookbackDays = config.sleepDays
                    .coerceAtMost(SyncRangePlanner.CHECK_IN_ACTIVITY_MAX_LOOKBACK_DAYS)
            )
            SyncRunProfile.FULL -> addPrimaryDataTasks(
                includeActivitySamples = true,
                activityMaxLookbackDays = config.sleepDays
            )
        }
    }

    private fun syncRangeNotes(range: SyncRangePlanner.PlannedRange): String =
        if (range.incremental) {
            "incremental+${range.dayCountInclusive}d"
        } else {
            "full+${range.dayCountInclusive}d"
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

    private suspend fun pruneRawPpiBuffer(syncRunId: Long, deviceId: String) {
        val startedAt = System.currentTimeMillis()
        val cutoffDate = LocalDate.now(ZoneOffset.UTC).minusDays(RAW_PPI_RETENTION_DAYS).toString()
        val prunableDates = dao.getPrunablePpiRawSourceDatesBefore(
            deviceId = deviceId,
            cutoffDate = cutoffDate,
            retainedRequestedRangePrefix = CLOUD_BACKFILL_REQUESTED_RANGE_PREFIX
        )
        if (prunableDates.isEmpty()) return
        val deletedRows = dao.deletePpiRawRecordsForDates(
            deviceId = deviceId,
            sourceDates = prunableDates,
            retainedRequestedRangePrefix = CLOUD_BACKFILL_REQUESTED_RANGE_PREFIX
        )
        val detail = "raw PPI retention kept ${RAW_PPI_RETENTION_DAYS}d buffer; deletedRows=$deletedRows, dates=${prunableDates.size}, cutoff=$cutoffDate, retainedCloudBackfill=true"
        dao.insertSyncDomainResult(
            SyncDomainResultEntity(
                syncRunId = syncRunId,
                deviceId = deviceId,
                domain = ProbeDomain.PPI_247.name,
                requestedRange = "raw_ppi_retention",
                status = ProbeStatus.SUPPORTED.name,
                recordCount = deletedRows,
                parserVersion = PARSER_VERSION,
                parseStatus = ProbeStatus.RAW_ONLY.name,
                detailSummary = detail,
                rawPayloadJson = GsonProvider.gson.toJson(
                    mapOf(
                        "purpose" to "raw_ppi_retention",
                        "retentionDays" to RAW_PPI_RETENTION_DAYS,
                        "cutoffDate" to cutoffDate,
                        "retainedRequestedRangePrefix" to CLOUD_BACKFILL_REQUESTED_RANGE_PREFIX,
                        "deletedRows" to deletedRows,
                        "deletedSourceDates" to prunableDates
                    )
                ),
                manualNotes = null,
                startedAtEpochMs = startedAt,
                endedAtEpochMs = System.currentTimeMillis(),
                errorCode = null,
                errorMessage = null
            )
        )
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

    suspend fun runDeviceDateFileListProbe(deviceId: String, from: LocalDate, to: LocalDate): Result<Long> = withContext(Dispatchers.IO) {
        require(!to.isBefore(from)) { "Device file list probe end must be on or after start" }
        runCatching {
            val startedAt = System.currentTimeMillis()
            val dates = mutableListOf<LocalDate>()
            var date = from
            while (!date.isAfter(to)) {
                require(dates.size < DEVICE_FILE_LIST_PROBE_MAX_DAYS) {
                    "Device file list probe is limited to $DEVICE_FILE_LIST_PROBE_MAX_DAYS days"
                }
                dates += date
                date = date.plusDays(1)
            }

            val dayResults = withTimeout(DEVICE_FILE_LIST_PROBE_TIMEOUT_MS) {
                runWithinSyncSession(deviceId, "device_file_list_probe") {
                    dates.map { probeDate ->
                        val path = deviceDateDirectoryPath(probeDate)
                        val result = runCatching {
                            polarManager.listDeviceFiles(deviceId, path, recursive = true).sorted()
                        }
                        val entries = result.getOrDefault(emptyList())
                        val activityEntries = entries.filter(::isActivityDeviceFileEntry)
                        val dailySummaryEntries = entries.filter(::isDailySummaryDeviceFileEntry)
                        DeviceFileListProbeDay(
                            date = probeDate.toString(),
                            path = path,
                            listed = result.isSuccess,
                            entryCount = entries.size,
                            activityEntryCount = activityEntries.size,
                            dailySummaryEntryCount = dailySummaryEntries.size,
                            activityEntries = activityEntries.take(DEVICE_FILE_LIST_PROBE_ENTRY_LIMIT),
                            dailySummaryEntries = dailySummaryEntries.take(DEVICE_FILE_LIST_PROBE_ENTRY_LIMIT),
                            entries = entries.take(DEVICE_FILE_LIST_PROBE_ENTRY_LIMIT),
                            entriesTruncated = entries.size > DEVICE_FILE_LIST_PROBE_ENTRY_LIMIT,
                            error = result.exceptionOrNull()?.message ?: result.exceptionOrNull()?.javaClass?.simpleName
                        )
                    }
                }
            }
            val errorCount = dayResults.count { !it.listed }
            val activityDays = dayResults.count { it.activityEntryCount > 0 }
            val dailySummaryDays = dayResults.count { it.dailySummaryEntryCount > 0 }
            val payload = mapOf(
                "purpose" to "read_only_device_date_file_list_probe",
                "deviceId" to deviceId,
                "startedAtEpochMs" to startedAt,
                "from" to from.toString(),
                "to" to to.toString(),
                "dateRoot" to DEVICE_DATE_ROOT_PATH,
                "days" to dayResults
            )
            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = if (errorCount == 0) "success" else "partial_failure",
                    notes = "read-only device date file list probe"
                )
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.ACTIVITY_SAMPLES.name,
                    requestedRange = "device_file_list:$from..$to",
                    status = if (errorCount == 0) ProbeStatus.SUPPORTED.name else ProbeStatus.PARTIAL.name,
                    recordCount = dayResults.sumOf { it.entryCount },
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "days=${dayResults.size}, activityFileDays=$activityDays, dailySummaryFileDays=$dailySummaryDays, listErrors=$errorCount",
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = "read-only Loop date-directory listing; filenames only",
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    errorCode = if (errorCount == 0) null else "DEVICE_FILE_LIST_PARTIAL",
                    errorMessage = if (errorCount == 0) null else "$errorCount date directories could not be listed"
                )
            )
            syncRunId
        }
    }

    suspend fun runAutosFileProbe(deviceId: String, from: LocalDate, to: LocalDate): Result<Long> = withContext(Dispatchers.IO) {
        require(!to.isBefore(from)) { "AUTOS file probe end must be on or after start" }
        runCatching {
            val startedAt = System.currentTimeMillis()
            val probe = withTimeout(AUTOS_FILE_PROBE_TIMEOUT_MS) {
                runWithinSyncSession(deviceId, "autos_file_probe") {
                    val listedPaths = polarManager
                        .listDeviceFiles(deviceId, AUTOS_FILE_ROOT_PATH, recursive = false)
                        .sorted()
                    val autosPaths = listedPaths
                        .filter { AUTOS_FILE_NAME_REGEX.matches(it.substringAfterLast('/')) }
                        .sorted()
                    val files = autosPaths.map { path ->
                        val readStartedAt = System.currentTimeMillis()
                        runCatching {
                            val bytes = polarManager.getDeviceFile(deviceId, path)
                            autosFileSummary(
                                path = path,
                                bytes = bytes,
                                requestedFrom = from,
                                requestedTo = to,
                                readStartedAt = readStartedAt
                            )
                        }.getOrElse { error ->
                            mapOf(
                                "path" to path,
                                "name" to path.substringAfterLast('/'),
                                "readSucceeded" to false,
                                "parseSucceeded" to false,
                                "error" to (error.message ?: error.javaClass.simpleName),
                                "durationMs" to (System.currentTimeMillis() - readStartedAt)
                            )
                        }
                    }
                    val requestedDayFiles = files.filter { it["inRequestedRange"] == true }
                    val requestedPpiSessions = requestedDayFiles.sumOf { (it["ppiSessionCount"] as? Int) ?: 0 }
                    val requestedPpiSamples = requestedDayFiles.sumOf { (it["ppiSampleCount"] as? Int) ?: 0 }
                    mapOf(
                        "listedPaths" to listedPaths,
                        "files" to files,
                        "requestedDayFileCount" to requestedDayFiles.size,
                        "requestedDayPpiSessionCount" to requestedPpiSessions,
                        "requestedDayPpiSampleCount" to requestedPpiSamples
                    )
                }
            }
            val files = probe["files"] as? List<*> ?: emptyList<Any?>()
            val requestedDayFileCount = (probe["requestedDayFileCount"] as? Int) ?: 0
            val requestedDayPpiSessionCount = (probe["requestedDayPpiSessionCount"] as? Int) ?: 0
            val requestedDayPpiSampleCount = (probe["requestedDayPpiSampleCount"] as? Int) ?: 0
            val payload = mapOf(
                "purpose" to "read_only_autos_file_probe",
                "deviceId" to deviceId,
                "startedAtEpochMs" to startedAt,
                "from" to from.toString(),
                "to" to to.toString(),
                "root" to AUTOS_FILE_ROOT_PATH,
                "listedPaths" to probe["listedPaths"],
                "files" to files
            )
            val syncRunId = dao.insertSyncRun(
                SyncRunEntity(
                    deviceId = deviceId,
                    firmwareVersion = runtimeState.value.firmwareVersion,
                    appVersion = APP_VERSION,
                    startedAtEpochMs = startedAt,
                    endedAtEpochMs = System.currentTimeMillis(),
                    status = "success",
                    notes = "read-only AUTOS automatic samples file probe"
                )
            )
            dao.insertSyncDomainResult(
                SyncDomainResultEntity(
                    syncRunId = syncRunId,
                    deviceId = deviceId,
                    domain = ProbeDomain.PPI_247.name,
                    requestedRange = "autos_file_probe:$from..$to",
                    status = ProbeStatus.SUPPORTED.name,
                    recordCount = files.size,
                    parserVersion = PARSER_VERSION,
                    parseStatus = ProbeStatus.RAW_ONLY.name,
                    detailSummary = "autosFiles=${files.size}, requestedDayFiles=$requestedDayFileCount, requestedPpiSessions=$requestedDayPpiSessionCount, requestedPpiSamples=$requestedDayPpiSampleCount",
                    rawPayloadJson = GsonProvider.gson.toJson(payload),
                    manualNotes = "read-only AUTOS file metadata; no raw automatic-sample payload bytes or values stored",
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
        rangeNotes: String? = null,
        block: suspend (Pair<LocalDate, LocalDate>) -> DomainPersistenceResult
    ): Throwable? {
        val startedAt = System.currentTimeMillis()
        val requestedRange = buildString {
            append(from)
            append("..")
            append(to)
            if (!rangeNotes.isNullOrBlank()) {
                append(" (")
                append(rangeNotes)
                append(')')
            }
        }
        try {
            val result = withTimeout(timeoutMs) {
                if (domain == ProbeDomain.PPI_247) {
                    runWhileDeviceConnected(deviceId, domain) {
                        block(from to to)
                    }
                } else {
                    block(from to to)
                }
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

    private suspend fun <T> runWhileDeviceConnected(
        deviceId: String,
        domain: ProbeDomain,
        block: suspend () -> T
    ): T {
        if (!runtimeState.value.isConnectedTo(deviceId)) {
            throw DeviceDisconnectedDuringSyncException(domain)
        }
        return coroutineScope {
            val operation = async { block() }
            val disconnect = async<T> {
                runtimeState.first { runtime -> !runtime.isConnectedTo(deviceId) }
                throw DeviceDisconnectedDuringSyncException(domain)
            }
            try {
                select {
                    operation.onAwait { result ->
                        disconnect.cancel()
                        result
                    }
                    disconnect.onAwait { result ->
                        operation.cancel()
                        result
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } finally {
                disconnect.cancel()
                if (!operation.isCompleted) {
                    operation.cancel()
                }
            }
        }
    }

    private fun com.daveharris.healthmonitor.polar.DeviceRuntimeState.isConnectedTo(deviceId: String): Boolean {
        val device = connectedDevice ?: return false
        return connectionPhase == "connected" &&
            (
                device.deviceId.equals(deviceId, ignoreCase = true) ||
                    device.address.equals(deviceId, ignoreCase = true)
                )
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
        val filtered = filterNewRecords(records, existingSleepPayloadsForIncoming(deviceId, records))
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
        val filtered = filterNewRecords(records, existingNightlyRechargePayloadsForIncoming(deviceId, records))
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
                dao.deleteHrRecordsForDatesExceptRequestedRangePrefix(
                    deviceId = null,
                    sourceDates = rebuild.rawSourceDatesWithEpochs,
                    retainedRequestedRangePrefix = CLOUD_BACKFILL_REQUESTED_RANGE_PREFIX
                )
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
        val sourceDates = data.map { it.date.toString() }.distinct()
        val existingKeys = if (sourceDates.isEmpty()) {
            hashSetOf()
        } else {
            dao.getExistingPpiRecordKeysForDates(deviceId, sourceDates).toHashSet()
        }
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
                    dao.deleteSkinTemperatureRecordsForDatesExceptRequestedRangePrefix(
                        deviceId = null,
                        sourceDates = skinSafeToPrune,
                        retainedRequestedRangePrefix = CLOUD_BACKFILL_REQUESTED_RANGE_PREFIX
                    )
                }
                if (activitySafeToPrune.isNotEmpty()) {
                    dao.deleteActivitySampleRecordsForDatesExceptRequestedRangePrefix(
                        deviceId = null,
                        sourceDates = activitySafeToPrune,
                        retainedRequestedRangePrefix = CLOUD_BACKFILL_REQUESTED_RANGE_PREFIX
                    )
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
        val filtered = filterNewRecords(records, existingDailySummaryPayloadsForIncoming(deviceId, records))
        filtered.forEach { dao.deleteDailySummaryRecordsForDate(deviceId, it.sourceDate) }
        dao.insertDailySummaryRecords(filtered)
    }

    private suspend fun existingSleepPayloadsForIncoming(
        deviceId: String,
        records: List<SleepNightRawEntity>
    ): List<String> = existingPayloadsForIncoming(
        records = records,
        sourceDate = { it.sourceDate },
        scopedLookup = { sourceDates -> dao.getExistingSleepPayloadsForDates(deviceId, sourceDates) },
        fullLookup = { dao.getExistingSleepPayloads(deviceId) }
    )

    private suspend fun existingNightlyRechargePayloadsForIncoming(
        deviceId: String,
        records: List<NightlyRechargeRawEntity>
    ): List<String> = existingPayloadsForIncoming(
        records = records,
        sourceDate = { it.sourceDate },
        scopedLookup = { sourceDates -> dao.getExistingNightlyRechargePayloadsForDates(deviceId, sourceDates) },
        fullLookup = { dao.getExistingNightlyRechargePayloads(deviceId) }
    )

    private suspend fun existingDailySummaryPayloadsForIncoming(
        deviceId: String,
        records: List<DailySummaryRawEntity>
    ): List<String> = existingPayloadsForIncoming(
        records = records,
        sourceDate = { it.sourceDate },
        scopedLookup = { sourceDates -> dao.getExistingDailySummaryPayloadsForDates(deviceId, sourceDates) },
        fullLookup = { dao.getExistingDailySummaryPayloads(deviceId) }
    )

    private suspend fun <T> existingPayloadsForIncoming(
        records: List<T>,
        sourceDate: (T) -> String?,
        scopedLookup: suspend (List<String>) -> List<String>,
        fullLookup: suspend () -> List<String>
    ): List<String> {
        if (records.isEmpty()) return emptyList()
        val sourceDates = records.map { sourceDate(it)?.takeIf(String::isNotBlank) }
        return if (sourceDates.all { it != null }) {
            scopedLookup(sourceDates.filterNotNull().distinct())
        } else {
            fullLookup()
        }
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

    private fun autosFileSummary(
        path: String,
        bytes: ByteArray,
        requestedFrom: LocalDate,
        requestedTo: LocalDate,
        readStartedAt: Long
    ): Map<String, Any?> {
        val parsed = runCatching { PbAutomaticSampleSessions.parseFrom(bytes) }
        if (parsed.isFailure) {
            val error = parsed.exceptionOrNull()
            return mapOf(
                "path" to path,
                "name" to path.substringAfterLast('/'),
                "readSucceeded" to true,
                "parseSucceeded" to false,
                "byteCount" to bytes.size,
                "sha256_12" to sha256Prefix(bytes),
                "error" to (error?.message ?: error?.javaClass?.simpleName),
                "durationMs" to (System.currentTimeMillis() - readStartedAt)
            )
        }

        val sessions = parsed.getOrThrow()
        val day = sessions.day.takeIf { sessions.hasDay() }?.toLocalDateOrNull()
        val ppiTimes = sessions.ppiSamplesList.mapNotNull { sample ->
            sample.recordingTime.takeIf { sample.hasRecordingTime() }?.toLocalTimeOrNull()
        }
        val hrTimes = sessions.samplesList.mapNotNull { sample ->
            sample.time.takeIf { sample.hasTime() }?.toLocalTimeOrNull()
        }
        return mapOf(
            "path" to path,
            "name" to path.substringAfterLast('/'),
            "readSucceeded" to true,
            "parseSucceeded" to true,
            "byteCount" to bytes.size,
            "sha256_12" to sha256Prefix(bytes),
            "day" to day?.toString(),
            "inRequestedRange" to (day != null && !day.isBefore(requestedFrom) && !day.isAfter(requestedTo)),
            "ppiSessionCount" to sessions.ppiSamplesCount,
            "ppiSampleCount" to sessions.ppiSamplesList.sumOf { if (it.hasPpi()) it.ppi.ppiDeltaCount else 0 },
            "ppiFirstTime" to ppiTimes.minOrNull()?.toString(),
            "ppiLastTime" to ppiTimes.maxOrNull()?.toString(),
            "hrSessionCount" to sessions.samplesCount,
            "hrSampleCount" to sessions.samplesList.sumOf { it.heartRateCount },
            "hrFirstTime" to hrTimes.minOrNull()?.toString(),
            "hrLastTime" to hrTimes.maxOrNull()?.toString(),
            "durationMs" to (System.currentTimeMillis() - readStartedAt)
        )
    }

    private fun PbDate.toLocalDateOrNull(): LocalDate? =
        if (hasYear() && hasMonth() && hasDay()) {
            runCatching { LocalDate.of(year, month, day) }.getOrNull()
        } else {
            null
        }

    private fun PbTime.toLocalTimeOrNull(): LocalTime? =
        if (hasHour() && hasMinute() && hasSeconds()) {
            runCatching { LocalTime.of(hour, minute, seconds, (if (hasMillis()) millis else 0) * 1_000_000) }.getOrNull()
        } else {
            null
        }

    private fun sha256Prefix(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .take(6)
            .joinToString("") { "%02x".format(it) }

    private fun deviceDateDirectoryPath(date: LocalDate): String =
        "$DEVICE_DATE_ROOT_PATH${date.format(DateTimeFormatter.BASIC_ISO_DATE)}/"

    private fun isActivityDeviceFileEntry(entry: String): Boolean =
        entry.contains("/ACT/", ignoreCase = true) || entry.contains("ASAMPL", ignoreCase = true)

    private fun isDailySummaryDeviceFileEntry(entry: String): Boolean =
        entry.contains("/DSUM/", ignoreCase = true) || entry.endsWith("DSUM.BPB", ignoreCase = true)

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
        wakeMarkers: List<WakeMarkerEntity>,
        sleepEpisodes: List<SleepEpisodeEntity> = emptyList(),
        expectedSourceDate: String = LocalDate.now(ZoneId.systemDefault()).toString(),
        allowProvisional: Boolean = true,
        currentState: CurrentStateRead? = null
    ): MorningReadSnapshot? {
        if (sleepRow == null && nightlyRow == null && ppi247Epochs.isEmpty() && sleepEpisodes.isEmpty()) return null

        val todayPpiEpochs = ppi247Epochs.filter { it.sourceDate == expectedSourceDate }
        val primaryEpisodeWindow = selectedPrimaryReadinessEpisode(expectedSourceDate, sleepEpisodes)
            ?.toPrimaryReadinessWindow()
        val latestNightlyJson = nightlyRow?.rawPayloadJson?.let {
            runCatching { GsonProvider.gson.fromJson(it, JsonObject::class.java) }.getOrNull()
        }
        val latestNightlySummary = latestNightlyJson?.getAsJsonObject("summary")
        val latestBaselineReady = latestNightlySummary?.booleanOrNull("baselineReady") ?: false
        if (primaryEpisodeWindow == null && hasConfirmedNoMainSleep(expectedSourceDate, sleepEpisodes)) {
            return noMainSleepMorningRead(
                sourceDate = expectedSourceDate,
                baselineReady = latestBaselineReady,
                currentState = currentState
            )
        }
        if (sleepRow?.sourceDate != expectedSourceDate) {
            if (!allowProvisional) return null
            val provisionalWindow = primaryEpisodeWindow
                ?: provisionalSleepWindowForDate(expectedSourceDate, wakeMarkers, ppi247Epochs)
            val ppi247Autonomic = provisionalWindow?.let {
                summarizePpi247ForSleepWindow(
                    sourceDate = null,
                    sleepStartEpochMs = it.startEpochMs,
                    sleepEndEpochMs = it.endEpochMs,
                    epochs = ppi247Epochs
                )
            }
            val hasRawPpi = todayPpiEpochs.isNotEmpty() || ppi247Autonomic != null
            val provisionalDurationMinutes = provisionalWindow?.durationMinutes
            val pendingSource = provisionalMorningReadSource(
                provisionalWindow = provisionalWindow,
                ppi247Autonomic = ppi247Autonomic,
                hasRawPpi = hasRawPpi
            )
            val scoreResult = currentState.toScoreResult()
            val provisionalStatus = provisionalStatus(
                scoreResult = scoreResult,
                provisionalWindow = provisionalWindow,
                ppi247Autonomic = ppi247Autonomic
            )
            return MorningReadSnapshot(
                sourceDate = expectedSourceDate,
                status = provisionalStatus,
                confidence = provisionalConfidence(provisionalStatus, ppi247Autonomic),
                overnightAutonomicSource = pendingSource,
                sleepDurationMinutes = provisionalDurationMinutes,
                nightlyRmssd = ppi247Autonomic?.averageRmssdMs,
                baselineReady = latestBaselineReady,
                recoveryAvailable = false,
                summary = provisionalSummary(provisionalStatus, ppi247Autonomic, "waiting for Polar sleep data"),
                reasons = listOf(
                    "Today’s resolved sleep window is not available yet.",
                    if (ppi247Autonomic != null) {
                        "A provisional PPI read is available from the ${provisionalWindow.label}."
                    } else if (provisionalWindow?.hasExplicitWakeMarker == true) {
                        "A marker-defined sleep window is available, but no usable overnight PPI overlapped it."
                    } else if (hasRawPpi && provisionalWindow == null) {
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
                rawPpiCoverageHours = ppi247Autonomic?.coverageHours,
                hrvTrajectory = ppi247Autonomic?.trajectoryPoints.orEmpty()
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
            if (!allowProvisional) return null
            val provisionalWindow = primaryEpisodeWindow
                ?: provisionalSleepWindowForDate(expectedSourceDate, wakeMarkers, ppi247Epochs)
            val ppi247Autonomic = provisionalWindow?.let {
                summarizePpi247ForSleepWindow(
                    sourceDate = null,
                    sleepStartEpochMs = it.startEpochMs,
                    sleepEndEpochMs = it.endEpochMs,
                    epochs = ppi247Epochs
                )
            }
            val hasRawPpi = todayPpiEpochs.isNotEmpty() || ppi247Autonomic != null
            val provisionalDurationMinutes = provisionalWindow?.durationMinutes
            val pendingSource = provisionalMorningReadSource(
                provisionalWindow = provisionalWindow,
                ppi247Autonomic = ppi247Autonomic,
                hasRawPpi = hasRawPpi
            )
            val scoreResult = currentState.toScoreResult()
            val provisionalStatus = provisionalStatus(
                scoreResult = scoreResult,
                provisionalWindow = provisionalWindow,
                ppi247Autonomic = ppi247Autonomic
            )
            return MorningReadSnapshot(
                sourceDate = expectedSourceDate,
                status = provisionalStatus,
                confidence = provisionalConfidence(provisionalStatus, ppi247Autonomic),
                overnightAutonomicSource = pendingSource,
                sleepDurationMinutes = provisionalDurationMinutes,
                nightlyRmssd = ppi247Autonomic?.averageRmssdMs,
                baselineReady = latestBaselineReady,
                recoveryAvailable = false,
                summary = provisionalSummary(provisionalStatus, ppi247Autonomic, "waiting for resolved Polar sleep window"),
                reasons = listOf(
                    "Polar has created today’s sleep record, but the resolved start/end times are not available yet.",
                    if (ppi247Autonomic != null) {
                        "A provisional PPI read is available from the ${provisionalWindow.label}."
                    } else if (provisionalWindow?.hasExplicitWakeMarker == true) {
                        "A marker-defined sleep window is available, but no usable overnight PPI overlapped it."
                    } else if (hasRawPpi && provisionalWindow == null) {
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
                rawPpiCoverageHours = ppi247Autonomic?.coverageHours,
                hrvTrajectory = ppi247Autonomic?.trajectoryPoints.orEmpty()
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
        val loopPpi247Autonomic = summarizePpi247ForSleepWindow(
            sourceDate = sleepRow.sourceDate,
            sleepStartEpochMs = sleepStartEpochMs,
            sleepEndEpochMs = sleepEndEpochMs,
            epochs = ppi247Epochs
        )
        val primaryWindow = primaryEpisodeWindow ?: provisionalSleepWindowForDate(expectedSourceDate, wakeMarkers, ppi247Epochs)
            ?.takeIf { it.hasExplicitWakeMarker }
        val primaryWindowPpi247Autonomic = primaryWindow?.let {
            summarizePpi247ForSleepWindow(
                sourceDate = null,
                sleepStartEpochMs = it.startEpochMs,
                sleepEndEpochMs = it.endEpochMs,
                epochs = ppi247Epochs
            )
        }
        val usePrimaryWindow = primaryWindow != null && primaryWindowPpi247Autonomic != null
        val ppi247Autonomic = primaryWindowPpi247Autonomic ?: loopPpi247Autonomic
        val scoringDurationMinutes = primaryWindow?.durationMinutes?.takeIf { usePrimaryWindow } ?: durationMinutes
        val autonomicRmssd = ppi247Autonomic?.averageRmssdMs ?: rmssd
        val autonomicSource = when {
            usePrimaryWindow && primaryWindow.source == MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT.key ->
                MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PRIMARY_WITH_SLEEP_REPORT.key
            usePrimaryWindow && primaryWindow.source == MorningReadSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT.key ->
                MorningReadSource.RAW_PPI_INFERRED_WINDOW_PRIMARY_WITH_SLEEP_REPORT.key
            usePrimaryWindow -> MorningReadSource.RAW_PPI_MANUAL_WINDOW_PRIMARY_WITH_SLEEP_REPORT.key
            loopPpi247Autonomic != null -> MorningReadSource.PPI247_SLEEP_WINDOW.key
            nightlySummary != null -> MorningReadSource.NIGHTLY_RECHARGE_SUMMARY.key
            else -> MorningReadSource.SLEEP_CONTEXT_ONLY.key
        }

        val scoreResult = currentState.toScoreResult()
        val status = scoreResult.status ?: TrafficLightStatus.UNSTEADY
        val contextReasons = primaryWindow?.let {
            finalSleepReportContextReasons(
                primaryWindow = it,
                loopDurationMinutes = durationMinutes,
                sleepStartEpochMs = sleepStartEpochMs,
                sleepEndEpochMs = sleepEndEpochMs,
                usePrimaryWindow = usePrimaryWindow
            )
        }.orEmpty()
        val reasons = scoreResult.reasons + contextReasons
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
            sleepDurationMinutes = scoringDurationMinutes,
            nightlyRmssd = autonomicRmssd,
            baselineReady = baselineReady,
            recoveryAvailable = recoveryAvailable,
            summary = "${status.name.lowercase().replaceFirstChar { it.titlecase() }} ($confidence confidence)",
            reasons = reasons,
            isInterim = false,
            sleepDataReady = true,
            rawPpiGoodEpochCount = ppi247Autonomic?.goodEpochCount,
            rawPpiPoorEpochCount = ppi247Autonomic?.poorEpochCount,
            rawPpiCoverageHours = ppi247Autonomic?.coverageHours,
            hrvTrajectory = ppi247Autonomic?.trajectoryPoints.orEmpty()
        )
    }

    private fun SleepEpisodeEntity.toPrimaryReadinessWindow(): SleepWindowEstimate? {
        val start = startEpochMs ?: return null
        val end = endEpochMs ?: return null
        if (end <= start) return null
        val sourceLabel = when (source) {
            SleepEpisodeSources.EDITED -> MorningReadSource.EDITED_SLEEP_EPISODE_PRIMARY.key
            SleepEpisodeSources.MIXED -> MorningReadSource.MIXED_SLEEP_EPISODE_PRIMARY.key
            SleepEpisodeSources.MANUAL -> MorningReadSource.MANUAL_SLEEP_EPISODE_PRIMARY.key
            else -> MorningReadSource.CONFIRMED_SLEEP_EPISODE_PRIMARY.key
        }
        val label = when (episodeKind) {
            SleepEpisodeKinds.NAP -> "selected nap window"
            SleepEpisodeKinds.REST_CANDIDATE -> "selected rest window"
            else -> "confirmed sleep window"
        }
        return SleepWindowEstimate(
            startEpochMs = start,
            endEpochMs = end,
            source = sourceLabel,
            label = label,
            hasExplicitWakeMarker = true
        )
    }

    private fun noMainSleepMorningRead(
        sourceDate: String,
        baselineReady: Boolean,
        currentState: CurrentStateRead? = null
    ): MorningReadSnapshot {
        val autonomicSource = MorningReadSource.USER_CONFIRMED_NO_SLEEP.key
        val scoreResult = currentState.toScoreResult()
        val status = scoreResult.status ?: TrafficLightStatus.UNSTEADY
        return MorningReadSnapshot(
            sourceDate = sourceDate,
            status = status,
            confidence = "user_confirmed",
            overnightAutonomicSource = autonomicSource,
            sleepDurationMinutes = 0,
            nightlyRmssd = null,
            baselineReady = baselineReady,
            recoveryAvailable = false,
            summary = "${status.name.lowercase().replaceFirstChar { it.titlecase() }} (user confirmed no sleep)",
            reasons = listOf("You marked this day as having no main sleep window.") + scoreResult.reasons,
            isInterim = false,
            sleepDataReady = true,
            rawPpiGoodEpochCount = null,
            rawPpiPoorEpochCount = null,
            rawPpiCoverageHours = null,
            hrvTrajectory = emptyList()
        )
    }

    private fun provisionalMorningReadSource(
        provisionalWindow: SleepWindowEstimate?,
        ppi247Autonomic: Ppi247WindowSummary?,
        hasRawPpi: Boolean
    ): String =
        when {
            ppi247Autonomic != null -> requireNotNull(provisionalWindow).source
            provisionalWindow?.hasExplicitWakeMarker == true -> MorningReadSource.MARKER_SLEEP_WINDOW_PENDING_SLEEP_REPORT.key
            hasRawPpi && provisionalWindow == null -> MorningReadSource.RAW_PPI_PENDING_MANUAL_SLEEP_WINDOW.key
            hasRawPpi -> MorningReadSource.RAW_PPI_PENDING_SLEEP_WINDOW.key
            else -> MorningReadSource.AWAITING_SLEEP_DATA.key
        }

    private fun provisionalStatus(
        scoreResult: MorningScoreResult,
        provisionalWindow: SleepWindowEstimate?,
        ppi247Autonomic: Ppi247WindowSummary?
    ): TrafficLightStatus? =
        scoreResult.status.takeIf {
            ppi247Autonomic != null || provisionalWindow?.hasExplicitWakeMarker == true
        }

    private fun provisionalConfidence(
        provisionalStatus: TrafficLightStatus?,
        ppi247Autonomic: Ppi247WindowSummary?
    ): String =
        when {
            provisionalStatus == null -> "pending"
            ppi247Autonomic != null -> "interim"
            else -> "low"
        }

    private fun provisionalSummary(
        provisionalStatus: TrafficLightStatus?,
        ppi247Autonomic: Ppi247WindowSummary?,
        fallback: String
    ): String =
        when {
            provisionalStatus == null -> "Interim: $fallback"
            ppi247Autonomic == null -> "Interim: marker-defined sleep window, autonomic unavailable"
            else -> "Interim: $fallback"
        }

    /**
     * Adapter: express the model-v1 [CurrentStateRead] as the legacy score-carrier
     * the snapshot branches consume. Replaces the deleted sleep-recovery scorer
     * `scoreMorningRead`, which was empirically non-predictive and reproduced the
     * Visible cognitive-dissonance failure (see docs/lodestone-model-v1.md). The
     * forecast now comes from lived-function persistence + caution, not sleep.
     */
    private fun CurrentStateRead?.toScoreResult(): MorningScoreResult =
        MorningScoreResult(
            status = this?.forecastLevel,
            reasons = this?.reasons.orEmpty()
        )

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
            lateMinusEarlyRmssdMs = if (earlyAverage != null && lateAverage != null) lateAverage - earlyAverage else null,
            trajectoryPoints = goodEpochs.mapNotNull { epoch ->
                epoch.rmssdMs?.let {
                    HrvTrajectoryPoint(
                        epochStartEpochMs = epoch.epochStartEpochMs,
                        rmssdMs = it,
                        epochQuality = epoch.epochQuality
                    )
                }
            }
        )
    }

    private fun finalSleepReportContextReasons(
        primaryWindow: SleepWindowEstimate,
        loopDurationMinutes: Int,
        sleepStartEpochMs: Long,
        sleepEndEpochMs: Long,
        usePrimaryWindow: Boolean
    ): List<String> {
        if (!usePrimaryWindow) {
            return listOf("Loop final sleep report is available; no usable manual/PPI window replaced it.")
        }
        val reasons = mutableListOf(
            "Primary rating uses the ${primaryWindow.label}; Loop final sleep report is kept as context."
        )
        val primaryDurationMinutes = primaryWindow.durationMinutes
        val durationDelta = primaryDurationMinutes - loopDurationMinutes
        val wakeDelta = ((primaryWindow.endEpochMs - sleepEndEpochMs) / 60_000L).toInt()
        val onsetDelta = ((primaryWindow.startEpochMs - sleepStartEpochMs) / 60_000L).toInt()
        if (kotlin.math.abs(durationDelta) >= SLEEP_REPORT_DISAGREEMENT_MINUTES) {
            reasons += "Loop sleep duration differed from the primary window by ${formatSignedMinutes(durationDelta)}."
        } else if (
            kotlin.math.abs(wakeDelta) >= SLEEP_REPORT_DISAGREEMENT_MINUTES ||
            kotlin.math.abs(onsetDelta) >= SLEEP_REPORT_DISAGREEMENT_MINUTES
        ) {
            reasons += "Loop sleep timing differed from the primary window, but total duration was similar."
        }
        return reasons
    }

    private fun provisionalSleepWindowForDate(
        sourceDate: String,
        wakeMarkers: List<WakeMarkerEntity>,
        ppi247Epochs: List<Ppi247EpochEntity>
    ): SleepWindowEstimate? =
        sleepWindowEstimatesForDate(
            sourceDate = sourceDate,
            wakeMarkers = wakeMarkers,
            ppi247Epochs = ppi247Epochs,
            includeRestCandidates = false
        ).firstOrNull()

    private fun sleepWindowEstimatesForDate(
        sourceDate: String,
        wakeMarkers: List<WakeMarkerEntity>,
        ppi247Epochs: List<Ppi247EpochEntity>,
        includeRestCandidates: Boolean
    ): List<SleepWindowEstimate> {
        val now = System.currentTimeMillis()
        val bounds = sleepSearchBoundsForDate(sourceDate)
        val markers = wakeMarkers
            .asSequence()
            .filter { it.markerEpochMs >= bounds.startEpochMs }
            .filter { it.markerEpochMs <= bounds.endEpochMs }
            .filterNot { it.notes == "manual awake command" }
            .sortedBy { it.markerEpochMs }
            .toList()
        val windowEpochs = ppi247Epochs
            .asSequence()
            .filter { it.epochStartEpochMs >= bounds.startEpochMs && it.epochStartEpochMs <= bounds.endEpochMs }
            .sortedBy { it.epochStartEpochMs }
            .toList()
        val bed = markers.lastOrNull { it.markerSource == WakeMarkerSources.GOING_TO_BED }
        val awakeMarker = markers
            .firstOrNull { marker ->
                marker.markerSource == WakeMarkerSources.IM_AWAKE &&
                    (bed == null || marker.markerEpochMs > bed.markerEpochMs)
            }
        val latestAllowedWake = minOf(awakeMarker?.markerEpochMs ?: now, bounds.endEpochMs)
        val searchStart = bed?.markerEpochMs ?: bounds.startEpochMs
        val boundedWake = latestAllowedWake.coerceAtLeast(searchStart)
        val ppiOnset = estimateSleepOnsetEpochMs(
            searchStartEpochMs = searchStart,
            endEpochMs = boundedWake,
            epochs = windowEpochs,
            hasManualBedMarker = bed != null
        )
        val primaryWindow = if (bed == null && ppiOnset == null) {
            null
        } else {
            buildPrimarySleepWindowEstimate(
                bed = bed,
                awakeMarker = awakeMarker,
                searchStartEpochMs = searchStart,
                boundedWakeEpochMs = boundedWake,
                ppiOnsetEpochMs = ppiOnset,
                epochs = windowEpochs
            )
        }
        val restCandidates = if (includeRestCandidates) {
            inferPpiRestWindowCandidates(windowEpochs)
                .filterNot { candidate ->
                    primaryWindow?.let { candidate.overlaps(it) } == true
                }
        } else {
            emptyList()
        }
        return listOfNotNull(primaryWindow) + restCandidates
    }

    private fun sleepSearchBoundsForDate(sourceDate: String): SleepSearchBounds {
        val targetDate = runCatching { LocalDate.parse(sourceDate) }
            .getOrDefault(LocalDate.now(ZoneId.systemDefault()))
        val startEpochMs = targetDate
            .minusDays(1)
            .atTime(LocalTime.NOON)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val endEpochMs = targetDate
            .atTime(LocalTime.NOON)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        return SleepSearchBounds(startEpochMs, endEpochMs)
    }

    private fun buildPrimarySleepWindowEstimate(
        bed: WakeMarkerEntity?,
        awakeMarker: WakeMarkerEntity?,
        searchStartEpochMs: Long,
        boundedWakeEpochMs: Long,
        ppiOnsetEpochMs: Long?,
        epochs: List<Ppi247EpochEntity>
    ): SleepWindowEstimate? {
        if (bed == null && ppiOnsetEpochMs == null) return null
        val ppiWake = ppiOnsetEpochMs?.let {
            estimateSleepEndEpochMs(
                onsetEpochMs = it,
                latestAllowedEpochMs = boundedWakeEpochMs,
                epochs = epochs
            )
        }
        val manualWake = awakeMarker?.markerEpochMs?.let { (it - MANUAL_WAKE_BACKDATE_MS).coerceAtLeast(searchStartEpochMs) }
        val estimatedWake = when {
            ppiWake != null && manualWake != null && manualWake - ppiWake > MANUAL_WAKE_OUTLIER_MS -> ppiWake
            manualWake != null -> manualWake
            ppiWake != null -> ppiWake
            else -> boundedWakeEpochMs
        }.coerceAtLeast(searchStartEpochMs)
        val estimatedOnset = ppiOnsetEpochMs ?: bed?.markerEpochMs ?: return null
        if (estimatedWake <= estimatedOnset) return null
        val source = when {
            bed == null -> MorningReadSource.RAW_PPI_INFERRED_WINDOW_PENDING_SLEEP_REPORT.key
            estimatedOnset > bed.markerEpochMs -> MorningReadSource.RAW_PPI_CALIBRATED_WINDOW_PENDING_SLEEP_REPORT.key
            else -> MorningReadSource.RAW_PPI_MANUAL_WINDOW_PENDING_SLEEP_REPORT.key
        }
        val label = when {
            bed == null && awakeMarker == null -> "PPI-inferred sleep/wake window"
            bed == null -> "PPI-inferred onset/manual wake window"
            awakeMarker == null -> "calibrated onset/PPI-inferred wake window"
            estimatedOnset > bed.markerEpochMs -> "calibrated onset/manual wake window"
            else -> "manual bed/wake window"
        }
        return SleepWindowEstimate(
            startEpochMs = estimatedOnset,
            endEpochMs = estimatedWake,
            source = source,
            label = label,
            hasExplicitWakeMarker = awakeMarker != null
        )
    }

    private fun inferPpiRestWindowCandidates(epochs: List<Ppi247EpochEntity>): List<SleepWindowEstimate> {
        val sleepLikeEpochs = epochs
            .asSequence()
            .filter { it.isSleepLikePpiEpoch() }
            .sortedBy { it.epochStartEpochMs }
            .toList()
        if (sleepLikeEpochs.size < SLEEP_ONSET_WINDOW_EPOCHS) return emptyList()

        val blocks = mutableListOf<List<Ppi247EpochEntity>>()
        var currentBlock = mutableListOf<Ppi247EpochEntity>()
        sleepLikeEpochs.forEach { epoch ->
            val previous = currentBlock.lastOrNull()
            if (previous == null || epoch.epochStartEpochMs - previous.epochEndEpochMs <= PPI_REST_CANDIDATE_MAX_GAP_MS) {
                currentBlock += epoch
            } else {
                blocks += currentBlock
                currentBlock = mutableListOf(epoch)
            }
        }
        if (currentBlock.isNotEmpty()) {
            blocks += currentBlock
        }

        return blocks.mapNotNull { block ->
            val startEpochMs = block.firstOrNull()?.epochStartEpochMs ?: return@mapNotNull null
            val endEpochMs = block.lastOrNull()?.epochEndEpochMs ?: return@mapNotNull null
            val durationMs = endEpochMs - startEpochMs
            if (durationMs < MIN_REST_CANDIDATE_WINDOW_MS || block.size < SLEEP_ONSET_WINDOW_EPOCHS) return@mapNotNull null
            val hasHrDrop = hasLocalHrDrop(startEpochMs, block, epochs)
            val label = if (durationMs >= MIN_INFERRED_SLEEP_WINDOW_MS && hasHrDrop) {
                "PPI-inferred main sleep candidate"
            } else {
                "PPI sleep/rest candidate"
            }
            SleepWindowEstimate(
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                source = RAW_PPI_REST_CANDIDATE_SOURCE,
                label = label,
                hasExplicitWakeMarker = false
            )
        }
    }

    private fun hasLocalHrDrop(
        candidateStartEpochMs: Long,
        block: List<Ppi247EpochEntity>,
        epochs: List<Ppi247EpochEntity>
    ): Boolean {
        val baselineHr = epochs
            .asSequence()
            .filter { it.epochStartEpochMs < candidateStartEpochMs }
            .filter { it.epochStartEpochMs >= candidateStartEpochMs - MARKERLESS_ONSET_BASELINE_MS }
            .filterNot { it.epochQuality.startsWith("poor") }
            .mapNotNull { it.meanHrBpm }
            .toList()
            .takeIf { it.size >= 3 }
            ?.medianOrNull() ?: return false
        val candidateHr = block.mapNotNull { it.meanHrBpm }.medianOrNull() ?: return false
        return candidateHr <= baselineHr - SLEEP_ONSET_MIN_HR_DROP_BPM
    }

    private fun estimateSleepOnsetEpochMs(
        searchStartEpochMs: Long,
        endEpochMs: Long,
        epochs: List<Ppi247EpochEntity>,
        hasManualBedMarker: Boolean
    ): Long? {
        val candidateEpochs = epochs
            .asSequence()
            .filter { it.epochStartEpochMs >= searchStartEpochMs && it.epochStartEpochMs <= endEpochMs }
            .filter { it.meanHrBpm != null }
            .sortedBy { it.epochStartEpochMs }
            .toList()
        if (candidateEpochs.size < SLEEP_ONSET_WINDOW_EPOCHS) return null
        candidateEpochs.windowed(SLEEP_ONSET_WINDOW_EPOCHS, step = 1).forEach { window ->
            if (window.any { !it.isSleepLikePpiEpoch() }) {
                return@forEach
            }
            val baselineHr = if (hasManualBedMarker) {
                candidateEpochs
                    .asSequence()
                    .filter { it.epochStartEpochMs < searchStartEpochMs + 60 * 60_000L }
                    .filterNot { it.epochQuality.startsWith("poor") }
                    .mapNotNull { it.meanHrBpm }
                    .toList()
                    .takeIf { it.size >= 3 }
                    ?.medianOrNull()
            } else {
                candidateEpochs
                    .asSequence()
                    .filter { it.epochStartEpochMs < window.first().epochStartEpochMs }
                    .filter { it.epochStartEpochMs >= window.first().epochStartEpochMs - MARKERLESS_ONSET_BASELINE_MS }
                    .filterNot { it.epochQuality.startsWith("poor") }
                    .mapNotNull { it.meanHrBpm }
                    .toList()
                    .takeIf { it.size >= 3 }
                    ?.medianOrNull()
            } ?: return@forEach
            val hrs = window.mapNotNull { it.meanHrBpm }
            val samples = window.sumOf { it.sampleCount }.coerceAtLeast(1)
            val movementRatio = window.sumOf { it.movementDetectedCount }.toDouble() / samples.toDouble()
            if (
                hrs.medianOrNull()?.let { it <= baselineHr - SLEEP_ONSET_MIN_HR_DROP_BPM } == true &&
                hrs.populationStdDev() <= SLEEP_ONSET_MAX_HR_STD_BPM &&
                movementRatio <= SLEEP_ONSET_MAX_MOVEMENT_RATIO
            ) {
                return window.first().epochStartEpochMs
            }
        }
        return null
    }

    private fun estimateSleepEndEpochMs(
        onsetEpochMs: Long,
        latestAllowedEpochMs: Long,
        epochs: List<Ppi247EpochEntity>
    ): Long? {
        val sleepEpochs = epochs
            .asSequence()
            .filter { it.epochStartEpochMs >= onsetEpochMs && it.epochEndEpochMs <= latestAllowedEpochMs }
            .filter { it.isSleepLikePpiEpoch() }
            .sortedBy { it.epochStartEpochMs }
            .toList()
        if (sleepEpochs.size < SLEEP_ONSET_WINDOW_EPOCHS) return null
        val endEpochMs = sleepEpochs.last().epochEndEpochMs
        return endEpochMs.takeIf { it - onsetEpochMs >= MIN_INFERRED_SLEEP_WINDOW_MS }
    }

    private fun Ppi247EpochEntity.isSleepLikePpiEpoch(): Boolean {
        val samples = sampleCount.coerceAtLeast(1)
        val movementRatio = movementDetectedCount.toDouble() / samples.toDouble()
        return meanHrBpm != null &&
            !epochQuality.startsWith("poor") &&
            movementRatio <= SLEEP_ONSET_MAX_MOVEMENT_RATIO
    }

    private fun SleepWindowEstimate.overlaps(other: SleepWindowEstimate): Boolean =
        startEpochMs < other.endEpochMs && other.startEpochMs < endEpochMs

    private fun SleepWindowEstimate.toSleepEpisodeCandidate(
        sourceDate: String,
        nowEpochMs: Long
    ): SleepEpisodeEntity {
        val durationMs = endEpochMs - startEpochMs
        val isRestCandidate = source == RAW_PPI_REST_CANDIDATE_SOURCE || durationMs < MIN_INFERRED_SLEEP_WINDOW_MS
        val episodeSource = SleepEpisodeSources.PPI_INFERRED
        val confidence = when {
            hasExplicitWakeMarker -> SleepEpisodeConfidences.MEDIUM
            durationMs >= MIN_INFERRED_SLEEP_WINDOW_MS -> SleepEpisodeConfidences.MEDIUM
            else -> SleepEpisodeConfidences.LOW
        }
        val evidenceJson = GsonProvider.gson.toJson(
            mapOf(
                "label" to label,
                "estimateSource" to source,
                "durationMinutes" to durationMinutes,
                "hasExplicitWakeMarker" to hasExplicitWakeMarker,
                "candidateOnly" to true
            )
        )
        return SleepEpisodeEntity(
            sourceDate = sourceDate,
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            episodeKind = if (isRestCandidate) SleepEpisodeKinds.REST_CANDIDATE else SleepEpisodeKinds.MAIN_SLEEP,
            source = episodeSource,
            confidence = confidence,
            isPrimaryForReadiness = false,
            deviceId = null,
            linkedSleepRawId = null,
            evidenceJson = evidenceJson,
            notes = null,
            createdAtEpochMs = nowEpochMs,
            updatedAtEpochMs = nowEpochMs
        )
    }

    private fun parsePolarDateTimeEpochMs(value: String): Long? =
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
            .getOrNull()

    private fun List<Double>.averageOrNull(): Double? =
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

    private fun List<Double>.populationStdDev(): Double {
        if (size < 2) return 0.0
        val average = average()
        return sqrt(sumOf { value -> val delta = value - average; delta * delta } / size.toDouble())
    }

    private fun MorningReadSnapshot.toMorningPredictionSnapshotEntity(
        snapshotOrigin: String,
        issuedAtEpochMs: Long
    ): MorningPredictionSnapshotEntity =
        MorningPredictionSnapshotEntity(
            sourceDate = requireNotNull(sourceDate),
            issuedAtEpochMs = issuedAtEpochMs,
            snapshotOrigin = snapshotOrigin,
            modelVersion = MORNING_MODEL_VERSION,
            status = requireNotNull(status).name,
            confidence = confidence,
            isInterim = isInterim,
            sleepDataReady = sleepDataReady,
            overnightAutonomicSource = overnightAutonomicSource,
            sleepDurationMinutes = sleepDurationMinutes,
            nightlyRmssd = nightlyRmssd,
            baselineReady = baselineReady,
            recoveryAvailable = recoveryAvailable,
            rawPpiGoodEpochCount = rawPpiGoodEpochCount,
            rawPpiPoorEpochCount = rawPpiPoorEpochCount,
            rawPpiCoverageHours = rawPpiCoverageHours,
            summary = summary,
            reasonsJson = GsonProvider.gson.toJson(reasons)
        )

    private fun CurrentStateRead.toCurrentStateSnapshotEntity(
        snapshotOrigin: String,
        issuedAtEpochMs: Long
    ): CurrentStateSnapshotEntity =
        CurrentStateSnapshotEntity(
            sourceDate = sourceDate,
            issuedAtEpochMs = issuedAtEpochMs,
            snapshotOrigin = snapshotOrigin,
            modelVersion = CURRENT_STATE_MODEL_VERSION,
            forecastLevel = forecastLevel?.name,
            forecastBasis = forecastBasis.name,
            cautionLevel = caution.level.name,
            cautionKind = caution.kind.name,
            cautionReasonsJson = GsonProvider.gson.toJson(caution.reasons),
            confidenceLevel = confidence.name,
            recentOutcomeLevel = recentOutcomeLevel?.name,
            recentOutcomeDate = recentOutcomeDate,
            exertionLoadRecent = exertionLoadRecentMvMinutes,
            hrvCv24h = hrvCv24h,
            reasonsJson = GsonProvider.gson.toJson(reasons)
        )

    private fun CurrentStateSnapshotEntity.isSameRead(other: CurrentStateSnapshotEntity): Boolean =
        sourceDate == other.sourceDate &&
            snapshotOrigin == other.snapshotOrigin &&
            modelVersion == other.modelVersion &&
            forecastLevel == other.forecastLevel &&
            forecastBasis == other.forecastBasis &&
            cautionLevel == other.cautionLevel &&
            cautionKind == other.cautionKind &&
            cautionReasonsJson == other.cautionReasonsJson &&
            confidenceLevel == other.confidenceLevel &&
            recentOutcomeLevel == other.recentOutcomeLevel &&
            recentOutcomeDate == other.recentOutcomeDate &&
            exertionLoadRecent == other.exertionLoadRecent &&
            hrvCv24h == other.hrvCv24h &&
            reasonsJson == other.reasonsJson

    private fun MorningPredictionSnapshotEntity.isSamePrediction(
        other: MorningPredictionSnapshotEntity
    ): Boolean =
        sourceDate == other.sourceDate &&
            snapshotOrigin == other.snapshotOrigin &&
            modelVersion == other.modelVersion &&
            status == other.status &&
            confidence == other.confidence &&
            isInterim == other.isInterim &&
            sleepDataReady == other.sleepDataReady &&
            overnightAutonomicSource == other.overnightAutonomicSource &&
            sleepDurationMinutes == other.sleepDurationMinutes &&
            nightlyRmssd == other.nightlyRmssd &&
            baselineReady == other.baselineReady &&
            recoveryAvailable == other.recoveryAvailable &&
            rawPpiGoodEpochCount == other.rawPpiGoodEpochCount &&
            rawPpiPoorEpochCount == other.rawPpiPoorEpochCount &&
            rawPpiCoverageHours == other.rawPpiCoverageHours &&
            summary == other.summary &&
            reasonsJson == other.reasonsJson

    private fun formatSignedMinutes(value: Int): String {
        val sign = if (value >= 0) "+" else "-"
        val absolute = kotlin.math.abs(value)
        return "$sign${absolute / 60}h ${absolute % 60}m"
    }

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
    val rangeNotes: String? = null,
    val block: suspend (Pair<LocalDate, LocalDate>) -> DomainPersistenceResult
)

private data class SyncDomainFailure(
    val domain: ProbeDomain,
    val error: Throwable
)

private data class DeviceFileListProbeDay(
    val date: String,
    val path: String,
    val listed: Boolean,
    val entryCount: Int,
    val activityEntryCount: Int,
    val dailySummaryEntryCount: Int,
    val activityEntries: List<String>,
    val dailySummaryEntries: List<String>,
    val entries: List<String>,
    val entriesTruncated: Boolean,
    val error: String?
)

private class DeviceDisconnectedDuringSyncException(domain: ProbeDomain) :
    IllegalStateException("${domain.name} sync interrupted: Loop connection was lost. Keep the phone near the Loop; Lodestone will retry.")

private const val SOFT_STORAGE_MAINTENANCE_USED_PERCENT = 70.0
private const val HARD_STORAGE_MAINTENANCE_USED_PERCENT = 85.0
private const val PPG_EXPERIMENT_RETENTION_HOURS = 6L
private const val SECONDARY_VALIDATION_RETENTION_DAYS = 14L
private const val OFFLINE_PPI_RETENTION_DAYS = 14L
private const val GENERIC_OFFLINE_RETENTION_DAYS = 14L
private const val DEVICE_STORED_DATA_RETENTION_DAYS = 14L
private const val RAW_PPI_RETENTION_DAYS = 21L
private const val CLOUD_BACKFILL_REQUESTED_RANGE_PREFIX = "cloud_backfill:"
private const val STALE_RUNNING_SYNC_AFTER_MS = 15 * 60 * 1000L
private const val MANUAL_SYNC_TIMEOUT_MS = 7 * 60 * 1000L
private const val SYNC_NOTIFICATION_START_ATTEMPTS = 5
private const val SYNC_NOTIFICATION_START_RETRY_DELAY_MS = 1_500L
private const val SYNC_NOTIFICATION_STOP_TIMEOUT_MS = 5_000L
private const val DATABASE_COMPACTION_THRESHOLD_BYTES = 300L * 1024L * 1024L
private const val ENABLE_ACTIVITY_SAMPLE_SYNC = true
private const val DEVICE_DATE_ROOT_PATH = "/U/0/"
private const val AUTOS_FILE_ROOT_PATH = "/U/0/AUTOS/"
private const val DEVICE_FILE_LIST_PROBE_MAX_DAYS = 31
private const val DEVICE_FILE_LIST_PROBE_ENTRY_LIMIT = 80
private const val SLEEP_SYNC_TIMEOUT_MS = 45_000L
private const val NIGHTLY_RECHARGE_SYNC_TIMEOUT_MS = 45_000L
private const val PPI_SYNC_TIMEOUT_MS = 4 * 60 * 1000L
private const val HR_SYNC_TIMEOUT_MS = 2 * 60 * 1000L
private const val SKIN_TEMPERATURE_SYNC_TIMEOUT_MS = 60_000L
private const val DAILY_SUMMARY_SYNC_TIMEOUT_MS = 60_000L
private const val ACTIVITY_SAMPLE_SYNC_TIMEOUT_MS = 2 * 60 * 1000L
private const val DEVICE_FILE_LIST_PROBE_TIMEOUT_MS = 2 * 60 * 1000L
private const val AUTOS_FILE_PROBE_TIMEOUT_MS = 3 * 60 * 1000L
private const val MANUAL_WAKE_BACKDATE_MS = 5 * 60_000L
private const val SLEEP_ONSET_WINDOW_EPOCHS = 4
private const val SLEEP_ONSET_MIN_HR_DROP_BPM = 3.0
private const val SLEEP_ONSET_MAX_HR_STD_BPM = 3.0
private const val SLEEP_ONSET_MAX_MOVEMENT_RATIO = 0.05
private const val MARKERLESS_ONSET_BASELINE_MS = 2 * 60 * 60_000L
private const val MIN_INFERRED_SLEEP_WINDOW_MS = 3 * 60 * 60_000L
private const val MIN_REST_CANDIDATE_WINDOW_MS = 20 * 60_000L
private const val PPI_REST_CANDIDATE_MAX_GAP_MS = 10 * 60_000L
private const val MANUAL_WAKE_OUTLIER_MS = 30 * 60_000L
private const val SLEEP_REPORT_DISAGREEMENT_MINUTES = 30
private const val RAW_PPI_REST_CANDIDATE_SOURCE = "raw_ppi_rest_candidate"
private const val MORNING_MODEL_VERSION = "morning_v1_primary_ppi_window_2026-05-15"
const val MORNING_PREDICTION_ORIGIN_OBSERVED = "OBSERVED_IN_APP"
private const val MORNING_PREDICTION_ORIGIN_BACKFILLED = "BACKFILLED_RECALCULATED"

// Model-v1 (current-state) — persistence + caution + capped confidence.
private const val CURRENT_STATE_MODEL_VERSION = "current_state_v1_persistence_caution_2026-06-13"
const val CURRENT_STATE_ORIGIN_OBSERVED = "OBSERVED_IN_APP"
private const val CURRENT_STATE_RECENT_OUTCOME_LOOKBACK_DAYS = 10
private val AUTOS_FILE_NAME_REGEX = Regex("""AUTOS\d{3}\.BPB""")

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
    val lateMinusEarlyRmssdMs: Double?,
    val trajectoryPoints: List<HrvTrajectoryPoint>
)

private data class SleepWindowEstimate(
    val startEpochMs: Long,
    val endEpochMs: Long,
    val source: String,
    val label: String,
    val hasExplicitWakeMarker: Boolean
) {
    val durationMinutes: Int
        get() = ((endEpochMs - startEpochMs) / 60_000L).toInt()
}

private data class SleepSearchBounds(
    val startEpochMs: Long,
    val endEpochMs: Long
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
