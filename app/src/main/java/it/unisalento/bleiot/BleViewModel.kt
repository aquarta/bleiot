package it.unisalento.bleiot

import android.Manifest
import android.app.Application
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.copy
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.*
import kotlinx.coroutines.flow.receiveAsFlow

class BleViewModel : ViewModel() {
    private val TAG = "BleViewModel"
    private val SCAN_PERIOD: Long = 10000 // Scan for 10 seconds

    // BLE properties
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    // Map address -> Device Info
    private val scannedDevicesMap = mutableMapOf<String, BleDeviceInfoTrans>()



    // MQTT Client properties
    private var mqttClient: MqttClient? = null
    private val MQTT_SERVER_URI = "tcp://broker.hivemq.com:1883"
    private val MQTT_CLIENT_ID = "AndroidBleClient"
    private val MQTT_TOPIC = "ble/temperature"

    // UI State
    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()
    // A channel to buffer and throttle UI updates
    private val uiUpdateTrigger = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

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
        // --- ADD THIS: Register the receiver here ---
        val filter = IntentFilter().apply {
            addAction(BleAndMqttService.ACTION_CHARACTERISTIC_FOUND)
            addAction(BleAndMqttService.ACTION_WHITEBOARD_FOUND)
            //putExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS, "address_placeholder") // Just to access constants safely if needed
        }
        // Use the appContext we just captured
        //appContext?.registerReceiver(characteristicReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        appContext?.registerReceiver(characteristicReceiver, filter, Context.RECEIVER_EXPORTED)
        appContext?.registerReceiver(whiteboardReceiver, filter, Context.RECEIVER_EXPORTED)
        // Note: Context.RECEIVER_NOT_EXPORTED is recommended for Android 14+
        // Launch a coroutine to handle UI updates smoothly
        viewModelScope.launch(Dispatchers.Main) {
            // receiveAsFlow combined with delay acts as a rate limiter
            uiUpdateTrigger.receiveAsFlow().collect {
                updateDevicesList()
                // Wait 300ms before allowing the next UI update.
                // Any updates arriving during this time are conflated (merged).
                delay(300)
            }
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

            // Clear the previously scanned devices first, but keep connected ones
            val connectedAddresses = uiState.value.connectedDeviceAddresses

            // 1. Identify keys (addresses) that should be kept
            val keysToKeep = scannedDevicesMap.keys.filter { it in connectedAddresses }.toSet()

            // 2. Remove everything else
            scannedDevicesMap.keys.retainAll(keysToKeep)

            // 3. Trigger UI update
            uiUpdateTrigger.trySend(Unit)


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
                val scannedDeviceTrans = BleDeviceInfoTrans(
                    name = device.name ?: "Unknown Device",
                    address = device.address,
                    device = device
                )
                val deviceName = device.name ?: "Unknown Device"

                Log.i(TAG, "Found device: $deviceName ${device.address}")

                // Add device to list if it's not already there
                if (device.name != null && !scannedDevicesMap.containsKey(device.address)) {
                    scannedDevicesMap[device.address] = scannedDeviceTrans

                    // CHANGE THIS:
                    // updateDevicesList()// TO THIS:
                    uiUpdateTrigger.trySend(Unit)
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
            // 1. Check permission ONCE, outside the loop
            val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            // 2. Create the new list efficiently
            // toList() creates a shallow copy which is safe for the UI state
            val currentScannedDevices = scannedDevicesMap.values.toList()

            _uiState.update { currentState ->
                currentState.copy(
                    devicesList = currentScannedDevices.map { deviceTrans ->
                        BleDeviceInfo(
                            name = if (hasConnectPermission) deviceTrans.name else "Unknown Device",
                            address = deviceTrans.address,
                            deviceT = deviceTrans
                        )
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

        // Safe unregister
        try {
            appContext?.unregisterReceiver(characteristicReceiver)
            appContext?.unregisterReceiver(whiteboardReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Receiver was not registered")
        }
    }


    private val whiteboardReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BleAndMqttService.ACTION_WHITEBOARD_FOUND) {
                val address = intent.getStringExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS)
                val whiteboardName = intent.getStringExtra(BleAndMqttService.EXTRA_WHITEBOARD)

                if (address != null && whiteboardName != null) {
                    // Force Main thread to ensure list safety if scannedDevices isn't thread-safe
                    viewModelScope.launch(Dispatchers.Main) {
                        updateDeviceWhiteBoard(address, whiteboardName)
                    }
                }
            }
        }
    }


    // Add the broadcast receiver
    private val characteristicReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == BleAndMqttService.ACTION_CHARACTERISTIC_FOUND) {
            val address = intent.getStringExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS)
            val uuid = intent.getStringExtra(BleAndMqttService.EXTRA_CHARACTERISTIC_NAME)

            if (address != null && uuid != null) {
                updateDeviceUuid(address, uuid)
            }
        }
        }
    }

    private fun updateDeviceWhiteBoard(address: String, whiteboardName: String) {
        // Instant lookup
        val originalDevice = scannedDevicesMap[address] ?: return

        if (originalDevice.whiteboardServices.contains(whiteboardName)) return

        val updatedServices = originalDevice.whiteboardServices + whiteboardName
        val updatedDeviceTrans = originalDevice.copy(whiteboardServices = updatedServices)

        scannedDevicesMap[address] = updatedDeviceTrans
        uiUpdateTrigger.trySend(Unit)
    }





    private fun updateDeviceUuid(address: String, uuid: String) {
        // Find the index of the device in your mutable list
        // Instant lookup using the HashMap
        val originalDevice = scannedDevicesMap[address] ?: return

        // 1. Check if UUID is already there to avoid duplicates
        if (originalDevice.bleServices.contains(uuid)) return

        val updatedServices = originalDevice.bleServices + uuid

        // 2. Create a COPY of the Trans object with the new list
        val updatedDeviceTrans = originalDevice.copy(bleServices = updatedServices)

        // 3. REPLACE the object in the source of truth map
        scannedDevicesMap[address] = updatedDeviceTrans

        // 4. Trigger the throttled UI update
        uiUpdateTrigger.trySend(Unit)

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
    val deviceT: BleDeviceInfoTrans
)

data class BleDeviceInfoTrans(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    var bleServices: List<String> = listOf<String>(),
    var whiteboardServices: List<String> = listOf<String>()
)