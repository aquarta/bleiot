package it.unisalento.bleiot

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.*

class BleViewModel : ViewModel() {
    private val TAG = "BleViewModel"
    private val SCAN_PERIOD: Long = 10000 // Scan for 10 seconds

    // BLE properties
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private val scannedDevices = mutableListOf<BluetoothDevice>()

    // Service and Characteristic UUIDs
    private val SERVICE_UUID = UUID.fromString("00000000-0001-11e1-9ab4-0002a5d5c51b") // MSSensorDemo Service
    private val BLUENRG_MS_ENV_CHARACTERISTIC_UUID = UUID.fromString("00140000-0001-11e1-ac36-0002a5d5c51b") // MSSensorDemo Characteristic
    private val BLUENRG_MS_ACC_CHARACTERISTIC_UUID = UUID.fromString("00c00000-0001-11e1-ac36-0002a5d5c51b")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val BLUENRG_MS_SERVICE_UUID = UUID.fromString("00000000-0001-11e1-9ab4-0002a5d5c51b") // MSSensorDemo Service
    private val HEAR_RATE_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB") // Heart Rate Service
    private val SERVICE_UUIDS = listOf(
        HEAR_RATE_SERVICE_UUID,
        BLUENRG_MS_SERVICE_UUID, // MSSensorDemo Service
    )

    private val HEART_RATE_CHARACTERISTIC_UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
    private val BATTERY_CHARACTERISTIC_UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")

    private val CHAR_UUIDS = listOf(
        HEART_RATE_CHARACTERISTIC_UUID,
        //BLUENRG_MS_ENV_CHARACTERISTIC_UUID,
        BLUENRG_MS_ACC_CHARACTERISTIC_UUID,
        BATTERY_CHARACTERISTIC_UUID,
    )

    // MQTT Client properties
    private var mqttClient: MqttClient? = null
    private val MQTT_SERVER_URI = "tcp://vmi2211704.contaboserver.net:1883"
    private val MQTT_CLIENT_ID = "AndroidBleClient"
    private val MQTT_TOPIC = "ble/temperature"

    // UI State
    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    // Service and context references
    private var bleAndMqttService: BleAndMqttService? = null
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext

