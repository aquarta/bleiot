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
import it.unisalento.bleiot.BleCharacteristicInfo

import it.unisalento.bleiot.repositories.BleRepository
import it.unisalento.bleiot.repositories.BleDeviceState
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@HiltViewModel
class BleViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: BleRepository
) : ViewModel() {
    private val TAG = "BleViewModel"
    private val SCAN_PERIOD: Long = 10000 // Scan for 10 seconds

    // BLE properties
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())

    // UI State
    private val _uiState = MutableStateFlow(BleUiState())
    val uiState: StateFlow<BleUiState> = _uiState.asStateFlow()

    // Service and context references
    private var bleAndMqttService: BleAndMqttService? = null

    init {
        // Initialize Bluetooth
        val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
            repository.updateStatus("Bluetooth not supported")
        }

        // Observe repository and update UI state
        combine(
            repository.scannedDevices,
            repository.statusText,
            repository.latestData
        ) { devices, status, data ->
            val hasConnectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else true

            _uiState.update { currentState ->
                currentState.copy(
                    statusText = status,
                    dataText = data,
                    connectedDeviceAddresses = devices.filter { it.value.isConnected }.keys,
                    devicesList = devices.values.map { deviceState ->
                        BleDeviceInfo(
                            name = if (hasConnectPermission) deviceState.name else "Unknown Device",
                            address = deviceState.address,
                            deviceT = mapToTrans(deviceState),
                            txPhy = deviceState.txPhy,
                            rxPhy = deviceState.rxPhy,
                            supportedPhy = deviceState.supportedPhy,
                            rssi = deviceState.rssi
                        )
                    }
                )
            }
        }.launchIn(viewModelScope)
    }

    private fun mapToTrans(state: BleDeviceState): BleDeviceInfoTrans {
        return BleDeviceInfoTrans(
            name = state.name,
            address = state.address,
            device = state.device,
            bleServices = state.bleServices,
            whiteboardServices = state.whiteboardServices,
            txPhy = state.txPhy,
            rxPhy = state.rxPhy,
            supportedPhy = state.supportedPhy,
            rssi = state.rssi
        )
    }

    fun setService(service: BleAndMqttService) {
        bleAndMqttService = service
        // Callbacks are now partially redundant but we keep them if service still uses them
        bleAndMqttService?.setCallbacks(
            status = { repository.updateStatus(it) },
            data = { repository.updateData(it) },
            phy = { addr, tx, rx -> repository.updateDevicePhy(addr, repository.phyToString(tx), repository.phyToString(rx)) },
            supPhy = { addr, sup -> repository.updateDeviceSupportedPhy(addr, sup) },
            rssi = { addr, rssi -> repository.updateDeviceRssi(addr, rssi) }
        )
    }

    fun setPreferredPhy(address: String, txPhy: Int, rxPhy: Int, phyOptions: Int) {
        bleAndMqttService?.setPreferredPhy(address, txPhy, rxPhy, phyOptions)
    }

    fun requestConnectionPriority(address: String, priority: Int) {
        bleAndMqttService?.requestConnectionPriority(address, priority)
    }

    fun setAppTagName(address: String, tagName: String) {
        bleAndMqttService?.let { service ->
            service.setAppTagName(address, tagName)
            // Also update repository directly for immediate UI feedback
            _uiState.update { currentState ->
                // This is a bit complex without repository support for appTagName update
                // For now, let's just rely on the service to update if we add that logic there
                currentState
            }
        }
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
                repository.updateStatus("Bluetooth is disabled")
                return
            }

            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                repository.updateStatus("Bluetooth scan permission denied")
                return
            }

            bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
            if (bluetoothLeScanner == null) {
                repository.updateStatus("Cannot access Bluetooth scanner")
                return
            }

            // Clear non-connected devices from repository
            repository.clearScannedDevices(uiState.value.connectedDeviceAddresses)

            handler.postDelayed({ if (scanning) stopScan() }, SCAN_PERIOD)

            scanning = true
            bluetoothLeScanner?.startScan(scanCallback)
            updateScanButtonText("Stop Scan")
            repository.updateStatus("Scanning...")
        }
    }

    fun stopScan() {
        appContext?.let { context ->
            if (scanning && bluetoothLeScanner != null) {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    scanning = false
                    bluetoothLeScanner?.stopScan(scanCallback)
                    updateScanButtonText("Start Scan")
                    repository.updateStatus("Scan stopped")
                }
            }
        }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (result.device.name != null) {
                repository.addOrUpdateScannedDevice(result.device, result.rssi)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            repository.updateStatus("Scan failed: $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        appContext?.let { context ->
            repository.updateStatus("Connecting to ${device.name ?: "Unknown"}...")
            if (bleAndMqttService != null) {
                val intent = Intent(context, BleAndMqttService::class.java).apply {
                    action = "CONNECT_BLE"
                    putExtra("deviceAddress", device.address)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            } else {
                repository.updateStatus("Service not bound")
            }
        }
    }

    fun disconnectDevice(deviceAddress: String) {
        appContext?.let { context ->
            if (bleAndMqttService != null) {
                val intent = Intent(context, BleAndMqttService::class.java).apply {
                    action = "DISCONNECT_BLE"
                    putExtra("deviceAddress", deviceAddress)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
                repository.updateStatus("Disconnecting from $deviceAddress")
            }
        }
    }

    fun updateStatus(status: String) {
        repository.updateStatus(status)
    }

    private fun updateScanButtonText(text: String) {
        _uiState.update { it.copy(scanButtonText = text) }
    }

    override fun onCleared() {
        super.onCleared()
        stopScan()
    }
    
    // Legacy update methods removed, now using Repository
    fun setCharacteristicNotification(deviceAddress: String, characteristicUuid: String, enabled: Boolean) {
        val intent = Intent(appContext, BleAndMqttService::class.java).apply {
            action = if (enabled) BleAndMqttService.ACTION_ENABLE_CHAR_NOTIFY else BleAndMqttService.ACTION_DISABLE_CHAR_NOTIFY
            putExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(BleAndMqttService.EXTRA_CHARACTERISTIC_NAME, characteristicUuid)
        }
        appContext?.startService(intent)
    }

    fun setWhiteboardSubscription(deviceAddress: String, measureName: String, enabled: Boolean) {
        val intent = Intent(appContext, BleAndMqttService::class.java).apply {
            action = if (enabled) BleAndMqttService.ACTION_ENABLE_WHITEBOARD_SUBSCRIBE else BleAndMqttService.ACTION_WHITEBOARD_UNSUBSCRIBE
            putExtra(BleAndMqttService.EXTRA_DEVICE_ADDRESS, deviceAddress)
            putExtra(BleAndMqttService.EXTRA_WHITEBOARD_MEASURE, measureName)
        }
        appContext?.startService(intent)
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
    val deviceT: BleDeviceInfoTrans,
    val txPhy: String = "Unknown",
    val rxPhy: String = "Unknown",
    var supportedPhy: String = "Unknown",
    val rssi: Int = 0
)

data class WhiteboardMeasureInfo(
    val name: String,
    val isSubscribed: Boolean = false
)

data class BleDeviceInfoTrans(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    var bleServices: List<BleCharacteristicInfo> = listOf<BleCharacteristicInfo>(),
    var whiteboardServices: List<WhiteboardMeasureInfo> = listOf(),
    var txPhy: String = "Unknown",
    var rxPhy: String = "Unknown",
    var supportedPhy: String = "Unknown",
    var autoConnect: Boolean = false,
    var rssi: Int = 0,
    var appTagName: String = ""
)