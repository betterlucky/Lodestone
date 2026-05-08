@file:OptIn(ExperimentalLayoutApi::class)

package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.daveharris.healthmonitor.data.MorningReadSnapshot
import com.daveharris.healthmonitor.data.TrafficLightStatus
import kotlin.math.absoluteValue
import kotlinx.coroutines.launch

private enum class ProbeTab(val title: String) {
    DEVICE("Device"),
    DATA("Today"),
    FEEDBACK("Review")
}

@Composable
fun ProbeApp(
    viewModel: ProbeViewModel,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onImportFoodCsv: () -> Unit,
    onSetFoodFolder: () -> Unit,
    onRequestHealthConnectPermissions: () -> Unit,
    onOpenHealthConnectSettings: () -> Unit
) {
    val runtime by viewModel.runtimeState.collectAsState()
    val deviceProfile by viewModel.deviceProfile.collectAsState()
    val ftuProfile by viewModel.ftuProfile.collectAsState()
    val capabilities by viewModel.observedCapabilities.collectAsState()
    val appSettings by viewModel.appSettings.collectAsState()
    val dailyCheckIns by viewModel.dailyCheckIns.collectAsState()
    val foodDailySummaries by viewModel.foodDailySummaries.collectAsState()
    val dailyWeights by viewModel.dailyWeights.collectAsState()
    val morningRead by viewModel.morningRead.collectAsState()
    val syncRuns by viewModel.syncRuns.collectAsState()
    val recentWakeMarkers by viewModel.recentWakeMarkers.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(pageCount = { ProbeTab.entries.size })
    val pagerScope = rememberCoroutineScope()
    val selectedTab = pagerState.currentPage
    var showSettings by remember { mutableStateOf(false) }
    var blockPostSwipeTaps by remember { mutableStateOf(false) }
    var hasInitialPagerPage by remember { mutableStateOf(false) }
    val tabFlingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        snapPositionalThreshold = 0.36f
    )

    LaunchedEffect(viewModel.statusMessage) {
        viewModel.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(selectedTab) {
        if (hasInitialPagerPage) {
            blockPostSwipeTaps = true
            kotlinx.coroutines.delay(400)
            blockPostSwipeTaps = false
        } else {
            hasInitialPagerPage = true
        }
    }

    BackHandler(enabled = showSettings) {
        showSettings = false
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
                                onClick = {
                                    showSettings = false
                                    pagerScope.launch {
                                        pagerState.animateScrollToPage(index)
                                    }
                                },
                                icon = {
                                    when (tab) {
                                        ProbeTab.DEVICE -> Icon(Icons.Outlined.Bluetooth, contentDescription = null)
                                        ProbeTab.DATA -> Icon(Icons.Outlined.FavoriteBorder, contentDescription = null)
                                        ProbeTab.FEEDBACK -> Icon(Icons.Outlined.RateReview, contentDescription = null)
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
                    if (showSettings) {
                        SettingsScreen(
                            padding = padding,
                            runtime = runtime,
                            deviceProfile = deviceProfile,
                            ftuProfile = ftuProfile,
                            capabilities = capabilities,
                            appSettingsSummary = appSettings?.let {
                                "selected=${it.selectedDeviceId ?: "none"}, windows=${it.sleepDays}/${it.nightlyRechargeDays}/${it.hrDays}/${it.ppiDays}"
                            },
                            firmwareRediscoveryNeeded = viewModel.firmwareRediscoveryNeeded,
                            viewModel = viewModel,
                            onSetFoodFolder = onSetFoodFolder,
                            onRequestHealthConnectPermissions = onRequestHealthConnectPermissions,
                            onOpenHealthConnectSettings = onOpenHealthConnectSettings,
                            onClose = { showSettings = false }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            HorizontalPager(
                                state = pagerState,
                                modifier = Modifier.fillMaxSize(),
                                flingBehavior = tabFlingBehavior,
                                beyondViewportPageCount = 0
                            ) { page ->
                                val pageOffset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                                    .coerceIn(-1f, 1f)
                                ElasticPagerPage(pageOffset = pageOffset) {
                                    when (ProbeTab.entries[page]) {
                                        ProbeTab.DEVICE -> DeviceScreen(
                                            padding = padding,
                                            runtime = runtime,
                                            viewModel = viewModel,
                                            actionsEnabled = !blockPostSwipeTaps,
                                            onOpenSettings = { showSettings = true }
                                        )
                                        ProbeTab.DATA -> DataScreen(
                                            padding = padding,
                                            runtime = runtime,
                                            morningRead = morningRead,
                                            syncRuns = syncRuns,
                                            wakeMarkers = recentWakeMarkers,
                                            viewModel = viewModel,
                                            actionsEnabled = !blockPostSwipeTaps,
                                            onOpenSettings = { showSettings = true }
                                        )
                                        ProbeTab.FEEDBACK -> FeedbackScreen(
                                            padding = padding,
                                            morningRead = morningRead,
                                            dailyCheckIns = dailyCheckIns,
                                            foodDailySummaries = foodDailySummaries,
                                            dailyWeights = dailyWeights,
                                            viewModel = viewModel,
                                            onImportFoodCsv = onImportFoodCsv,
                                            actionsEnabled = !blockPostSwipeTaps,
                                            onOpenSettings = { showSettings = true }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ElasticPagerPage(
    pageOffset: Float,
    content: @Composable () -> Unit
) {
    val pull = pageOffset.absoluteValue.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val direction = if (pageOffset == 0f) 0f else pageOffset / pageOffset.absoluteValue
                cameraDistance = 18f * density
                rotationY = -direction * pull * 5.5f
                scaleX = 1f - (pull * 0.025f)
                scaleY = 1f - (pull * 0.012f)
                translationX = -direction * pull * 10f
                alpha = 1f - (pull * 0.05f)
                transformOrigin = TransformOrigin(
                    pivotFractionX = if (direction < 0f) 1f else 0f,
                    pivotFractionY = 0.5f
                )
            }
    ) {
        content()
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
            Text("Lodestone needs Bluetooth permissions before it can scan for or connect to the Loop.")
            SupportText("Once that’s granted, the normal sync and review flow will work as expected.")
            Button(onClick = onRequestPermissions) {
                Text("Grant Bluetooth permissions")
            }
        }
    }
}

@Composable
fun HeroCard(
    title: String,
    subtitle: String,
    eyebrow: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        eyebrow.uppercase(),
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.76f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    if (actionLabel != null && onAction != null) {
                        TextButton(onClick = onAction) {
                            if (actionLabel == "Settings") {
                                Icon(
                                    Icons.Outlined.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text(actionLabel, color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
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
fun SectionCard(
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
fun MorningReadCard(morningRead: MorningReadSnapshot) {
    if (morningRead.isInterim) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Morning signal", fontWeight = FontWeight.SemiBold)
                    StatusBadge("Pending", null)
                }
                Text(
                    "Morning data is still pending. Check back after Polar releases the sleep report.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val tone = statusTone(morningRead.status)
    var expanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = tone.copy(alpha = 0.10f)
        ),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.20f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Morning signal", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        morningRead.confidence.replaceFirstChar { it.titlecase() },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    StatusBadge(labelForStatus(morningRead.status?.name ?: "unknown"), morningRead.status)
                }
            }
            morningRead.reasons.take(2).forEach { reason ->
                Text("• $reason", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show details")
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DetailRow("Date", morningRead.sourceDate ?: "unknown")
                    DetailRow("Source", morningRead.overnightAutonomicSource)
                    DetailRow("Sleep", formatDurationMinutes(morningRead.sleepDurationMinutes))
                    DetailRow("RMSSD", morningRead.nightlyRmssd?.toInt()?.toString() ?: "n/a")
                    DetailRow("Raw PPI", "${morningRead.rawPpiGoodEpochCount ?: 0} good epochs")
                }
            }
        }
    }
}

@Composable
fun SectionLabel(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
fun SupportText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
fun BannerNote(text: String, tint: Color, textColor: Color) {
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
fun ButtonRow(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
fun DetailRow(label: String, value: String) {
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
fun StatusBadge(label: String, status: TrafficLightStatus?) {
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
fun statusTone(status: TrafficLightStatus?): Color =
    when (status) {
        TrafficLightStatus.GOOD -> Color(0xFF2E7D60)
        TrafficLightStatus.OK -> Color(0xFF2F63C8)
        TrafficLightStatus.UNSTEADY -> Color(0xFFB26A19)
        TrafficLightStatus.CRASH -> Color(0xFFAF2F4A)
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

fun labelForStatus(value: String): String =
    value.lowercase().replaceFirstChar { it.titlecase() }

fun formatDurationMinutes(minutes: Int?): String =
    minutes?.let { "${it / 60}h ${it % 60}m" } ?: "unknown"
