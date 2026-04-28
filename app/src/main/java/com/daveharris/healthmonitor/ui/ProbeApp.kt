@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.DailyCheckInEntity
import com.daveharris.healthmonitor.data.DailyWeightEntity
import com.daveharris.healthmonitor.data.DeviceProfileEntity
import com.daveharris.healthmonitor.data.FoodDailySummaryEntity
import com.daveharris.healthmonitor.data.FtuProfileEntity
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.ObservedCapabilityEntity
import com.daveharris.healthmonitor.data.OfflinePpiNightSummary
import com.daveharris.healthmonitor.data.SyncDomainResultEntity
import com.daveharris.healthmonitor.data.SyncRunEntity
import com.daveharris.healthmonitor.data.TrafficLightStatus
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import com.polar.sdk.api.model.PolarDeviceInfo
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private enum class ProbeTab(val title: String) {
    DEVICE("Device"),
    OVERNIGHT("Overnight"),
    SYNC("Sync"),
    FEEDBACK("Review")
}

@Composable
fun ProbeApp(
    viewModel: ProbeViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onImportFoodCsv: () -> Unit,
    onSetFoodFolder: () -> Unit
) {
    val runtime by viewModel.runtimeState.collectAsState()
    val deviceProfile by viewModel.deviceProfile.collectAsState()
    val ftuProfile by viewModel.ftuProfile.collectAsState()
    val capabilities by viewModel.observedCapabilities.collectAsState()
    val syncRuns by viewModel.syncRuns.collectAsState()
    val syncDomainResults by viewModel.syncDomainResults.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val dailyCheckIns by viewModel.dailyCheckIns.collectAsState()
    val morningRead by viewModel.morningRead.collectAsState()
    val latestOfflinePpiNightSummary by viewModel.latestOfflinePpiNightSummary.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(viewModel.statusMessage) {
        viewModel.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    HealthMonitorTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 10.dp
                    ) {
                        ProbeTab.entries.forEachIndexed { index, tab ->
                            NavigationBarItem(
                                selected = selectedTab == index,
                                onClick = { selectedTab = index },
                                icon = {
                                    when (tab) {
                                        ProbeTab.DEVICE -> Icon(Icons.Outlined.Bluetooth, contentDescription = null)
                                        ProbeTab.OVERNIGHT -> Icon(Icons.Outlined.Refresh, contentDescription = null)
                                        ProbeTab.SYNC -> Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                                        ProbeTab.FEEDBACK -> Icon(Icons.Outlined.Refresh, contentDescription = null)
                                    }
                                },
                                label = { Text(tab.title) }
                            )
                        }
                    }
                }
            ) { padding ->
                if (!permissionsGranted) {
                    MissingPermissionsScreen(padding, onRequestPermissions)
                } else {
                    when (ProbeTab.entries[selectedTab]) {
                        ProbeTab.DEVICE -> DeviceScreen(
                            padding = padding,
                            runtime = runtime,
                            deviceProfile = deviceProfile,
                            ftuProfile = ftuProfile,
                            capabilities = capabilities,
                            appSettingsSummary = appSettings?.let {
                                "selected=${it.selectedDeviceId ?: "none"}, windows=${it.sleepDays}/${it.nightlyRechargeDays}/${it.hrDays}/${it.ppiDays}"
                            },
                            firmwareRediscoveryNeeded = viewModel.firmwareRediscoveryNeeded,
                            viewModel = viewModel
                        )
                        ProbeTab.OVERNIGHT -> OvernightScreen(
                            padding = padding,
                            runtime = runtime,
                            syncRuns = syncRuns,
                            syncDomainResults = syncDomainResults,
                            latestPpiNightSummary = latestOfflinePpiNightSummary,
                            viewModel = viewModel
                        )
                        ProbeTab.SYNC -> SyncScreen(
                            padding = padding,
                            runtime = runtime,
                            syncRuns = syncRuns,
                            syncDomainResults = syncDomainResults,
                            viewModel = viewModel
                        )
                        ProbeTab.FEEDBACK -> FeedbackScreen(
                            padding = padding,
                            morningRead = morningRead,
                            latestPpiNightSummary = latestOfflinePpiNightSummary,
                            dailyCheckIns = dailyCheckIns,
                            viewModel = viewModel,
                            onImportFoodCsv = onImportFoodCsv
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MissingPermissionsScreen(padding: PaddingValues, onRequestPermissions: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        SectionCard(title = "Bluetooth access needed", subtitle = "One-time setup") {
            Text("Health Monitor needs Bluetooth permissions before it can scan for or connect to the Loop.")
            SupportText("Once that’s granted, the normal sync and review flow will work as expected.")
            Button(onClick = onRequestPermissions) {
                Text("Grant Bluetooth permissions")
            }
        }
    }
}

@Composable
private fun DeviceScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    deviceProfile: DeviceProfileEntity?,
    ftuProfile: FtuProfileEntity?,
    capabilities: List<ObservedCapabilityEntity>,
    appSettingsSummary: String?,
    firmwareRediscoveryNeeded: Boolean,
    viewModel: ProbeViewModel
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroCard(
                title = "Health Monitor",
                subtitle = "A calmer daily home for Loop connection, manual sync, and end-of-day review.",
                eyebrow = "Device"
            )
        }
        item {
            SectionCard(title = "Connection", subtitle = "Daily essentials") {
                BoxWithConstraints {
                    if (maxWidth < 380.dp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            KeyMetricPill("Bluetooth", if (runtime.bluetoothPowered) "On" else "Off")
                            KeyMetricPill("Phase", runtime.connectionPhase.replaceFirstChar { it.titlecase() })
                            KeyMetricPill("Battery", runtime.batteryLevel?.let { "$it%" } ?: "Unknown")
                            KeyMetricPill("Charging", runtime.chargingState.name.replace('_', ' '))
                        }
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            KeyMetricPill("Bluetooth", if (runtime.bluetoothPowered) "On" else "Off")
                            KeyMetricPill("Phase", runtime.connectionPhase.replaceFirstChar { it.titlecase() })
                            KeyMetricPill("Battery", runtime.batteryLevel?.let { "$it%" } ?: "Unknown")
                            KeyMetricPill("Charging", runtime.chargingState.name.replace('_', ' '))
                        }
                    }
                }
                DetailRow("Selected device", viewModel.selectedDeviceId ?: "None")
                DetailRow("Connected", runtime.connectedDevice?.name ?: "None")
                DetailRow("Firmware", runtime.firmwareVersion ?: "Unknown")
                DetailRow("Saved sync profile", appSettingsSummary ?: "none")
                if (runtime.connectedDevice == null || runtime.connectionPhase == "connecting") {
                    BannerNote(
                        text = "Connection tip: Android does not let Health Monitor disable Polar Flow's Bluetooth session automatically. If the Loop is missing or connection stalls, close Polar Flow or disable Flow's Bluetooth/device access, then try Connect again.",
                        tint = MaterialTheme.colorScheme.secondaryContainer,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                if (!runtime.lastError.isNullOrBlank()) {
                    BannerNote(
                        text = runtime.lastError,
                        tint = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                ButtonRow {
                    Button(onClick = viewModel::scanDevices, enabled = !viewModel.isBusy) { Text("Scan") }
                    Button(onClick = viewModel::connectSelectedDevice, enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null) { Text("Connect") }
                    OutlinedButton(onClick = viewModel::disconnectSelectedDevice, enabled = !viewModel.isBusy) { Text("Disconnect") }
                }
            }
        }
        item {
            SectionCard(title = "Setup & repair", subtitle = "Only when needed") {
                SupportText("Most days you can ignore this section. Use it when pairing a device, refreshing capabilities after firmware changes, or checking stored device state.")
                if (firmwareRediscoveryNeeded) {
                    BannerNote(
                        text = "Firmware appears to have changed since the last stored settings. Refresh capabilities before trusting sync results.",
                        tint = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                ButtonRow {
                    Button(onClick = viewModel::discoverCapabilities, enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null) {
                        Text("Refresh capabilities")
                    }
                    OutlinedButton(onClick = viewModel::refreshFtuStatus, enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null) {
                        Text("Refresh FTU")
                    }
                    OutlinedButton(onClick = viewModel::exportInspectorData, enabled = !viewModel.isBusy) {
                        Text("Export JSON")
                    }
                }
                DetailRow("FTU complete", "${ftuProfile?.isCompleted ?: false}")
                DetailRow("Last known device state", ftuProfile?.lastKnownDeviceState ?: "none")
                DetailRow("Saved profile device", deviceProfile?.deviceId ?: "none")
                if (capabilities.isNotEmpty()) {
                    SectionLabel("Recent capability snapshot")
                    capabilities.take(4).forEach { capability ->
                        DetailRow(capability.domain, capability.status)
                    }
                }
            }
        }
        item { SectionLabel("Discovered devices") }
        itemsIndexed(runtime.scannedDevices, key = { index, device -> "${device.deviceId}-$index" }) { _, device ->
            DeviceRow(
                device = device,
                selected = viewModel.selectedDeviceId == device.deviceId,
                onSelect = { viewModel.selectDevice(device.deviceId) }
            )
        }
    }
}

@Composable
private fun SyncScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    syncRuns: List<SyncRunEntity>,
    syncDomainResults: List<SyncDomainResultEntity>,
    viewModel: ProbeViewModel
) {
    val config = viewModel.syncWindowConfig
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroCard(
                title = "Refresh Data",
                subtitle = "Pull in the latest Loop data after waking or anytime you want the app caught up.",
                eyebrow = "Sync"
            )
        }
        item {
            SectionCard(title = "Manual sync", subtitle = "Current sync window") {
                SupportText("Partial results are kept even if one domain arrives late, so it’s usually worth syncing even after a rough night.")
                NumericField("Sleep days", config.sleepDays.toString()) { viewModel.updateSyncDays(sleepDays = it.toIntOrNull() ?: config.sleepDays) }
                NumericField("Nightly recharge days", config.nightlyRechargeDays.toString()) { viewModel.updateSyncDays(nightlyRechargeDays = it.toIntOrNull() ?: config.nightlyRechargeDays) }
                NumericField("24/7 HR days", config.hrDays.toString()) { viewModel.updateSyncDays(hrDays = it.toIntOrNull() ?: config.hrDays) }
                NumericField("24/7 PPi days", config.ppiDays.toString()) { viewModel.updateSyncDays(ppiDays = it.toIntOrNull() ?: config.ppiDays) }
                ButtonRow {
                    Button(onClick = viewModel::runManualSync, enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null) { Text("Sync now") }
                    OutlinedButton(onClick = viewModel::exportInspectorData, enabled = !viewModel.isBusy) { Text("Export JSON") }
                    KeyMetricPill("Connection", if (runtime.connectedDevice != null) "Live" else "Not connected")
                }
            }
        }
        item { SectionLabel("Recent sync runs") }
        itemsIndexed(syncRuns.take(6), key = { index, run -> "sync-run-${run.id}-${run.startedAtEpochMs}-$index" }) { _, run ->
            DataCard(
                title = "Run #${run.id}",
                headline = run.status.replace('_', ' ').replaceFirstChar { it.titlecase() }
            ) {
                DetailRow("Started", formatEpochMs(run.startedAtEpochMs))
                DetailRow("Ended", run.endedAtEpochMs?.let(::formatEpochMs) ?: "running")
                DetailRow("Notes", run.notes ?: "none")
            }
        }
        item { SectionLabel("Latest domain results") }
        itemsIndexed(
            items = syncDomainResults.take(12),
            key = { index, result ->
                "sync-domain-${result.id}-${result.syncRunId}-${result.domain}-${result.startedAtEpochMs}-$index"
            }
        ) { _, result ->
            DataCard(
                title = result.domain,
                headline = result.status.replace('_', ' ').replaceFirstChar { it.titlecase() }
            ) {
                DetailRow("Requested range", result.requestedRange)
                DetailRow("Records", result.recordCount.toString())
                DetailRow("Parse status", result.parseStatus)
                DetailRow("Details", result.detailSummary)
                if (!result.errorMessage.isNullOrBlank()) {
                    DetailRow("Error", result.errorMessage)
                }
            }
        }
    }
}

@Composable
private fun OvernightScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    syncRuns: List<SyncRunEntity>,
    syncDomainResults: List<SyncDomainResultEntity>,
    latestPpiNightSummary: OfflinePpiNightSummary?,
    viewModel: ProbeViewModel
) {
    val latestOffline = syncDomainResults.firstOrNull { it.domain == "OFFLINE_RECORDING" }
    val latestStart = syncRuns
        .filter { it.notes?.contains("normal offline recording smoke start PPI") == true }
        .maxByOrNull { it.startedAtEpochMs }
    val latestCompleted = syncRuns
        .filter { it.notes?.contains("normal offline recording smoke completed: type=PPI") == true }
        .maxByOrNull { it.endedAtEpochMs ?: it.startedAtEpochMs }
    val likelyRunning = latestStart != null &&
        latestStart.status == "running" &&
        (latestCompleted?.endedAtEpochMs ?: 0L) < latestStart.startedAtEpochMs

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroCard(
                title = "Overnight PPI",
                subtitle = "Record raw overnight pulse intervals while keeping the normal Loop sleep and Nightly Recharge path.",
                eyebrow = "Raw HRV"
            )
        }
        item {
            SectionCard(title = "Tonight", subtitle = "Manual controls") {
                SupportText("Use Start when you want to begin recording. Use I’m Awake to stop, fetch PPI, and run the normal morning sync.")
                DetailRow("Selected device", viewModel.selectedDeviceId ?: "None")
                DetailRow("Connection", if (runtime.connectedDevice != null) "Live" else "Not connected")
                DetailRow("Likely recording", if (likelyRunning) "Yes" else "No")
                ButtonRow {
                    Button(
                        onClick = viewModel::startOvernightPpiNow,
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Start PPI now")
                    }
                    Button(
                        onClick = viewModel::markAwakeAndFetchOvernightPpi,
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("I’m awake")
                    }
                }
            }
        }
        item {
            SectionCard(title = "Scheduled start", subtitle = "Optional broad-window trigger") {
                SupportText("Use 24-hour local time. Android may run this within a short window rather than exactly on the minute.")
                OutlinedTextField(
                    value = viewModel.overnightStartTimeDraft,
                    onValueChange = viewModel::updateOvernightStartTime,
                    label = { Text("Start time") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                DetailRow("Enabled", if (viewModel.scheduledStartEnabled) "Yes" else "No")
                DetailRow("Next start", viewModel.nextScheduledStartEpochMs?.let(::formatEpochMs) ?: "not scheduled")
                ButtonRow {
                    Button(
                        onClick = { viewModel.updateScheduledStartEnabled(true) },
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Enable start")
                    }
                    OutlinedButton(onClick = { viewModel.updateScheduledStartEnabled(false) }, enabled = !viewModel.isBusy) {
                        Text("Disable start")
                    }
                }
            }
        }
        item {
            SectionCard(title = "Scheduled stop", subtitle = "Optional automatic morning fetch") {
                SupportText("This is useful for routine nights. If your sleep is irregular, leave this off and use I’m Awake instead.")
                OutlinedTextField(
                    value = viewModel.overnightStopTimeDraft,
                    onValueChange = viewModel::updateOvernightStopTime,
                    label = { Text("Stop/fetch time") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                DetailRow("Enabled", if (viewModel.scheduledStopEnabled) "Yes" else "No")
                DetailRow("Next stop", viewModel.nextScheduledStopEpochMs?.let(::formatEpochMs) ?: "not scheduled")
                ButtonRow {
                    Button(
                        onClick = { viewModel.updateScheduledStopEnabled(true) },
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Enable stop")
                    }
                    OutlinedButton(onClick = { viewModel.updateScheduledStopEnabled(false) }, enabled = !viewModel.isBusy) {
                        Text("Disable stop")
                    }
                }
            }
        }
        item {
            SectionCard(title = "Latest PPI result", subtitle = "Most recent offline recording outcome") {
                if (latestOffline == null) {
                    SupportText("No offline PPI result has been recorded yet.")
                } else {
                    DetailRow("Status", latestOffline.status)
                    DetailRow("Requested", latestOffline.requestedRange)
                    DetailRow("Records", latestOffline.recordCount.toString())
                    DetailRow("Details", latestOffline.detailSummary)
                    if (!latestOffline.errorMessage.isNullOrBlank()) {
                        DetailRow("Error", latestOffline.errorMessage)
                    }
                }
            }
        }
        item {
            SectionCard(title = "Latest overnight PPI summary", subtitle = "5-minute raw-PPI trajectory rollup") {
                if (latestPpiNightSummary == null) {
                    SupportText("No normalized overnight PPI epochs are available yet.")
                } else {
                    OfflinePpiSummaryCard(latestPpiNightSummary)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FeedbackScreen(
    padding: PaddingValues,
    morningRead: MorningReadSnapshot?,
    latestPpiNightSummary: OfflinePpiNightSummary?,
    dailyCheckIns: List<DailyCheckInEntity>,
    viewModel: ProbeViewModel,
    onImportFoodCsv: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            HeroCard(
                title = "Day Review",
                subtitle = "Use the bedtime outcome as the main truth, then add how you approached the day if that helps explain the result.",
                eyebrow = "Review"
            )
        }
        item {
            SectionCard(title = "Today's note", subtitle = "Morning context") {
                MorningDataQualityCard(
                    morningRead = morningRead,
                    latestPpiNightSummary = latestPpiNightSummary
                )
                if (morningRead != null) {
                    MorningReadCard(morningRead)
                } else {
                    BannerNote(
                        text = "No morning read is available yet. You can still record how the day ended.",
                        tint = MaterialTheme.colorScheme.secondaryContainer,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                ReviewDatePickerField(
                    selectedDate = viewModel.checkInDate,
                    hasSavedReview = dailyCheckIns.any { it.sourceDate == viewModel.checkInDate },
                    hasFoodImport = viewModel.currentFoodSummary != null || viewModel.currentDailyWeight != null,
                    onDateSelected = viewModel::updateCheckInDate
                )
                SectionLabel("Food")
                if (viewModel.currentFoodSummary != null || viewModel.currentDailyWeight != null) {
                    FoodSummaryCard(
                        summary = viewModel.currentFoodSummary,
                        weight = viewModel.currentDailyWeight
                    )
                } else {
                    BannerNote(
                        text = "No food log has been synced for this date yet.",
                        tint = MaterialTheme.colorScheme.secondaryContainer,
                        textColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                ButtonRow {
                    Button(onClick = viewModel::importLatestFoodCsvFromDownloads, enabled = !viewModel.isBusy) {
                        Text("Sync food log")
                    }
                    OutlinedButton(onClick = onImportFoodCsv, enabled = !viewModel.isBusy) {
                        Text("Choose file")
                    }
                }
                SupportText("Sync imports the FoodLogData CSV for the selected date. Choose file remains the fallback.")
                SectionLabel("How did the day actually end?")
                StatusChipRow(
                    selected = viewModel.eveningOutcomeDraft,
                    onSelect = viewModel::setEveningOutcome,
                    onClear = { viewModel.setEveningOutcome(null) }
                )
                SupportText(feedbackCopyFor(viewModel.eveningOutcomeDraft))
                MuscleWeaknessToggle(
                    checked = viewModel.muscleWeaknessTodayDraft,
                    onCheckedChange = viewModel::updateMuscleWeaknessToday
                )
                SectionLabel("How did you approach the day? Optional.")
                StatusChipRow(
                    selected = viewModel.approachToDayDraft,
                    onSelect = { selected ->
                        viewModel.setApproachToDay(
                            if (viewModel.approachToDayDraft == selected) null else selected
                        )
                    },
                    onClear = { viewModel.setApproachToDay(null) }
                )
                SupportText(approachCopyFor(viewModel.approachToDayDraft))
                SectionLabel("Notes")
                NotesField(
                    value = viewModel.notesDraft,
                    onValueChange = viewModel::updateNotesDraft
                )
                ButtonRow {
                    Button(
                        onClick = viewModel::saveDailyCheckIn,
                        enabled = !viewModel.isBusy && viewModel.eveningOutcomeDraft != null
                    ) {
                        Text("Save ${viewModel.checkInDate}")
                    }
                    OutlinedButton(onClick = viewModel::resetSelectedReviewDate, enabled = !viewModel.isBusy) {
                        Text("Reset")
                    }
                }
            }
        }
        item { SectionLabel("Recent reviews") }
        items(
            items = dailyCheckIns,
            key = { item -> "check-in-${item.sourceDate}-${item.updatedAtEpochMs}" }
        ) { checkIn ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.loadDailyCheckIn(checkIn.sourceDate) },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f)
                )
            ) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(checkIn.sourceDate, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    StatusLine("Outcome", checkIn.eveningOutcome)
                    DetailRow("Approach", checkIn.approachToDay?.let(::labelForStatus) ?: "Not recorded")
                    DetailRow("Muscle weakness", if (checkIn.muscleWeaknessToday) "Yes" else "No")
                    if (!checkIn.notes.isNullOrBlank()) {
                        DetailRow("Notes", checkIn.notes)
                    }
                    DetailRow("Updated", formatEpochMs(checkIn.updatedAtEpochMs))
                    SupportText("Tap to load this review")
                }
            }
        }
    }
}

@Composable
private fun ReviewDatePickerField(
    selectedDate: String,
    hasSavedReview: Boolean,
    hasFoodImport: Boolean,
    onDateSelected: (String) -> Unit
) {
    var showPicker by remember { mutableStateOf(false) }
    val selectedMillis = remember(selectedDate) {
        runCatching {
            java.time.LocalDate.parse(selectedDate)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }
    val status = listOf(
        if (hasSavedReview) "saved review" else "no saved review",
                if (hasFoodImport) "import synced" else "no import"
    ).joinToString(" · ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showPicker = true },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text("Review date", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                Text(selectedDate, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                SupportText(status)
            }
            OutlinedButton(onClick = { showPicker = true }) {
                Text("Change")
            }
        }
    }

    if (showPicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedMillis,
            initialDisplayMode = DisplayMode.Picker
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val date = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString()
                            onDateSelected(date)
                        }
                        showPicker = false
                    }
                ) {
                    Text("Use date")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun DeviceRow(device: PolarDeviceInfo, selected: Boolean, onSelect: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            }
        ),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)) else null
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(device.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                if (selected) {
                    KeyMetricPill("Selected", "Ready")
                }
            }
            DetailRow("ID", device.deviceId)
            DetailRow("Signal", "RSSI ${device.rssi}")
            DetailRow("Connectable", device.isConnectable.toString())
            DetailRow("File system", device.hasFileSystemService.toString())
        }
    }
}

