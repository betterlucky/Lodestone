@file:OptIn(ExperimentalLayoutApi::class)

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                DetailRow("Firmware", runtime.firmwareVersion ?: "Unknown")
                if (runtime.connectedDevice == null || runtime.connectionPhase == "connecting") {
                    BannerNote(
                        text = "Connection tip: Android does not let Lodestone disable Polar Flow's Bluetooth session automatically. If the Loop is missing or connection stalls, close Polar Flow or disable Flow's Bluetooth/device access, then try Connect again.",
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
    onImportSleep2Screenshot: () -> Unit,
    onRequestHealthConnectPermissions: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit,
    onClose: () -> Unit
) {
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
                subtitle = "Configuration, Flow handoff, and repair controls that should not clutter the daily flow.",
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
                ButtonRow {
                    Button(onClick = onSetFoodFolder, enabled = !viewModel.isBusy) {
                        Text("Set FoodLogData folder")
                    }
                    OutlinedButton(
                        onClick = viewModel::prepareForPolarFlowUpdate,
                        enabled = !viewModel.isBusy && viewModel.selectedDeviceId != null
                    ) {
                        Text("Prepare for Flow")
                    }
                }
                SupportText("Flow remains useful for firmware and occasional sleep finalisation. Prepare disconnects Lodestone so Flow can take the Loop cleanly.")
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
