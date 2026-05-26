package com.daveharris.healthmonitor

import android.content.Context
import com.daveharris.healthmonitor.data.ProbeRepository
import com.daveharris.healthmonitor.data.SyncRunProfile
import com.daveharris.healthmonitor.data.SyncWindowConfig
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.LocalDate

class SyncCoordinator(
    private val appContext: Context,
    private val repository: ProbeRepository
) {
    private val syncMutex = Mutex()

    suspend fun runSync(
        deviceId: String,
        config: SyncWindowConfig,
        profile: SyncRunProfile = SyncRunProfile.FULL,
        scheduleMorningRetryIfNeeded: Boolean = false,
        cancelMorningRetryFirst: Boolean = false,
        wakeMarkerNotes: String? = null,
        morningReadGuard: MorningReadGuard? = null
    ): SyncCoordinatorResult = syncMutex.withLock {
        if (cancelMorningRetryFirst) {
            MorningReadScheduler.cancel(appContext)
        }
        morningReadGuard?.ensureCurrent(appContext)
        val targetDate = LocalDate.now().toString()

        var connectedId = deviceId
        var syncRunId: Long? = null
        try {
            connectedId = connectAndAwait(deviceId)
            if (wakeMarkerNotes != null) {
                repository.recordWakeMarker(
                    sourceDate = targetDate,
                    deviceId = connectedId,
                    notes = wakeMarkerNotes
                )
            }

            syncRunId = repository.runManualSync(connectedId, config, profile).getOrThrow()
            val recoveryResult = if (profile.needsMorningPpi()) {
                recoverMorningPpiIfNeeded(
                    deviceId = connectedId,
                    config = config,
                    targetDate = targetDate,
                    currentRunId = syncRunId,
                    morningReadGuard = morningReadGuard
                )
            } else {
                null
            }
            if (recoveryResult != null) {
                connectedId = recoveryResult.connectedDeviceId
                syncRunId = recoveryResult.syncRunId
            }
            return@withLock SyncCoordinatorResult(
                connectedDeviceId = connectedId,
                syncRunId = requireNotNull(syncRunId),
                recoveredMorningPpi = recoveryResult?.recoveredMorningPpi == true
            )
        } finally {
            // A failed first wake sync should still leave the app with a recovery path.
            if (scheduleMorningRetryIfNeeded) {
                scheduleMorningReadCheckIfNeeded(connectedId)
            }
        }
    }

    suspend fun <T> runExclusiveDeviceOperation(
        deviceId: String,
        block: suspend (connectedDeviceId: String) -> T
    ): T = syncMutex.withLock {
        val connectedId = connectAndAwait(deviceId)
        block(connectedId)
    }

    private suspend fun connectAndAwait(
        deviceId: String,
        connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
        readyTimeoutMs: Long = DEVICE_READY_TIMEOUT_MS
    ): String {
        repository.connect(deviceId)
        withTimeout(connectTimeoutMs) {
            repository.runtimeState.first { runtime ->
                runtime.matchesConnectedDevice(deviceId)
            }
        }
        withTimeout(readyTimeoutMs) {
            repository.runtimeState.first { runtime ->
                runtime.matchesConnectedDevice(deviceId) &&
                    (
                        runtime.firmwareVersion != null ||
                            runtime.readyFeatures.isNotEmpty() ||
                            runtime.unavailableFeatures.isNotEmpty()
                        )
            }
        }
        return repository.runtimeState.value.connectedDevice?.deviceId ?: deviceId
    }

    private suspend fun recoverMorningPpiIfNeeded(
        deviceId: String,
        config: SyncWindowConfig,
        targetDate: String,
        currentRunId: Long,
        morningReadGuard: MorningReadGuard?
    ): SyncCoordinatorResult? {
        if (repository.hasPpiRecordForDate(targetDate)) return null

        var connectedId = deviceId
        var latestRunId = currentRunId
        repeat(MORNING_PPI_RECOVERY_ATTEMPTS) { attempt ->
            morningReadGuard?.ensureCurrent(appContext)
            if (!repository.runtimeState.value.matchesConnectedDevice(connectedId)) {
                delay(
                    if (attempt == 0) {
                        MORNING_PPI_FIRST_RECOVERY_DELAY_MS
                    } else {
                        MORNING_PPI_RECOVERY_RETRY_DELAY_MS
                    }
                )
            }
            val reconnectedId = try {
                connectAndAwait(
                    deviceId = connectedId,
                    connectTimeoutMs = RECOVERY_CONNECT_TIMEOUT_MS,
                    readyTimeoutMs = RECOVERY_READY_TIMEOUT_MS
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return@repeat
            }
            connectedId = reconnectedId
            delay(MORNING_PPI_RECOVERY_SETTLE_MS)
            latestRunId = try {
                repository.runManualSync(
                    deviceId = connectedId,
                    config = config,
                    profile = SyncRunProfile.MORNING_PPI_RETRY
                ).getOrThrow()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                return@repeat
            }
            if (repository.hasPpiRecordForDate(targetDate)) {
                return SyncCoordinatorResult(
                    connectedDeviceId = connectedId,
                    syncRunId = latestRunId,
                    recoveredMorningPpi = true
                )
            }
        }
        return SyncCoordinatorResult(
            connectedDeviceId = connectedId,
            syncRunId = latestRunId
        )
    }

    private suspend fun scheduleMorningReadCheckIfNeeded(deviceId: String) {
        val targetDate = LocalDate.now().toString()
        if (repository.hasSleepRecordForDate(targetDate)) {
            MorningReadScheduler.cancel(appContext)
        } else if (repository.hasPpiRecordForDate(targetDate)) {
            MorningReadScheduler.scheduleSleepReportRetry(appContext, targetDate, deviceId)
        } else {
            MorningReadScheduler.schedulePpiRetry(appContext, targetDate, deviceId)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val DEVICE_READY_TIMEOUT_MS = 20_000L
        private const val RECOVERY_CONNECT_TIMEOUT_MS = 25_000L
        private const val RECOVERY_READY_TIMEOUT_MS = 10_000L
        private const val MORNING_PPI_RECOVERY_ATTEMPTS = 2
        private const val MORNING_PPI_FIRST_RECOVERY_DELAY_MS = 5_000L
        private const val MORNING_PPI_RECOVERY_RETRY_DELAY_MS = 20_000L
        private const val MORNING_PPI_RECOVERY_SETTLE_MS = 3_000L
    }
}

data class SyncCoordinatorResult(
    val connectedDeviceId: String,
    val syncRunId: Long,
    val recoveredMorningPpi: Boolean = false
)

private fun SyncRunProfile.needsMorningPpi(): Boolean =
    this == SyncRunProfile.MORNING_CORE || this == SyncRunProfile.MORNING_PPI_RETRY

private fun DeviceRuntimeState.matchesConnectedDevice(deviceId: String): Boolean {
    val device = connectedDevice
    return connectionPhase == "connected" &&
        (
            device?.deviceId.equals(deviceId, ignoreCase = true) ||
                device?.address.equals(deviceId, ignoreCase = true)
            )
}

data class MorningReadGuard(
    val targetDate: String,
    val generation: Long,
    val stage: MorningRetryStage? = null
) {
    fun ensureCurrent(context: Context) {
        val isCurrent = if (stage == null) {
            MorningReadScheduler.isCurrentCheck(context, targetDate, generation)
        } else {
            MorningReadScheduler.isCurrentCheck(context, targetDate, generation, stage)
        }
        if (!isCurrent) {
            throw StaleMorningReadCheckException(targetDate)
        }
    }
}

class StaleMorningReadCheckException(targetDate: String) :
    IllegalStateException("Stale morning read check for $targetDate")