@Composable
private fun HeroCard(title: String, subtitle: String, eyebrow: String) {
    Card(
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.secondary
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    eyebrow.uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                Text(
                    title,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun DataCard(
    title: String,
    headline: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            SupportText(headline)
            content()
        }
    }
}

@Composable
private fun MorningDataQualityCard(
    morningRead: MorningReadSnapshot?,
    latestPpiNightSummary: OfflinePpiNightSummary?
) {
    val ready = morningRead != null && !morningRead.isInterim
    val tone = when {
        ready -> MaterialTheme.colorScheme.primary
        morningRead?.isInterim == true -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = tone.copy(alpha = 0.13f)
        ),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.24f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Morning data quality", fontWeight = FontWeight.SemiBold)
                    SupportText("Whether today’s read has enough overnight data behind it")
                }
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            when {
                                ready -> "Ready"
                                morningRead?.isInterim == true -> "Interim"
                                else -> "Missing"
                            }
                        )
                    }
                )
            }
            DetailRow("Sleep report", if (morningRead?.sleepDataReady == true) "Present" else "Waiting")
            DetailRow("Autonomic lane", morningRead?.overnightAutonomicSource ?: "none")
            val ppiQuality = when {
                morningRead?.offlinePpiGoodEpochCount != null -> {
                    val coverage = morningRead.offlinePpiCoverageHours?.let {
                        String.format(java.util.Locale.UK, ", %.1fh", it)
                    }.orEmpty()
                    "${morningRead.offlinePpiGoodEpochCount} good epochs$coverage"
                }
                latestPpiNightSummary != null -> "Recorded, waiting for sleep-window alignment"
                else -> "No overnight PPI yet"
            }
            DetailRow("Offline PPI", ppiQuality)
            if ((morningRead?.offlinePpiPoorEpochCount ?: 0) > 0) {
                DetailRow("Flagged PPI windows", morningRead?.offlinePpiPoorEpochCount.toString())
            }
            SupportText(
                when {
                    ready -> "Ready to use for today’s provisional guidance."
                    morningRead?.isInterim == true -> "The app will keep checking until Polar releases the sleep report."
                    else -> "Run sync or stop/fetch overnight PPI to populate today’s read."
                }
            )
        }
    }
}

