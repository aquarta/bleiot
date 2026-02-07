package it.unisalento.bleiot.repositories

import android.bluetooth.BluetoothDevice
import it.unisalento.bleiot.BleCharacteristicInfo
import it.unisalento.bleiot.WhiteboardMeasureInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central repository for BLE state across the application.
 */
@Singleton
class BleRepository @Inject constructor() {
    private val _scannedDevices = MutableStateFlow<Map<String, BleDeviceState>>(emptyMap())
    val scannedDevices: StateFlow<Map<String, BleDeviceState>> = _scannedDevices.asStateFlow()

    private val _statusText = MutableStateFlow("Ready")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _latestData = MutableStateFlow("")
    val latestData: StateFlow<String> = _latestData.asStateFlow()

    fun updateStatus(status: String) {
        _statusText.value = status
    }

    fun phyToString(phy: Int): String {
        return when (phy) {
            BluetoothDevice.PHY_LE_1M -> "1M"
            BluetoothDevice.PHY_LE_2M -> "2M"
            BluetoothDevice.PHY_LE_CODED -> "Coded"
            else -> "Unknown"
        }
    }

    fun updateData(data: String) {
        _latestData.value = data
    }

    fun addOrUpdateScannedDevice(device: BluetoothDevice, rssi: Int? = null) {
        _scannedDevices.update { devices ->
            val currentState = devices[device.address] ?: BleDeviceState(
                name = device.name ?: "Unknown Device",
                address = device.address,
                device = device
            )
            val updatedState = currentState.copy(
                rssi = rssi ?: currentState.rssi,
                name = device.name ?: currentState.name
            )
            devices + (device.address to updatedState)
        }
    }

    fun updateConnectionState(address: String, isConnected: Boolean) {
        _scannedDevices.update { devices ->
            devices[address]?.let {
                devices + (address to it.copy(isConnected = isConnected))
            } ?: devices
        }
    }

    fun updateDeviceServices(address: String, services: List<BleCharacteristicInfo>) {
        _scannedDevices.update { devices ->
            devices[address]?.let {
                devices + (address to it.copy(bleServices = services))
            } ?: devices
        }
    }

    fun updateDeviceWhiteboards(address: String, whiteboards: List<WhiteboardMeasureInfo>) {
        _scannedDevices.update { devices ->
            devices[address]?.let {
                devices + (address to it.copy(whiteboardServices = whiteboards))
            } ?: devices
        }
    }

    fun updateDevicePhy(address: String, txPhy: String, rxPhy: String) {
        _scannedDevices.update { devices ->
            devices[address]?.let {
                devices + (address to it.copy(txPhy = txPhy, rxPhy = rxPhy))
            } ?: devices
        }
    }

    fun updateDeviceSupportedPhy(address: String, supportedPhy: String) {
        _scannedDevices.update { devices ->
            devices[address]?.let {
                devices + (address to it.copy(supportedPhy = supportedPhy))
            } ?: devices
        }
    }

    fun updateDeviceRssi(address: String, rssi: Int) {
        _scannedDevices.update { devices ->
            devices[address]?.let {
                devices + (address to it.copy(rssi = rssi))
            } ?: devices
        }
    }

    fun updateDeviceAppTagName(address: String, tagName: String) {
        _scannedDevices.update { devices ->
            devices[address]?.let {
                devices + (address to it.copy(appTagName = tagName))
            } ?: devices
        }
    }

    fun updateCharacteristicNotificationState(address: String, characteristicUuid: String, isNotifying: Boolean) {
        _scannedDevices.update { devices ->
            val device = devices[address] ?: return@update devices
            var found = false
            val updatedServices = device.bleServices.map { charInfo ->
                if (charInfo.uuid.equals(characteristicUuid, ignoreCase = true)) {
                    found = true
                    charInfo.copy(isNotifying = isNotifying)
                } else {
                    charInfo
                }
            }
            if (!found) {
                android.util.Log.w("BleRepository", "Char $characteristicUuid not found in ${device.bleServices.map { it.uuid }}")
            } else {
                android.util.Log.i("BleRepository", "Updated state for $characteristicUuid to $isNotifying")
            }
            devices + (address to device.copy(bleServices = updatedServices))
        }
    }

    fun updateWhiteboardSubscriptionState(address: String, measureName: String, isSubscribed: Boolean) {
        _scannedDevices.update { devices ->
            val device = devices[address] ?: return@update devices
            val updatedWhiteboards = device.whiteboardServices.map { wbInfo ->
                if (wbInfo.name == measureName) {
                    wbInfo.copy(isSubscribed = isSubscribed)
                } else {
                    wbInfo
                }
            }
            devices + (address to device.copy(whiteboardServices = updatedWhiteboards))
        }
    }

    fun clearScannedDevices(exceptConnected: Set<String>) {
        _scannedDevices.update { devices ->
            devices.filter { it.key in exceptConnected || it.value.isConnected }
        }
    }
}

data class BleDeviceState(
    val name: String,
    val address: String,
    val device: BluetoothDevice,
    val isConnected: Boolean = false,
    val bleServices: List<BleCharacteristicInfo> = emptyList(),
    val whiteboardServices: List<WhiteboardMeasureInfo> = emptyList(),
    val txPhy: String = "Unknown",
    val rxPhy: String = "Unknown",
    val supportedPhy: String = "Unknown",
    val rssi: Int = 0,
    val appTagName: String = ""
)
