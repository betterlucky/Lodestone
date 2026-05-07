package com.daveharris.healthmonitor

import android.content.Context
import com.daveharris.healthmonitor.data.ProbeRepository
import com.daveharris.healthmonitor.data.SyncRunProfile
import com.daveharris.healthmonitor.data.SyncWindowConfig
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
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

        val connectedId = connectAndAwait(deviceId)
        if (wakeMarkerNotes != null) {
            repository.recordWakeMarker(
                sourceDate = LocalDate.now().toString(),
                deviceId = connectedId,
                notes = wakeMarkerNotes
            )
        }

        val runId = repository.runManualSync(connectedId, config, profile).getOrThrow()

        if (scheduleMorningRetryIfNeeded) {
            scheduleMorningReadCheckIfNeeded(connectedId)
        }

        return@withLock SyncCoordinatorResult(connectedId, runId)
    }

    suspend fun <T> runExclusiveDeviceOperation(
        deviceId: String,
        block: suspend (connectedDeviceId: String) -> T
    ): T = syncMutex.withLock {
        val connectedId = connectAndAwait(deviceId)
        block(connectedId)
    }

    private suspend fun connectAndAwait(deviceId: String): String {
        fun DeviceRuntimeState.matchesConnectedDevice(): Boolean {
            val device = connectedDevice
            return connectionPhase == "connected" &&
                (
                    device?.deviceId.equals(deviceId, ignoreCase = true) ||
                        device?.address.equals(deviceId, ignoreCase = true)
                    )
        }

        repository.connect(deviceId)
        withTimeout(CONNECT_TIMEOUT_MS) {
            repository.runtimeState.first { runtime ->
                runtime.matchesConnectedDevice()
            }
        }
        withTimeout(DEVICE_READY_TIMEOUT_MS) {
            repository.runtimeState.first { runtime ->
                runtime.matchesConnectedDevice() &&
                    (
                        runtime.firmwareVersion != null ||
                            runtime.readyFeatures.isNotEmpty() ||
                            runtime.unavailableFeatures.isNotEmpty()
                        )
            }
        }
        return repository.runtimeState.value.connectedDevice?.deviceId ?: deviceId
    }

    private suspend fun scheduleMorningReadCheckIfNeeded(deviceId: String) {
        val targetDate = LocalDate.now().toString()
        if (repository.hasSleepRecordForDate(targetDate)) {
            MorningReadScheduler.cancel(appContext)
        } else {
            MorningReadScheduler.scheduleNextCheck(appContext, targetDate, deviceId)
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 45_000L
        private const val DEVICE_READY_TIMEOUT_MS = 20_000L
    }
}

data class SyncCoordinatorResult(
    val connectedDeviceId: String,
    val syncRunId: Long
)

data class MorningReadGuard(
    val targetDate: String,
    val generation: Long
) {
    fun ensureCurrent(context: Context) {
        if (!MorningReadScheduler.isCurrentCheck(context, targetDate, generation)) {
            throw StaleMorningReadCheckException(targetDate)
        }
    }
}

class StaleMorningReadCheckException(targetDate: String) :
    IllegalStateException("Stale morning read check for $targetDate")