@Composable
private fun MorningReadCard(morningRead: MorningReadSnapshot) {
    val tone = statusTone(morningRead.status)
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = tone.copy(alpha = 0.14f)
        ),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.24f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (morningRead.isInterim) "Interim morning read" else "Latest morning read",
                        fontWeight = FontWeight.SemiBold
                    )
                    SupportText(
                        if (morningRead.isInterim) {
                            "Waiting for Polar to release the sleep report"
                        } else {
                            "A quick anchor before you score the day"
                        }
                    )
                }
                if (morningRead.isInterim) {
                    StatusBadge("Interim", null)
                } else {
                    StatusBadge(labelForStatus(morningRead.status?.name ?: "unknown"), morningRead.status)
                }
            }
            DetailRow("Confidence", morningRead.confidence.replaceFirstChar { it.titlecase() })
            DetailRow("Date", morningRead.sourceDate ?: "unknown")
            DetailRow("Source", morningRead.overnightAutonomicSource)
            DetailRow("Sleep", formatDurationMinutes(morningRead.sleepDurationMinutes))
            DetailRow("RMSSD", morningRead.nightlyRmssd?.toInt()?.toString() ?: "n/a")
            morningRead.reasons.take(3).forEach { reason ->
                SupportText("• $reason")
            }
        }
    }
}

