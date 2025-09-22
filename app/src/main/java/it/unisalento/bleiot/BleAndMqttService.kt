package it.unisalento.bleiot

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject
import java.util.*

private const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

class BleAndMqttService : Service() {
    private val TAG = "BleAndMqttService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "ble_mqtt_service_channel"

    // MQTT properties
    private var mqttClient: MqttClient? = null
    private val MQTT_CLIENT_ID = "AndroidBleClient" + System.currentTimeMillis()
    private val MQTT_TOPIC = "ble/temperature"
    private val MQTT_USERNAME = "your_username" // Optional
    private val MQTT_PASSWORD = "your_password" // Optional
    private lateinit var mqttSettings: MqttSettings
    private lateinit var deviceConfigManager: DeviceConfigurationManager

    // BLE properties
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    private val gattConnections = mutableMapOf<String, BluetoothGatt>()

    // Queue for descriptor operations per device
    private val descriptorWriteQueues = mutableMapOf<String, MutableList<BluetoothGattDescriptor>>()
    private val isWritingDescriptors = mutableMapOf<String, Boolean>()

    // Service UUID and Characteristic UUID
    private val SERVICE_UUID = UUID.fromString("00000000-0001-11e1-9ab4-0002a5d5c51b")
    private val CHARACTERISTIC_UUID = UUID.fromString("00140000-0001-11e1-ac36-0002a5d5c51b")

    // Binder for activity communication
    private val binder = LocalBinder()

    // Status callback
    private var statusCallback: ((String) -> Unit)? = null
    private var dataCallback: ((String) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): BleAndMqttService = this@BleAndMqttService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize MQTT settings and device configuration
        mqttSettings = MqttSettings.getInstance(this)
        deviceConfigManager = DeviceConfigurationManager.getInstance(this)

        // Initialize Bluetooth adapter
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        // Connect to MQTT broker
        setupMqttClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Service is running")
        startForeground(NOTIFICATION_ID, notification)

        // Handle actions from intent
        when (intent?.action) {
            "CONNECT_BLE" -> {
                val deviceAddress = intent.getStringExtra("deviceAddress")
                deviceAddress?.let {
                    connectToDevice(it)
                }
            }
            "DISCONNECT_BLE" -> {
                val deviceAddress = intent.getStringExtra("deviceAddress")
                Log.i(TAG, "received device disconnection command for $deviceAddress")
                disconnectBle(deviceAddress)
            }
            "STOP_SERVICE" -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectBle()
        disconnectMqtt()
    }

    fun setCallbacks(statusCallback: (String) -> Unit, dataCallback: (String) -> Unit) {
        this.statusCallback = statusCallback
        this.dataCallback = dataCallback
    }

    fun getConnectedDevices(): List<BluetoothDevice> {
        return connectedDevices.values.toList()
    }

    fun getConnectedDeviceAddresses(): Set<String> {
        return connectedDevices.keys.toSet()
    }

    fun reloadMqttSettings() {
        disconnectMqtt()
        setupMqttClient()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "BLE MQTT Service"
            val descriptionText = "Maintains BLE and MQTT connections"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, pendingIntentFlags
        )

