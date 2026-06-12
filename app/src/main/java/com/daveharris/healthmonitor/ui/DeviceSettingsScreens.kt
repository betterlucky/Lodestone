@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.daveharris.healthmonitor.data.DeviceProfileEntity
import com.daveharris.healthmonitor.data.FtuProfileEntity
import com.daveharris.healthmonitor.data.ObservedCapabilityEntity
import com.daveharris.healthmonitor.polar.DeviceRuntimeState
import com.polar.sdk.api.model.PolarDeviceInfo
import kotlinx.coroutines.delay

@Composable
fun DeviceScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    viewModel: ProbeViewModel,
    actionsEnabled: Boolean,
    onOpenSettings: () -> Unit
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
                title = "Lodestone",
                subtitle = "A daily pacing compass for Loop connection, morning signals, and end-of-day review.",
                eyebrow = "Device",
                actionLabel = "Settings",
                onAction = onOpenSettings
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
                DetailRow("Runtime firmware", runtime.firmwareVersion ?: "Unknown")
                if (runtime.connectedDevice == null || runtime.connectionPhase == "connecting") {
                    BannerNote(
                        text = "Connection tip: Android does not let Lodestone disable Polar Flow's Bluetooth session automatically. Keep Flow closed during normal collection; if the Loop is missing or connection stalls, close Flow or disable Flow's Bluetooth/device access, then try Connect again.",
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
                    Button(onClick = { if (actionsEnabled) viewModel.scanDevices() }, enabled = !viewModel.isBusy) {
                        Text("Scan")
                    }
                    Button(
                        onClick = { if (actionsEnabled) viewModel.connectSelectedDevice() },
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Connect")
                    }
                    OutlinedButton(
                        onClick = { if (actionsEnabled) viewModel.disconnectSelectedDevice() },
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }
        item { SectionLabel("Discovered devices") }
        if (runtime.scannedDevices.isEmpty()) {
            item {
                BannerNote(
                    text = "No devices listed yet. Tap Scan above, then choose your Loop when it appears.",
                    tint = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
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
fun SettingsScreen(
    padding: PaddingValues,
    runtime: DeviceRuntimeState,
    deviceProfile: DeviceProfileEntity?,
    ftuProfile: FtuProfileEntity?,
    capabilities: List<ObservedCapabilityEntity>,
    appSettingsSummary: String?,
    firmwareRediscoveryNeeded: Boolean,
    viewModel: ProbeViewModel,
    onSetFoodFolder: () -> Unit,
    onSetGripFolder: () -> Unit,
    onImportSleep2Screenshot: () -> Unit,
    onRequestHealthConnectPermissions: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    onClose: () -> Unit
) {
    val morningRead by viewModel.morningRead.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    var cooldownTicker by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(viewModel.sleepReportRetryCooldownUntilEpochMs) {
        while (viewModel.sleepReportRetryCooldownUntilEpochMs > System.currentTimeMillis()) {
            delay(30_000L)
            cooldownTicker = System.currentTimeMillis()
        }
        cooldownTicker = System.currentTimeMillis()
    }
    cooldownTicker
    val today = viewModel.currentLodestoneDate()
    val todayMorningRead = morningRead?.takeIf { it.sourceDate == today }
    val finalSleepReportPresent = todayMorningRead?.sleepDataReady == true
    val ppiPresent = todayMorningRead?.rawPpiGoodEpochCount != null ||
        todayMorningRead?.overnightAutonomicSource?.contains("ppi", ignoreCase = true) == true
    val sleepRetryCooldown = viewModel.sleepReportRetryCooldownLabel()
    var showJournalFocusTimePicker by remember { mutableStateOf(false) }
    if (showJournalFocusTimePicker) {
        val focusMinutes = viewModel.journalFocusFixedTimeMinutes.coerceIn(0, 23 * 60 + 59)
        val pickerState = rememberTimePickerState(
            initialHour = focusMinutes / 60,
            initialMinute = focusMinutes % 60,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { showJournalFocusTimePicker = false },
            title = { Text("Journal focus time") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateJournalFocusFixedTime(pickerState.hour, pickerState.minute)
                        showJournalFocusTimePicker = false
                    }
                ) {
                    Text("Set")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJournalFocusTimePicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeroCard(
                title = "Settings & tools",
                subtitle = "Configuration, controlled maintenance, and repair controls that should not clutter the daily flow.",
                eyebrow = "Settings",
                actionLabel = "Done",
                onAction = onClose
            )
        }
        item {
            SectionCard(title = "Daily setup", subtitle = "Things that shape the normal ritual") {
                DetailRow("Selected device", viewModel.selectedDeviceId ?: "None")
                DetailRow("Connected now", runtime.connectedDevice?.name ?: "None")
                DetailRow("Saved sync profile", appSettingsSummary ?: "none")
                DetailRow("Marker mode", viewModel.markerMode.settingsLabel())
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    NowMarkerMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = viewModel.markerMode == mode,
                            onClick = { viewModel.updateMarkerMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = NowMarkerMode.entries.size
                            ),
                            enabled = !viewModel.isBusy
                        ) {
                            Text(mode.shortSettingsLabel())
                        }
                    }
                }
                DetailRow("Journal focus", viewModel.journalFocusMode.settingsLabel())
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    NowJournalFocusMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = viewModel.journalFocusMode == mode,
                            onClick = { viewModel.updateJournalFocusMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = NowJournalFocusMode.entries.size
                            ),
                            enabled = !viewModel.isBusy
                        ) {
                            Text(mode.settingsLabel())
                        }
                    }
                }
                if (viewModel.journalFocusMode == NowJournalFocusMode.FIXED_TIME) {
                    DetailRow("Focus time", viewModel.journalFocusFixedTimeMinutes.timeOfDayLabel())
                    OutlinedButton(
                        onClick = { showJournalFocusTimePicker = true },
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Choose focus time")
                    }
                }
                ButtonRow {
                    Button(onClick = onSetFoodFolder, enabled = !viewModel.isBusy) {
                        Text("Set FoodLogData folder")
                    }
                    Button(onClick = onSetGripFolder, enabled = !viewModel.isBusy) {
                        Text("Set GripRecorderData folder")
                    }
                    OutlinedButton(
                        onClick = viewModel::prepareForPolarFlowUpdate,
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Release to Flow")
                    }
                }
                SupportText("Use Flow only for controlled maintenance or recovery. It can affect what data remains available locally; after using it, run Lodestone sync and review data completeness before trusting affected days.")
            }
        }
        item {
            SectionCard(title = "Loop device", subtitle = "Scan, select, and hand off the Loop from Settings") {
                DetailRow("Bluetooth", if (runtime.bluetoothPowered) "On" else "Off")
                DetailRow("Connection", runtime.connectionPhase.replaceFirstChar { it.titlecase() })
                DetailRow("Battery", runtime.batteryLevel?.let { "$it%" } ?: "Unknown")
                DetailRow("Selected", viewModel.selectedDeviceId ?: "None")
                DetailRow("Connected", runtime.connectedDevice?.name ?: "None")
                DetailRow("Runtime firmware", runtime.firmwareVersion ?: "Unknown")
                runtime.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    BannerNote(
                        text = error,
                        tint = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                ButtonRow {
                    Button(onClick = viewModel::scanDevices, enabled = !viewModel.isBusy) {
                        Text("Scan")
                    }
                    Button(
                        onClick = viewModel::connectSelectedDevice,
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Connect")
                    }
                    OutlinedButton(
                        onClick = viewModel::disconnectSelectedDevice,
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Disconnect")
                    }
                }
                SupportText("If Polar Flow is holding the Loop connection, close Flow before connecting here. Firmware values are source-specific local observations, not a check against Flow or public release pages.")
            }
        }
        if (runtime.scannedDevices.isEmpty()) {
            item {
                BannerNote(
                    text = "No Loop devices listed yet. Tap Scan above, then choose your Loop here when it appears.",
                    tint = MaterialTheme.colorScheme.secondaryContainer,
                    textColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        } else {
            item { SectionLabel("Discovered Loop devices") }
            itemsIndexed(runtime.scannedDevices, key = { index, device -> "settings-${device.deviceId}-$index" }) { _, device ->
                DeviceRow(
                    device = device,
                    selected = viewModel.selectedDeviceId == device.deviceId,
                    onSelect = { viewModel.selectDevice(device.deviceId) }
                )
            }
        }
        item {
            SectionCard(title = "Morning repair", subtitle = "Non-standard tools for stubborn sleep reports") {
                DetailRow("Today", today)
                DetailRow("PPI received", if (ppiPresent) "Yes" else "Not yet")
                DetailRow("Final sleep report", if (finalSleepReportPresent) "Present" else "Pending")
                sleepRetryCooldown?.let { DetailRow("Sleep retry", it) }
                ButtonRow {
                    OutlinedButton(
                        onClick = viewModel::retryFinalSleepReport,
                        enabled = !viewModel.isBusy &&
                            viewModel.selectedDeviceId != null &&
                            ppiPresent &&
                            !finalSleepReportPresent &&
                            sleepRetryCooldown == null
                    ) {
                        Text("Retry sleep report")
                    }
                }
                SupportText("Use this after the automatic checks have given up. It only fetches Sleep and Nightly Recharge, then locks itself for 30 minutes.")
            }
        }
        item {
            SectionCard(title = "Analysis exports", subtitle = "Temporary calibration data kept out of the main Lodestone database") {
                DetailRow("Calibration date", viewModel.checkInDate)
                DetailRow("Health Connect access", if (viewModel.healthConnectPermissionsGranted) "Granted" else "Not granted")
                viewModel.lastSleep2ScreenshotPath?.let { path ->
                    DetailRow("Latest Sleep2 screenshot", path)
                }
                ButtonRow {
                    Button(
                        onClick = onImportSleep2Screenshot,
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Import Sleep2 screenshot")
                    }
                    Button(
                        onClick = onRequestHealthConnectPermissions,
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Grant Health Connect")
                    }
                    OutlinedButton(
                        onClick = onOpenHealthConnectSettings,
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Open HC settings")
                    }
                    OutlinedButton(
                        onClick = viewModel::exportHealthConnectSleepAnalysis,
                        enabled = !viewModel.isBusy && viewModel.healthConnectPermissionsGranted
                    ) {
                        Text("Export HC sleep")
                    }
                }
                SupportText("Sleep2 screenshots are copied to app files as sleep2-statistics-YYYY-MM-DD.png using the selected review date. Health Connect exports remain analysis-only; neither path writes to Lodestone's production database.")
            }
        }
        item {
            SectionCard(title = "Sync windows", subtitle = "How much history Lodestone asks the Loop for") {
                val config = viewModel.syncWindowConfig
                DetailRow("Sleep / Nightly Recharge", "${config.sleepDays}d / ${config.nightlyRechargeDays}d")
                DetailRow("HR / PPI", "${config.hrDays}d / ${config.ppiDays}d")
                ButtonRow {
                    Button(
                        onClick = {
                            viewModel.updateSyncDays(
                                sleepDays = 7,
                                nightlyRechargeDays = 7,
                                hrDays = 3,
                                ppiDays = 3
                            )
                        },
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Normal")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.updateSyncDays(
                                sleepDays = 14,
                                nightlyRechargeDays = 14,
                                hrDays = 7,
                                ppiDays = 7
                            )
                        },
                        enabled = !viewModel.isBusy
                    ) {
                        Text("Extended")
                    }
                }
                SupportText("Normal is lighter for daily use. Extended is useful after missed syncs or data investigations.")
            }
        }
        item {
            SectionCard(title = "Loop setup & repair", subtitle = "Rare controls for firmware changes, factory reset, or investigation") {
                if (firmwareRediscoveryNeeded) {
                    BannerNote(
                        text = "Runtime firmware appears to differ from the saved selected-device firmware. Refresh capabilities before trusting sync results.",
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
                DetailRow("Saved profile firmware", deviceProfile?.firmwareVersion ?: "none")
                DetailRow("Saved selected-device firmware", appSettings?.lastKnownFirmwareBySelectedDevice ?: "none")
                SupportText("Firmware values here are source-specific local observations. Polar Flow and public release pages remain separate manual references.")
                if (capabilities.isNotEmpty()) {
                    SectionLabel("Recent capability snapshot")
                    capabilities.take(4).forEach { capability ->
                        DetailRow(capability.domain, capability.status)
                    }
                }
            }
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
private fun KeyMetricPill(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f)
        )
        Text(
            value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun NowMarkerMode.shortSettingsLabel(): String =
    when (this) {
        NowMarkerMode.NO_MARKERS -> "None"
        NowMarkerMode.BEDTIME -> "Bedtime"
        NowMarkerMode.BEDTIME_AND_WAKING -> "Bed + wake"
    }