@Composable
private fun FoodSummaryCard(
    summary: FoodDailySummaryEntity?,
    weight: DailyWeightEntity?
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Food for this date", fontWeight = FontWeight.SemiBold)
            if (summary != null) {
                DetailRow("Calories", summary.totalCaloriesKcal?.toString() ?: "n/a")
                DetailRow("Events", summary.eventCount?.toString() ?: "n/a")
                DetailRow("Tea", summary.teaCount?.toString() ?: "n/a")
                DetailRow("First intake", summary.firstIntakeTime ?: "n/a")
                DetailRow("Last intake", summary.lastIntakeTime ?: "n/a")
                DetailRow("Eating window", summary.eatingWindowHours?.let { String.format(java.util.Locale.UK, "%.1f h", it) } ?: "n/a")
            }
            if (weight != null) {
                DetailRow("Weight", String.format(java.util.Locale.UK, "%.1f kg", weight.weightKg))
            }
        }
    }
}

@Composable
private fun OfflinePpiSummaryCard(summary: OfflinePpiNightSummary) {
    val usablePercent = if (summary.sampleCount > 0) {
        (summary.usableSampleCount.toDouble() / summary.sampleCount.toDouble()) * 100.0
    } else {
        null
    }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(summary.sourceDate, fontWeight = FontWeight.SemiBold)
            DetailRow("Window", "${summary.firstEpochStartEpochMs?.let(::formatTimeOnly) ?: "?"} → ${summary.lastEpochEndEpochMs?.let(::formatTimeOnly) ?: "?"}")
            DetailRow("Epochs", summary.epochCount.toString())
            DetailRow("Usable beats", "${summary.usableSampleCount} / ${summary.sampleCount}${usablePercent?.let { String.format(java.util.Locale.UK, " (%.0f%%)", it) } ?: ""}")
            DetailRow("Avg RMSSD", summary.averageRmssdMs?.let { String.format(java.util.Locale.UK, "%.1f ms", it) } ?: "n/a")
            DetailRow("Peak RMSSD", summary.maxRmssdMs?.let { String.format(java.util.Locale.UK, "%.1f ms", it) } ?: "n/a")
            DetailRow("Avg HR", summary.averageHrBpm?.let { String.format(java.util.Locale.UK, "%.1f bpm", it) } ?: "n/a")
            DetailRow(
                "Quality",
                "good ${summary.goodEpochCount}, usable ${summary.usableEpochCount}, review ${summary.reviewEpochCount}, poor ${summary.poorEpochCount}"
            )
        }
    }
}