        val stopIntent = Intent(this, BleAndMqttService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE MQTT Service")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // MQTT Methods
    private fun setupMqttClient() {
        try {
            val config = mqttSettings.getMqttConfig()
            val serverUri = "tcp://${config.server}:${config.port}"

            mqttClient = MqttClient(
                serverUri,
                MQTT_CLIENT_ID,
                MemoryPersistence()
            )

            val options = MqttConnectOptions()
            if (MQTT_USERNAME.isNotEmpty() && MQTT_PASSWORD.isNotEmpty()) {
                options.userName = MQTT_USERNAME
                options.password = MQTT_PASSWORD.toCharArray()
            }
            options.isAutomaticReconnect = true
            options.isCleanSession = true

            mqttClient?.connect(options)
            updateStatus("Connected to MQTT broker at ${config.server}:${config.port}")

        } catch (e: MqttException) {
            Log.e(TAG, "Error setting up MQTT client: ${e.message}")
            updateStatus("MQTT connection failed: ${e.message}")
        }
    }

    private fun reconnectMqttClient() {
        Log.w(TAG, "Reconnecting to MQTT server")
        setupMqttClient();
    }
    private fun publishToMqtt(topic: String, message: String) {
        try {
            if (mqttClient?.isConnected == true) {
                val mqttMessage = MqttMessage(message.toByteArray())
                mqttMessage.qos = 1
                mqttClient?.publish(topic, mqttMessage)
                Log.i(TAG, "Published to MQTT: $message")
            } else {
                Log.w(TAG, "MQTT client not connected, attempting to reconnect")
                setupMqttClient()
                // Try again after reconnection attempt
                if (mqttClient?.isConnected == true) {
                    val mqttMessage = MqttMessage(message.toByteArray())
                    mqttMessage.qos = 1
                    mqttClient?.publish(topic, mqttMessage)
                }
            }
        } catch (e: MqttException) {
            reconnectMqttClient();
            Log.e(TAG, "Error publishing to MQTT: ${e.message}")
        }
    }

    private fun disconnectMqtt() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
        } catch (e: MqttException) {
            Log.e(TAG, "Error disconnecting MQTT: ${e.message}")
        }
    }

    // BLE Methods
    fun connectToDevice(address: String) {
        if (bluetoothAdapter == null || address.isEmpty()) {
            updateStatus("Bluetooth adapter not initialized or invalid address")
            return
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            updateStatus("Bluetooth connect permission not granted")
            return
        }

        try {
            val device = bluetoothAdapter?.getRemoteDevice(address)
            device?.let {
                // Check if already connected to this device
                if (connectedDevices.containsKey(it.address)) {
                    updateStatus("Already connected to ${it.name ?: "Unknown Device"}")
                    return
                }

                // Connect to the new device (no need to disconnect others)
                connectedDevices[it.address] = it
                descriptorWriteQueues[it.address] = mutableListOf()
                isWritingDescriptors[it.address] = false

                updateStatus("Connecting to ${it.name ?: "Unknown Device"}...")
                val gatt = it.connectGatt(this, false, gattCallback)
                gattConnections[it.address] = gatt
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device: ${e.message}")
            updateStatus("Error connecting: ${e.message}")
        }
    }

    private fun disconnectBle(deviceAddress: String? = null) {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            return
        }

        if (deviceAddress != null) {
            // Disconnect specific device
            gattConnections[deviceAddress]?.disconnect()
        } else {
            // Disconnect all devices
            gattConnections.values.forEach { gatt ->
                gatt.disconnect()
            }
        }
    }

    // GATT callback
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.i(TAG, "Connected to GATT server.")

                    if (ActivityCompat.checkSelfPermission(
                            this@BleAndMqttService,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        return
                    }

                    updateStatus("Connected to ${gatt.device.name ?: "Unknown Device"}")
                    updateNotification("Connected to ${gatt.device.name ?: "Unknown Device"}")

                    // Discover services
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val deviceAddress = gatt.device.address
                    Log.i(TAG, "Disconnected from GATT server.")
                    Log.i(TAG, "Disconnected from device ${gatt.device.name ?: "Unknown Device"} ($deviceAddress)")
                    updateStatus("Disconnected from device ${gatt.device.name ?: "Unknown Device"}")
                    updateNotification("Disconnected from BLE device ${gatt.device.name ?: "Unknown Device"}")

                    // Clean up the specific GATT connection
                    gatt.close()
                    gattConnections.remove(deviceAddress)
                    connectedDevices.remove(deviceAddress)
                    descriptorWriteQueues.remove(deviceAddress)
                    isWritingDescriptors.remove(deviceAddress)

                    // Update data only if this was the last connected device
                    if (connectedDevices.isEmpty()) {
                        updateData("")
                    }
                }
            } else {
                val deviceAddress = gatt.device.address
                Log.w(TAG, "Error $status encountered! Disconnecting...")
                updateStatus("Connection error: $status")
                gatt.close()

                // Clean up the specific device on error
                gattConnections.remove(deviceAddress)
                connectedDevices.remove(deviceAddress)
                descriptorWriteQueues.remove(deviceAddress)
                isWritingDescriptors.remove(deviceAddress)

                updateStatus("Disconnected from device ${gatt.device.name ?: "Unknown Device"} due to a connection error")
                updateNotification("Disconnected from BLE device ${gatt.device.name ?: "Unknown Device"}")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")

                if (ActivityCompat.checkSelfPermission(
                        this@BleAndMqttService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ) {
                    return
                }

                val deviceName = gatt.device.name
                var enabledCharacteristics = 0

                // Iterate through all services
                for (service in gatt.services) {
                    Log.i(TAG, "Checking service: ${service.uuid}")

                    // Iterate through all characteristics in each service
                    for (characteristic in service.characteristics) {
                        Log.i(TAG, "Checking characteristic: ${characteristic.uuid}")

                        // Check if this characteristic is known in our configuration
                        val configPair = deviceConfigManager.findServiceAndCharacteristic(
                            deviceName, service.uuid.toString(), characteristic.uuid.toString()
                        )

                        if (configPair != null) {
                            val (serviceInfo, characteristicInfo) = configPair
                            Log.i(TAG, "Found configured characteristic: ${characteristicInfo.name}")

                            // Enable notifications for known characteristics
                            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                                gatt.setCharacteristicNotification(characteristic, true)

                                // Enable the Client Characteristic Configuration Descriptor (CCCD)
                                val desc_uuid = UUID.fromString(CCCD)
                                val descriptor = characteristic.getDescriptor(desc_uuid)
                                if (descriptor != null) {
                                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                    queueDescriptorWrite(gatt.device.address, descriptor)
                                    enabledCharacteristics++
                                    Log.i(TAG, "Queued notifications for ${characteristicInfo.name}")
                                }
                            }
                        } else {
                            // Fallback: check against hardcoded characteristic for backward compatibility
                            if (service.uuid == SERVICE_UUID && characteristic.uuid == CHARACTERISTIC_UUID) {
                                Log.i(TAG, "Found fallback characteristic")

                                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                                    gatt.setCharacteristicNotification(characteristic, true)

                                    val desc_uuid = UUID.fromString(CCCD)
                                    val descriptor = characteristic.getDescriptor(desc_uuid)
                                    if (descriptor != null) {
                                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                        queueDescriptorWrite(gatt.device.address, descriptor)
                                        enabledCharacteristics++
                                        Log.i(TAG, "Queued notifications for fallback characteristic")
                                    }
                                }
                            }
                        }
                    }
                }

                if (enabledCharacteristics > 0) {
                    updateStatus("Notifications enabled for $enabledCharacteristics characteristics")
                } else {
                    updateStatus("No known characteristics found for notifications")
                }
            } else {
                updateStatus("Service discovery failed: $status")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val deviceAddress = gatt.device.address
            isWritingDescriptors[deviceAddress] = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor write successful for device $deviceAddress")
            } else {
                Log.w(TAG, "Descriptor write failed with status: $status for device $deviceAddress")
            }
            // Process next descriptor in queue for this device
            processDescriptorQueue(deviceAddress)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        // For Android versions below 13
        @Deprecated("Deprecated in API level 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                super.onCharacteristicChanged(gatt, characteristic)
            } else {
                val value = characteristic.value
                handleCharacteristicChanged(gatt, characteristic, value)
            }
        }
    }

    private fun handleCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            ) {
                return
            }

            val deviceName = gatt.device.name
            val serviceUuid = characteristic.service.uuid.toString()
            val characteristicUuid = characteristic.uuid.toString()
            Log.i(TAG, "handleCharacteristicChanged findServiceAndCharacteristic ${deviceName} ${serviceUuid} ${characteristicUuid}")
            // Try to find device configuration
            val configPair = deviceConfigManager.findServiceAndCharacteristic(
                deviceName, serviceUuid, characteristicUuid
            )

            if (configPair != null) {
                val (serviceInfo, characteristicInfo) = configPair
                
                // Parse data using configuration
                val parsedData = deviceConfigManager.parseCharacteristicData(characteristicInfo, value)

                if (parsedData != null) {
                    // Add device info if parsedData is a Map
                    val enrichedData = if (parsedData is Map<*, *>) {
                        val mutableData = parsedData.toMutableMap()
                        mutableData["deviceName"] = deviceName ?: "Unknown"
                        mutableData["deviceAddress"] = gatt.device.address ?: "Unknown"
                        mutableData
                    } else {
                        parsedData
                    }

                    val formattedData = "${characteristicInfo.name}: $enrichedData"
                    updateData(formattedData)
                    publishToMqtt(characteristicInfo.mqttTopic, toJsonString(enrichedData))

                    Log.i(TAG, "Parsed ${characteristicInfo.name} from ${deviceName}: $enrichedData")
                } else {
                    Log.w(TAG, "Failed to parse data for ${characteristicInfo.name}")
                }
            } else {
                // Check if characteristic is known by UUID only (without device context)
                val knownCharacteristic = deviceConfigManager.findCharacteristicByUuid(characteristicUuid)

                if (knownCharacteristic != null) {
                    // Check if there's a custom parser defined
                    if (!knownCharacteristic.customParser.isNullOrEmpty()) {
                        try {
                            // Call the custom parser method using reflection
                            val method = this@BleAndMqttService::class.java.getDeclaredMethod(knownCharacteristic.customParser, ByteArray::class.java)
                            method.isAccessible = true
                            val parsedData = method.invoke(this@BleAndMqttService, value)

                            val formattedData = "${knownCharacteristic.name}: $parsedData"
                            updateData(formattedData)
                            publishToMqtt(knownCharacteristic.mqttTopic, toJsonString(parsedData))

                            Log.i(TAG, "Used custom parser ${knownCharacteristic.customParser} for ${knownCharacteristic.name}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error calling custom parser ${knownCharacteristic.customParser}: ${e.message}")
                            // Fall back to standard parsing
                            val parsedData = deviceConfigManager.parseCharacteristicData(knownCharacteristic, value)
                            if (parsedData != null) {
                                // Add device info if parsedData is a Map
                                val enrichedData = if (parsedData is Map<*, *>) {
                                    val mutableData = parsedData.toMutableMap()
                                    mutableData["deviceName"] = deviceName ?: "Unknown"
                                    mutableData["deviceAddress"] = gatt.device.address ?: "Unknown"
                                    mutableData
                                } else {
                                    parsedData
                                }

                                val formattedData = "${knownCharacteristic.name}: $enrichedData"
                                updateData(formattedData)
                                publishToMqtt(knownCharacteristic.mqttTopic, toJsonString(enrichedData))
                            }
                        }
                    } else {
                        // Use standard parsing from configuration
                        val parsedData = deviceConfigManager.parseCharacteristicData(knownCharacteristic, value)
                        if (parsedData != null) {
                            // Add device info if parsedData is a Map
                            val enrichedData = if (parsedData is Map<*, *>) {
                                val mutableData = parsedData.toMutableMap()
                                mutableData["deviceName"] = deviceName ?: "Unknown"
                                mutableData["deviceAddress"] = gatt.device.address ?: "Unknown"
                                mutableData
                            } else {
                                parsedData
                            }

                            val formattedData = "${knownCharacteristic.name}: $enrichedData"
                            updateData(formattedData)
                            publishToMqtt(knownCharacteristic.mqttTopic, toJsonString(enrichedData))

                            Log.i(TAG, "Used standard parsing for known characteristic ${knownCharacteristic.name}")
                        }
                    }
                } else {
                    Log.e(TAG, "Failed parsing for unknown device: $deviceName $characteristicUuid $knownCharacteristic")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling characteristic change", e)
        }
    }

    private fun parseSTBattery(data: ByteArray): Double {
        val battValue = data.sliceArray(6 until data.size).foldIndexed(0) { index, acc, byte ->
            acc or ((byte.toInt() and 0xFF) shl (8 * index))
        }
        return battValue.toDouble()
    }

    private fun parseTemperatureDeta(data: ByteArray): Double {
        val tempValue = data.sliceArray(6 until data.size).foldIndexed(0) { index, acc, byte ->
            acc or ((byte.toInt() and 0xFF) shl (8 * index))
        }
        return (tempValue/10).toDouble()
    }

    // Update status helpers
    private fun updateStatus(status: String) {
        Log.i(TAG, "Status: $status")
        statusCallback?.invoke(status)
    }

    private fun updateData(data: String) {
        Log.i(TAG, "Data: $data")
        dataCallback?.invoke(data)
    }

    private fun toJsonString(data: Any): String {
        return if (data is Map<*, *>) {
            try {
                JSONObject(data as Map<String, Any>).toString()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to convert to JSON: ${e.message}")
                data.toString()
            }
        } else {
            data.toString()
        }
    }

    private fun queueDescriptorWrite(deviceAddress: String, descriptor: BluetoothGattDescriptor) {
        descriptorWriteQueues[deviceAddress]?.add(descriptor)
        processDescriptorQueue(deviceAddress)
    }

    private fun processDescriptorQueue(deviceAddress: String) {
        val queue = descriptorWriteQueues[deviceAddress] ?: return
        val isWriting = isWritingDescriptors[deviceAddress] ?: false

        if (isWriting || queue.isEmpty()) return

        val descriptor = queue.removeAt(0)
        isWritingDescriptors[deviceAddress] = true

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            isWritingDescriptors[deviceAddress] = false
            return
        }

        gattConnections[deviceAddress]?.writeDescriptor(descriptor)
    }
}