package com.daveharris.healthmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
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
