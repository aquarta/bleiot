package it.unisalento.bleiot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import dagger.hilt.android.AndroidEntryPoint
import it.unisalento.bleiot.ui.theme.BleNotificationTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: BleViewModel by viewModels()
    @Inject lateinit var deviceConfigManager: DeviceConfigurationManager

    // Request for Bluetooth permissions
    private val requestMultiplePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            // All permissions granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    requestBackgroundLocationPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    viewModel.startScan()
                }
            } else {
                viewModel.startScan()
            }
        } else {
            viewModel.updateStatus("Permissions denied")
        }
    }

    private val requestBackgroundLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startScan()
        } else {
            viewModel.updateStatus("Background location permission denied")
        }
    }

    // Service binding
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as BleAndMqttService.LocalBinder
            val bleService = binder.getService()
            viewModel.setService(bleService)
            serviceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize device configuration early
        initializeDeviceConfiguration()

        // Start and bind to the service
        startAndBindService()

        setContent {
            BleNotificationTheme {
                MainScreenWithMenu(
                    uiState = viewModel.uiState,
                    onScanButtonClick = {
                        if (viewModel.uiState.value.scanButtonText == "Start Scan" || viewModel.uiState.value.scanButtonText == "Inizia Esperimento") {
                            checkPermissionsAndStartScan()
                        } else {
                            viewModel.stopScan()
                        }
                    },
                    onDeviceClick = { device ->
                        viewModel.onDeviceClicked(device)
                    },
                    onDisconnectClick = { deviceAddress ->
                        viewModel.disconnectDevice(deviceAddress)
                    },
                    onSettingsClick = {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    },
                    viewModel = viewModel
                )
            }
        }
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, BleAndMqttService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onResume() {
        super.onResume()

        // Check if Bluetooth is enabled
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            } else {
                checkPermissionsAndStartScan()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun checkPermissionsAndStartScan() {
        val permissionsToRequest = mutableListOf<String>()

        // For Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        // For Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        // For Android 10+ (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // For older Android versions
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            //permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            //permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // Request permissions if needed
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissions.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.startScan()
        }
    }

    private fun initializeDeviceConfiguration() {
        try {
            val existingConfig = deviceConfigManager.getDeviceConfiguration()

            if (existingConfig == null) {
                // Create default configuration if none exists
                createDefaultConfiguration(deviceConfigManager)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error initializing device configuration: ${e.message}")
        }
    }

    private fun createDefaultConfiguration(deviceConfigManager: DeviceConfigurationManager) {
        val defaultConfig = DeviceConfiguration(
            devices = mapOf(
                "STEVAL-STWINKT1B" to DeviceInfo(
                    name = "STEVAL-STWINKT1B",
                    shortName = "STWIN",
                    services = listOf(
                        ServiceInfo(
                            uuid = "00000000-0001-11e1-9ab4-0002a5d5c51b",
                            name = "MSSensorDemo Service",
                            characteristics = listOf(
                                CharacteristicInfo(
                                    uuid = "00140000-0001-11e1-ac36-0002a5d5c51b",
                                    name = "Temperature Data",
                                    dataType = "custom_temperature",
                                    mqttTopic = "ble/temperature",
                                    structParser = null
                                ),
                                CharacteristicInfo(
                                    uuid = "001c0000-0001-11e1-ac36-0002a5d5c51b",
                                    name = "Battery Data",
                                    dataType = "STBatteryStruct",
                                    mqttTopic = "ble/battery",
                                    structParser = null
                                )
                            )
                        )
                    )
                )
            ),
            dataTypes = mapOf(
                "custom_temperature" to DataTypeInfo(
                    size = "variable",
                    conversion = "custom",
                    description = "Temperature data with custom parsing"
                ),
                "STBatteryStruct" to DataTypeInfo(
                    size = "9_bytes",
                    conversion = "struct",
                    description = "ST Battery information structure"
                )
            )
        )

        deviceConfigManager.saveConfiguration(defaultConfig)
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
    }
}