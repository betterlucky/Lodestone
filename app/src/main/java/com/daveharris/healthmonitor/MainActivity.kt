package com.daveharris.healthmonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.core.content.ContextCompat
import com.daveharris.healthmonitor.health.HealthConnectAnalysisExporter
import androidx.lifecycle.viewmodel.compose.viewModel
import com.daveharris.healthmonitor.ui.ProbeApp
import com.daveharris.healthmonitor.ui.ProbeViewModel

class MainActivity : ComponentActivity() {
    private var pendingAutomationAction by mutableStateOf<String?>(null)
    private var pendingAutomationDeviceId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureAutomationIntent(intent)
        enableEdgeToEdge()
        setContent {
            val vm: ProbeViewModel = viewModel(factory = ProbeViewModel.Factory)
            var permissionsGranted by remember { mutableStateOf(hasBluetoothPermissions()) }
            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { result ->
                permissionsGranted = result.values.all { it }
            }
            val foodCsvLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    vm.importFoodCsv(uri)
                }
            }
            val foodFolderLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri: Uri? ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    vm.saveFoodFolder(uri)
                }
            }
            val healthConnectPermissionLauncher = rememberLauncherForActivityResult(
                contract = HealthConnectAnalysisExporter.requestPermissionContract()
            ) { granted ->
                vm.handleHealthConnectPermissionResult(granted)
            }
            LaunchedEffect(Unit) {
                if (!permissionsGranted) {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                }
            }

            LaunchedEffect(permissionsGranted, pendingAutomationAction, pendingAutomationDeviceId) {
                val action = pendingAutomationAction
                if (permissionsGranted && action != null) {
                    vm.runAutomationCommand(action, pendingAutomationDeviceId)
                    pendingAutomationAction = null
                    pendingAutomationDeviceId = null
                }
            }

            ProbeApp(
                viewModel = vm,
                permissionsGranted = permissionsGranted,
                onRequestPermissions = {
                    launcher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    )
                },
                onImportFoodCsv = {
                    foodCsvLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel"))
                },
                onSetFoodFolder = {
                    foodFolderLauncher.launch(null)
                },
                onRequestHealthConnectPermissions = {
                    healthConnectPermissionLauncher.launch(HealthConnectAnalysisExporter.REQUIRED_PERMISSIONS)
                },
                onOpenHealthConnectSettings = {
                    openHealthConnectSettings()
                    vm.notifyHealthConnectSettingsOpened()
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureAutomationIntent(intent)
    }

    private fun hasBluetoothPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun captureAutomationIntent(intent: Intent?) {
        pendingAutomationAction = intent?.getStringExtra(EXTRA_AUTOMATION_ACTION)
        pendingAutomationDeviceId = intent?.getStringExtra(EXTRA_AUTOMATION_DEVICE_ID)
    }

    private fun openHealthConnectSettings() {
        val intent = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        runCatching { startActivity(intent) }
            .recoverCatching {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
    }

    companion object {
        const val EXTRA_AUTOMATION_ACTION = "automation_action"
        const val EXTRA_AUTOMATION_DEVICE_ID = "automation_device_id"
    }
}
