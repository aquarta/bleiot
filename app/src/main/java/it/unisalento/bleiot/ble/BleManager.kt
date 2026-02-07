package it.unisalento.bleiot.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.movesense.mds.Mds
import com.movesense.mds.MdsConnectionListener
import com.movesense.mds.MdsException
import it.unisalento.bleiot.BleAndMqttService
import it.unisalento.bleiot.DeviceConfigurationManager
import it.unisalento.bleiot.WhiteboardMeasureInfo
import it.unisalento.bleiot.repositories.BleRepository
import java.util.*

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: BleRepository,
    private val deviceConfigManager: DeviceConfigurationManager
) {
    private val TAG = "BleManager"
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val gattConnections = mutableMapOf<String, BluetoothGatt>()
    private val movesenseConnectedDevices = mutableMapOf<String, String>()
    
    // Queues for operations
    private val descriptorWriteQueues = mutableMapOf<String, MutableList<BluetoothGattDescriptor>>()
    private val isWritingDescriptors = mutableMapOf<String, Boolean>()
    
    data class CharacteristicWrite(val characteristic: BluetoothGattCharacteristic, val data: ByteArray)
    private val characteristicWriteQueues = mutableMapOf<String, MutableList<CharacteristicWrite>>()
    private val isWritingCharacteristics = mutableMapOf<String, Boolean>()

    // Handler for periodic RSSI updates
    private val rssiHandler = Handler(Looper.getMainLooper())
    private val rssiRunnable = object : Runnable {
        override fun run() {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                gattConnections.values.forEach { gatt ->
                    gatt.readRemoteRssi()
                }
            }
            rssiHandler.postDelayed(this, 30000)
        }
    }

    // Callbacks for data processing (will be moved to SensorDataManager later)
    var onCharacteristicDataReceived: ((BluetoothGatt, ByteArray, String, String) -> Unit)? = null
    var onWhiteboardFound: ((String, String) -> Unit)? = null
    var onCharacteristicFound: ((String, String, Int) -> Unit)? = null

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun connectToDevice(address: String) {
        if (bluetoothAdapter == null || address.isEmpty()) {
            repository.updateStatus("Bluetooth adapter not initialized or invalid address")
            return
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            device?.let {
                if (gattConnections.containsKey(it.address)) {
                    repository.updateStatus("Already connected to ${it.name ?: "Unknown"}")
                    return
                }

                repository.addOrUpdateScannedDevice(it)
                descriptorWriteQueues[it.address] = mutableListOf()
                isWritingDescriptors[it.address] = false
                characteristicWriteQueues[it.address] = mutableListOf()
                isWritingCharacteristics[it.address] = false

                repository.updateStatus("Connecting to ${it.name ?: "Unknown"}...")
                
                if (it.name?.contains("Movesense") == true) {
                    Log.i(TAG, "Connecting to Movesense device: ${it.address}")
                    Mds.builder().build(context).connect(it.address, object : MdsConnectionListener {
                        override fun onConnect(s: String) { Log.d(TAG, "MDS onConnect: $s") }
                        override fun onConnectionComplete(macAddress: String, serial: String) {
                            Log.d(TAG, "MDS Connected: $macAddress -> $serial")
                            movesenseConnectedDevices[macAddress] = serial
                        }
                        override fun onError(e: MdsException) { Log.e(TAG, "MDS onError: $e") }
                        override fun onDisconnect(bleAddress: String) { Log.d(TAG, "MDS onDisconnect: $bleAddress") }
                    })
                }
                
                val gatt = it.connectGatt(context, false, gattCallback)
                gattConnections[it.address] = gatt
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device: ${e.message}")
            repository.updateStatus("Error connecting: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect(address: String? = null) {
        if (address != null) {
            gattConnections[address]?.disconnect()
        } else {
            gattConnections.values.forEach { it.disconnect() }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun setPreferredPhy(address: String, txPhy: Int, rxPhy: Int, phyOptions: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            gattConnections[address]?.setPreferredPhy(txPhy, rxPhy, phyOptions)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun requestConnectionPriority(address: String, priority: Int) {
        gattConnections[address]?.requestConnectionPriority(priority)
    }

    fun getMovesenseSerial(address: String): String? = movesenseConnectedDevices[address]

    fun getGatt(address: String): BluetoothGatt? = gattConnections[address]

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT: $address")
                    repository.updateConnectionState(address, true)
                    repository.updateStatus("Connected to ${gatt.device.name ?: "Unknown"}")
                    
                    if (gattConnections.size == 1) rssiHandler.post(rssiRunnable)
                    gatt.discoverServices()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) gatt.readPhy()

                    val supported = StringBuilder("1M").apply {
                        if (bluetoothAdapter?.isLe2MPhySupported == true) append(", 2M")
                        if (bluetoothAdapter?.isLeCodedPhySupported == true) append(", Coded")
                    }.toString()
                    repository.updateDeviceSupportedPhy(address, supported)

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "Disconnected from GATT: $address")
                    cleanupDevice(address)
                    gatt.close()
                    repository.updateConnectionState(address, false)
                    repository.updateStatus("Disconnected from ${gatt.device.name ?: "Unknown"}")
                    if (gattConnections.isEmpty()) rssiHandler.removeCallbacks(rssiRunnable)
                }
            } else {
                Log.w(TAG, "GATT error $status for $address. Disconnecting...")
                cleanupDevice(address)
                gatt.close()
                repository.updateConnectionState(address, false)
                repository.updateStatus("Connection error: $status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for ${gatt.device.address}")
                processDiscoveredServices(gatt)
            }
        }

        override fun onPhyRead(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                repository.updateDevicePhy(gatt.device.address, phyToString(txPhy), phyToString(rxPhy))
            }
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                repository.updateDevicePhy(gatt.device.address, phyToString(txPhy), phyToString(rxPhy))
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                repository.updateDeviceRssi(gatt.device.address, rssi)
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onCharacteristicDataReceived?.invoke(gatt, characteristic.value, characteristic.service.uuid.toString(), characteristic.uuid.toString())
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onCharacteristicDataReceived?.invoke(gatt, characteristic.value, characteristic.service.uuid.toString(), characteristic.uuid.toString())
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                onCharacteristicDataReceived?.invoke(gatt, value, characteristic.service.uuid.toString(), characteristic.uuid.toString())
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onCharacteristicDataReceived?.invoke(gatt, value, characteristic.service.uuid.toString(), characteristic.uuid.toString())
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val address = gatt.device.address
            isWritingDescriptors[address] = false
            
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Check if it's the CCCD (Client Characteristic Configuration Descriptor)
                if (descriptor.uuid.toString() == "00002902-0000-1000-8000-00805f9b34fb") {
                    val isEnabled = !descriptor.value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                    val charUuid = descriptor.characteristic.uuid.toString()
                    val serviceUuid = descriptor.characteristic.service.uuid.toString()
                    val deviceName = gatt.device.name ?: "Unknown"
                    
                    // Try to find specific config first to match what was used in discovery
                    val configPair = deviceConfigManager.findServiceAndCharacteristic(deviceName, serviceUuid, charUuid, address)
                    val charName = configPair?.second?.name ?: deviceConfigManager.findCharacteristicByUuid(charUuid)?.name
                    
                    if (charName != null) {
                        repository.updateCharacteristicNotificationState(address, charName, isEnabled)
                        Log.i(TAG, "Notification state updated for $charName ($charUuid) on $address: $isEnabled")
                    } else {
                        Log.w(TAG, "Could not resolve name for characteristic $charUuid, UI state not updated")
                    }
                }
            } else {
                Log.e(TAG, "Descriptor write failed: $status")
            }

            processDescriptorQueue(address)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val address = gatt.device.address
            isWritingCharacteristics[address] = false
            processCharacteristicQueue(address)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processDiscoveredServices(gatt: BluetoothGatt) {
        val deviceName = gatt.device.name ?: "Unknown"
        val foundChars = mutableListOf<it.unisalento.bleiot.BleCharacteristicInfo>()

        for (service in gatt.services) {
            for (characteristic in service.characteristics) {
                val configPair = deviceConfigManager.findServiceAndCharacteristic(
                    deviceName, service.uuid.toString(), characteristic.uuid.toString(), gatt.device.address
                )
                if (configPair != null) {
                    val (_, charInfo) = configPair
                    onCharacteristicFound?.invoke(gatt.device.address, charInfo.name, characteristic.properties)
                    foundChars.add(it.unisalento.bleiot.BleCharacteristicInfo(charInfo.name, characteristic.properties))
                }
            }
        }
        repository.updateDeviceServices(gatt.device.address, foundChars)

        val whiteboardMeasures = deviceConfigManager.findWhiteboardSpecs(deviceName)
        if (whiteboardMeasures.isNotEmpty()) {
            val whiteboardInfos = whiteboardMeasures.map { wbMeasure -> 
                WhiteboardMeasureInfo(name = wbMeasure.name, isSubscribed = false) 
            }
            repository.updateDeviceWhiteboards(gatt.device.address, whiteboardInfos)
            
            // Still invoke callback for legacy support if needed
            whiteboardMeasures.forEach { whiteboard ->
                onWhiteboardFound?.invoke(gatt.device.address, whiteboard.name)
            }
        }
    }

    private fun cleanupDevice(address: String) {
        gattConnections.remove(address)
        movesenseConnectedDevices.remove(address)
        descriptorWriteQueues.remove(address)
        isWritingDescriptors.remove(address)
        characteristicWriteQueues.remove(address)
        isWritingCharacteristics.remove(address)
    }

    private fun phyToString(phy: Int): String = when (phy) {
        BluetoothDevice.PHY_LE_1M -> "1M"
        BluetoothDevice.PHY_LE_2M -> "2M"
        BluetoothDevice.PHY_LE_CODED -> "Coded"
        else -> "Unknown"
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun queueDescriptorWrite(address: String, descriptor: BluetoothGattDescriptor) {
        descriptorWriteQueues[address]?.add(descriptor)
        processDescriptorQueue(address)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processDescriptorQueue(address: String) {
        if (isWritingDescriptors[address] == true) return
        val queue = descriptorWriteQueues[address] ?: return
        if (queue.isEmpty()) return

        val descriptor = queue.removeAt(0)
        isWritingDescriptors[address] = true
        gattConnections[address]?.writeDescriptor(descriptor)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun queueCharacteristicWrite(address: String, characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        characteristicWriteQueues[address]?.add(CharacteristicWrite(characteristic, data))
        processCharacteristicQueue(address)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun processCharacteristicQueue(address: String) {
        if (isWritingCharacteristics[address] == true) return
        val queue = characteristicWriteQueues[address] ?: return
        if (queue.isEmpty()) return

        val write = queue.removeAt(0)
        isWritingCharacteristics[address] = true
        
        val gatt = gattConnections[address] ?: return
        write.characteristic.value = write.data
        gatt.writeCharacteristic(write.characteristic)
    }
}