@Composable
private fun MuscleWeaknessToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.34f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Did you feel muscle weakness today?", fontWeight = FontWeight.SemiBold)
                SupportText("Optional marker for distinct weakness episodes, separate from fatigue or brain fog.")
            }
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
private fun NumericField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@Composable
private fun NotesField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = value,
        onValueChange = onValueChange,
        label = { Text("Anything notable about the day?") },
        minLines = 3,
        maxLines = 5,
        shape = RoundedCornerShape(18.dp)
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun StatusChipRow(
    selected: TrafficLightStatus?,
    onSelect: (TrafficLightStatus) -> Unit,
    onClear: (() -> Unit)? = null
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TrafficLightStatus.entries.forEach { status ->
            FilterChip(
                selected = selected == status,
                onClick = { onSelect(status) },
                label = { Text(labelForStatus(status.name)) }
            )
        }
        if (onClear != null) {
            OutlinedButton(onClick = onClear) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun SupportText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun BannerNote(text: String, tint: Color, textColor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(tint)
            .padding(12.dp)
    ) {
        Text(text, color = textColor)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ButtonRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun KeyMetricPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f))
        Text(value, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp)
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    val parsedStatus = value.toTrafficLightStatusOrNull()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StatusBadge(parsedStatus?.let { labelForStatus(it.name) } ?: labelForStatus(value), parsedStatus)
    }
}