        // Initialize Bluetooth
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            updateStatus("Bluetooth not supported")
        }
    }

    fun setService(service: BleAndMqttService) {
        bleAndMqttService = service

        // Set callback functions to update UI
        bleAndMqttService?.setCallbacks(
            statusCallback = { status ->
                updateStatus(status)
                // Update connected devices list from service
                updateConnectedDevices()
            },
            dataCallback = { data ->
                updateData(data)
            }
        )
    }

    fun onScanClicked() {
        if (!scanning) {
            // Clear the previously scanned devices first
            scannedDevices.clear()
            updateDevicesList()
            startScan()
        } else {
            stopScan()
        }
    }

    fun onDeviceClicked(device: BluetoothDevice) {
        connectToDevice(device)
    }

    fun startScan() {
        appContext?.let { context ->
            if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
                updateStatus("Bluetooth is disabled")
                return
            }

            // Check for permissions
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    updateStatus("Bluetooth scan permission denied")
                    return
                }
            }

            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                updateStatus("Cannot access Bluetooth scanner")
                return
            }

            // Stop scanning after a pre-defined period
            handler.postDelayed({
                if (scanning) {
                    scanning = false
                    if (ActivityCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_SCAN
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        bluetoothLeScanner?.stopScan(scanCallback)
                        updateScanButtonText("Start Scan")
                        updateStatus("Scan stopped")
                    }
                }
            }, SCAN_PERIOD)

            scanning = true
            bluetoothLeScanner?.startScan(scanCallback)
            updateScanButtonText("Stop Scan")
            updateStatus("Scanning...")
        }
    }

    fun stopScan() {
        appContext?.let { context ->
            if (scanning && bluetoothLeScanner != null) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_SCAN
                    ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                ) {
                    scanning = false
                    bluetoothLeScanner?.stopScan(scanCallback)
                    updateScanButtonText("Start Scan")
                    updateStatus("Scan stopped")
                }
            }
        }
    }

    // Device scan callback
    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            appContext?.let { context ->
                // Check for permissions
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        return
                    }
                }

                val device = result.device
                val deviceName = device.name ?: "Unknown Device"

                Log.i(TAG, "Found device: $deviceName ${device.address}")

                // Add device to list if it's not already there
                if (device.name != null && !scannedDevices.any { it.address == device.address }) {
                    scannedDevices.add(device)
                    updateDevicesList()
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            updateStatus("Scan failed: $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        appContext?.let { context ->
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                return
            }

            updateStatus("Connecting to ${device.name ?: "Unknown Device"}...")

            // Send connect command to service
            if (bleAndMqttService != null) {
                val deviceAddress = device.address
                val serviceIntent = Intent(context, BleAndMqttService::class.java).apply {
                    action = "CONNECT_BLE"
                    putExtra("deviceAddress", deviceAddress)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                // Update connected devices in UI state will be done by callback
            } else {
                updateStatus("Service not bound, cannot connect")
            }
        }
    }

    fun disconnectDevice(deviceAddress: String) {
        appContext?.let { context ->
            // Send disconnect command to service
            Log.e(TAG, "Start device disconnection view for $deviceAddress")
            if (bleAndMqttService != null) {
                val serviceIntent = Intent(context, BleAndMqttService::class.java).apply {
                    action = "DISCONNECT_BLE"
                    putExtra("deviceAddress", deviceAddress)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                updateStatus("Disconnecting from device $deviceAddress")
            } else {
                updateStatus("Service not bound, cannot disconnect")
            }
        }
    }

    fun disconnectAllDevices() {
        appContext?.let { context ->
            // Send disconnect command without device address to disconnect all
            Log.e(TAG, "Start disconnecting all devices")
            if (bleAndMqttService != null) {
                val serviceIntent = Intent(context, BleAndMqttService::class.java).apply {
                    action = "DISCONNECT_BLE"
                    // No deviceAddress = disconnect all
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                updateStatus("Disconnecting from all devices")
            } else {
                updateStatus("Service not bound, cannot disconnect")
            }
        }
    }

    // Example parser for Temperature data (adjust according to your device)
    private fun parseTemperatureDeta(data: ByteArray): Double {
        val tempValue = data.sliceArray(6 until data.size).foldIndexed(0) { index, acc, byte ->
            acc or ((byte.toInt() and 0xFF) shl (8 * index))
        }

        val temperature = (tempValue/10).toDouble()
        // Publish temperature to MQTT
        publishToMqtt(MQTT_TOPIC, temperature.toString())

        return temperature
    }

    private fun parseMSAccDeta(data: ByteArray): Int {
        val accValue = data.sliceArray(6 until data.size).foldIndexed(0) { index, acc, byte ->
            acc or ((byte.toInt() and 0xFF) shl (8 * index))
        }

        // Publish accelerometer to MQTT
        publishToMqtt(MQTT_TOPIC, accValue.toString())

        return accValue
    }

    private fun setupMqttClient() {
        try {
            // Create a new MqttClient instance
            mqttClient = MqttClient(
                MQTT_SERVER_URI,
                MQTT_CLIENT_ID,
                MemoryPersistence()
            )

            // Set up connection options
            val options = MqttConnectOptions()
            options.isAutomaticReconnect = true
            options.isCleanSession = true

            // Connect to the broker
            mqttClient?.connect(options)
            updateStatus("Connected to MQTT broker")

        } catch (e: MqttException) {
            Log.e(TAG, "Error setting up MQTT client: ${e.message}")
            updateStatus("MQTT connection failed: ${e.message}")
        }
    }

    private fun publishToMqtt(topic: String, message: String) {
        viewModelScope.launch {
            try {
                if (mqttClient?.isConnected == true) {
                    val mqttMessage = MqttMessage(message.toByteArray())
                    mqttMessage.qos = 1
                    mqttClient?.publish(topic, mqttMessage)
                    Log.i(TAG, "Published to MQTT: $message")
                } else {
                    Log.w(TAG, "MQTT client not connected, attempting to reconnect")
                    setupMqttClient()
                }
            } catch (e: MqttException) {
                Log.e(TAG, "Error publishing to MQTT: ${e.message}")
            }
        }
    }

    private fun updateConnectedDevices() {
        val connectedAddresses = bleAndMqttService?.getConnectedDeviceAddresses() ?: emptySet()
        _uiState.update { currentState ->
            currentState.copy(connectedDeviceAddresses = connectedAddresses)
        }
    }

    // Update UI state helpers
    fun updateStatus(status: String) {
        _uiState.update { currentState ->
            currentState.copy(statusText = status)
        }

    }

    private fun updateData(data: String) {
        _uiState.update { currentState ->
            currentState.copy(dataText = data)
        }
    }

    private fun updateScanButtonText(text: String) {
        _uiState.update { currentState ->
            currentState.copy(scanButtonText = text)
        }
    }

    private fun updateDevicesList() {
        appContext?.let { context ->
            _uiState.update { currentState ->
                currentState.copy(
                    devicesList = scannedDevices.map { device ->
                        // Check for permissions
                        if (ActivityCompat.checkSelfPermission(
                                context,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        ) {
                            BleDeviceInfo(
                                name = device.name ?: "Unknown Device",
                                address = device.address,
                                device = device
                            )
                        } else {
                            BleDeviceInfo(
                                name = "Unknown Device",
                                address = device.address,
                                device = device
                            )
                        }
                    }
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
        mqttClient?.disconnect()
        mqttClient?.close()
    }
}

// Data class to hold UI state
data class BleUiState(
    val statusText: String = "Not scanning",
    val dataText: String = "No data received",
    val scanButtonText: String = "Start Scan",
    val devicesList: List<BleDeviceInfo> = emptyList(),
    val connectedDeviceAddresses: Set<String> = emptySet()
)

// Data class to represent a Bluetooth device in the UI
data class BleDeviceInfo(
    val name: String,
    val address: String,
    val device: BluetoothDevice
)