@Composable
private fun StatusBadge(label: String, status: TrafficLightStatus?) {
    val tone = statusTone(status)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(tone.copy(alpha = 0.14f))
            .border(1.dp, tone.copy(alpha = 0.24f), RoundedCornerShape(100.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = tone, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun statusTone(status: TrafficLightStatus?): Color =
    when (status) {
        TrafficLightStatus.GOOD -> Color(0xFF2E7D60)
        TrafficLightStatus.OK -> Color(0xFF2F63C8)
        TrafficLightStatus.UNSTEADY -> Color(0xFFB26A19)
        TrafficLightStatus.CRASH -> Color(0xFFAF2F4A)
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun labelForStatus(value: String): String =
    value.lowercase().replaceFirstChar { it.titlecase() }

private fun String.toTrafficLightStatusOrNull(): TrafficLightStatus? =
    runCatching { TrafficLightStatus.valueOf(this) }.getOrNull()

private fun feedbackCopyFor(status: TrafficLightStatus?): String =
    when (status) {
        null -> "Select the outcome before saving. This should be the day-end truth label."
        TrafficLightStatus.GOOD -> "Good: capacity felt broadly normal for you, without obvious warning signs."
        TrafficLightStatus.OK -> "OK: manageable, but with some need for care or adaptation."
        TrafficLightStatus.UNSTEADY -> "Unsteady: you were up and functioning, but the margin felt thin or unstable."
        TrafficLightStatus.CRASH -> "Crash: clearly beyond safe capacity, or a day dominated by symptoms and pullback."
    }

private fun approachCopyFor(status: TrafficLightStatus?): String =
    when (status) {
        null -> "Leave this blank if you only want to capture how the day ended."
        TrafficLightStatus.GOOD -> "You treated the day as high-capacity and spent energy fairly freely."
        TrafficLightStatus.OK -> "You did things, but with some pacing or caution."
        TrafficLightStatus.UNSTEADY -> "You kept the day light, essential, or deliberately limited."
        TrafficLightStatus.CRASH -> "You treated the day as a crash-management day."
    }

private fun formatEpochMs(epochMs: Long): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

private fun formatTimeOnly(epochMs: Long): String =
    DateTimeFormatter.ofPattern("HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))

private fun formatDurationMinutes(minutes: Int?): String =
    minutes?.let { "${it / 60}h ${it % 60}m" } ?: "unknown"
