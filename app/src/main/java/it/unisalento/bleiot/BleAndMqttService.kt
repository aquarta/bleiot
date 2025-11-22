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
import android.os.Handler
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.movesense.mds.Mds
import com.movesense.mds.MdsConnectionListener
import com.movesense.mds.MdsException
import com.movesense.mds.MdsResponseListener
import it.unisalento.bleiot.MoveSenseConstants.MS_GSP_COMMAND_ID
import it.unisalento.bleiot.MoveSenseConstants.MS_GSP_HR_ID
import it.unisalento.bleiot.MoveSenseConstants.MS_GSP_IMU_ID
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

    // Queue for characteristic write operations per device
    data class CharacteristicWrite(val characteristic: BluetoothGattCharacteristic, val data: ByteArray)
    private val characteristicWriteQueues = mutableMapOf<String, MutableList<CharacteristicWrite>>()
    private val isWritingCharacteristics = mutableMapOf<String, Boolean>()


    // Binder for activity communication
    private val binder = LocalBinder()

    // Status callback
    private var statusCallback: ((String) -> Unit)? = null
    private var dataCallback: ((String) -> Unit)? = null

    companion object {
        // ... existing constants
        const val ACTION_CHARACTERISTIC_FOUND = "it.unisalento.bleiot.ACTION_CHARACTERISTIC_FOUND"
        const val EXTRA_DEVICE_ADDRESS = "it.unisalento.bleiot.EXTRA_DEVICE_ADDRESS"
        const val EXTRA_CHARACTERISTIC_UUID = "it.unisalento.bleiot.EXTRA_CHARACTERISTIC_UUID"
    }


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
                characteristicWriteQueues[it.address] = mutableListOf()
                isWritingCharacteristics[it.address] = false

                updateStatus("Connecting to ${it.name ?: "Unknown Device"}...")
                if(it.name.contains("Movesense")) {
                    Log.i(TAG, "use Mds.builder")
                    Mds.builder().build(this).connect(it.address,  object : MdsConnectionListener {
                        override fun onConnect(s: String) {
                            Log.d(TAG, "onConnect :$s")
                        }

                        override fun onConnectionComplete(macAddress: String, serial: String) {
                            Log.d(TAG, "Connected :$macAddress --> $serial")
                        }

                        override fun onError(e: MdsException) {
                            Log.e(TAG, "onError:$e")
                        }

                        override fun onDisconnect(bleAddress: String) {
                            Log.d(TAG, "Movesense onDisconnect: $bleAddress")
                        }
                    });
                }
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
                    characteristicWriteQueues.remove(deviceAddress)
                    isWritingCharacteristics.remove(deviceAddress)

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
                characteristicWriteQueues.remove(deviceAddress)
                isWritingCharacteristics.remove(deviceAddress)

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

                if (deviceName.contains("Movesense")) {
                    Handler(android.os.Looper.getMainLooper()).postDelayed({
                            movesenseGetInfo(gatt)
                        }, 5000L) // 5s for first, 6s for second, etc.

                    //return
                }

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
                            val intent = Intent(ACTION_CHARACTERISTIC_FOUND).apply {
                                putExtra(EXTRA_DEVICE_ADDRESS, gatt.device.address)
                                putExtra(EXTRA_CHARACTERISTIC_UUID, characteristicInfo.name) // Assuming mUuid holds the UUID string
                            }
                            sendBroadcast(intent)
                            Log.i(TAG, "Found configured characteristic: ${characteristicInfo.name}")

                            // Check if this is the Movesense Whiteboard Write Char
                            if (characteristicInfo.name == "Movesense GSD Write Char") {
                                val rate = 200;
                                val imuRate = 104;
                                // Check characteristic properties before writing
                                val canWrite = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                                val canWriteNoResponse = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

                                Log.i(TAG, "Movesense Write Char properties: Write=$canWrite, WriteNoResponse=$canWriteNoResponse, Properties=${characteristic.properties}")

                                if (canWrite || canWriteNoResponse) {

                                    val commands = listOf(
                                                    //  byteArrayOf(MS_GSP_COMMAND_ID, MS_GSP_ECG_ID) + "/Meas/ECG/${rate}".toByteArray(Charsets.UTF_8),
                                                    byteArrayOf(MS_GSP_COMMAND_ID, MS_GSP_IMU_ID) + "/Meas/IMU9/${imuRate}".toByteArray(Charsets.UTF_8),
                                                    byteArrayOf(MS_GSP_COMMAND_ID, MS_GSP_HR_ID) + "/Meas/HR".toByteArray(Charsets.UTF_8),
                                    )

                                    // Iterate over commands with delays
                                    commands.forEachIndexed { index, command ->
                                        Log.i(TAG, "Found Movesense GATT Sensor Data Write Char, queuing measurement command: ${command.contentToString()}")
                                        // Add a small delay to ensure all services are fully discovered before writing
                                        Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            queueCharacteristicWrite(gatt.device.address, characteristic, command)
                                        }, 5000L + (index * 1000L)) // 5s for first, 6s for second, etc.
                                    }

                                } else {
                                    Log.w(TAG, "Movesense Whiteboard Write Char does not support write operations")
                                }
                            }

                            // Enable notifications for known characteristics
//                            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
//                                gatt.setCharacteristicNotification(characteristic, true)
//
//                                // Enable the Client Characteristic Configuration Descriptor (CCCD)
//                                val desc_uuid = UUID.fromString(CCCD)
//                                val descriptor = characteristic.getDescriptor(desc_uuid)
//                                if (descriptor != null) {
//                                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
//                                    queueDescriptorWrite(gatt.device.address, descriptor)
//                                    enabledCharacteristics++
//                                    Log.i(TAG, "Queued notifications for ${characteristicInfo.name}")
//                                }
//                            }
                        } else {
                            Log.i(TAG, "characteristic ${characteristic.uuid.toString()} not found")
                        }
                    }
                }
                deviceConfigManager.findWhiteboardSpecs(deviceName)


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

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val deviceAddress = gatt.device.address
            isWritingCharacteristics[deviceAddress] = false

            when (status) {
                BluetoothGatt.GATT_SUCCESS -> {
                    Log.i(TAG, "Characteristic write successful for device $deviceAddress, UUID: ${characteristic.uuid}")
                }
                BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> {
                    Log.e(TAG, "Characteristic write failed: WRITE_NOT_PERMITTED for device $deviceAddress")
                }
                BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> {
                    Log.e(TAG, "Characteristic write failed: INVALID_ATTRIBUTE_LENGTH for device $deviceAddress")
                }
                BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> {
                    Log.e(TAG, "Characteristic write failed: INSUFFICIENT_AUTHENTICATION for device $deviceAddress")
                }
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> {
                    Log.e(TAG, "Characteristic write failed: REQUEST_NOT_SUPPORTED for device $deviceAddress")
                }
                BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> {
                    Log.e(TAG, "Characteristic write failed: INSUFFICIENT_ENCRYPTION for device $deviceAddress")
                }
                BluetoothGatt.GATT_CONNECTION_CONGESTED -> {
                    Log.w(TAG, "Characteristic write failed: CONNECTION_CONGESTED for device $deviceAddress")
                }
                else -> {
                    Log.e(TAG, "Characteristic write failed with unknown status: $status for device $deviceAddress")
                }
            }

            // Process next characteristic write in queue for this device
            processCharacteristicQueue(deviceAddress)
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

    private fun movesenseGetInfo(gatt: BluetoothGatt) {
        Mds.builder().build(this@BleAndMqttService)
            .get("suunto://MDS/whiteboard/info", null, object : MdsResponseListener {

                override fun onSuccess(data: String) {
                    Log.d(TAG, "ID: " + gatt.device.name + " OUTPUT: " + data);
                }

                override fun onError(error: MdsException) {
                    Log.e(TAG, "onError()", error);
                }

            })
        Mds.builder().build(this@BleAndMqttService)
            .get("suunto://223430000418/comm/ble/config", null, object : MdsResponseListener {
                override fun onSuccess(data: String) {
                    Log.d(
                        TAG,
                        "ID: " + gatt.device.name + " [GET]/Comm/Ble/Config " + " OUTPUT: " + data
                    );
                }

                override fun onError(error: MdsException) {
                    Log.e(
                        TAG,
                        " + gatt.device.name + " + "onError() [GET]/Comm/Ble/Config ",
                        error
                    );
                }
            })

        Mds.builder().build(this@BleAndMqttService)
            .get("suunto://223430000418/component/leds", null, object : MdsResponseListener {
                override fun onSuccess(data: String) {
                    Log.d(
                        TAG,
                        "ID: " + gatt.device.name + " [GET]/component/leds" + " OUTPUT: " + data
                    );
                }

                override fun onError(error: MdsException) {
                    Log.e(
                        TAG,
                        " + gatt.device.name + " + "onError() [GET]/component/leds ",
                        error
                    );
                }
            })
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

    private fun queueCharacteristicWrite(deviceAddress: String, characteristic: BluetoothGattCharacteristic, data: ByteArray) {
        characteristicWriteQueues[deviceAddress]?.add(CharacteristicWrite(characteristic, data))
        processCharacteristicQueue(deviceAddress)
    }

    private fun processCharacteristicQueue(deviceAddress: String) {
        val queue = characteristicWriteQueues[deviceAddress] ?: return
        val isWriting = isWritingCharacteristics[deviceAddress] ?: false

        if (isWriting || queue.isEmpty()) {
            Log.d(TAG, "Skipping characteristic queue processing: isWriting=$isWriting, queueEmpty=${queue.isEmpty()}")
            return
        }

        val write = queue.removeAt(0)
        isWritingCharacteristics[deviceAddress] = true

        Log.d(TAG, "Processing characteristic write for device $deviceAddress, data: ${write.data.contentToString()}")

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission for characteristic write")
            isWritingCharacteristics[deviceAddress] = false
            return
        }

        val gatt = gattConnections[deviceAddress]
        if (gatt == null) {
            Log.w(TAG, "No GATT connection found for device $deviceAddress")
            isWritingCharacteristics[deviceAddress] = false
            return
        }

        // Check GATT connection state
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectionState = bluetoothManager.getConnectionState(gatt.device, BluetoothProfile.GATT)
        Log.d(TAG, "GATT connection state: $connectionState (2=CONNECTED)")

        if (connectionState != BluetoothProfile.STATE_CONNECTED) {
            Log.w(TAG, "GATT not connected, cannot write characteristic")
            isWritingCharacteristics[deviceAddress] = false
            return
        }

        // Check characteristic write permissions
        val canWrite = (write.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val canWriteNoResponse = (write.characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        Log.d(TAG, "Characteristic write capabilities: canWrite=$canWrite, canWriteNoResponse=$canWriteNoResponse, properties=${write.characteristic.properties}")

        if (!canWrite && !canWriteNoResponse) {
            Log.w(TAG, "Characteristic does not support write operations. Properties: ${write.characteristic.properties}")
            isWritingCharacteristics[deviceAddress] = false
            processCharacteristicQueue(deviceAddress) // Process next item
            return
        }

        // Try WRITE_TYPE_NO_RESPONSE first (often more reliable for MoveSense)
        var success = false

        if (canWriteNoResponse) {
            write.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            write.characteristic.value = write.data
            success = gatt.writeCharacteristic(write.characteristic)
            Log.d(TAG, "Attempted WRITE_TYPE_NO_RESPONSE: success=$success")
        }

        // If no response write failed and normal write is supported, try that
        if (!success && canWrite) {
            write.characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            write.characteristic.value = write.data
            success = gatt.writeCharacteristic(write.characteristic)
            Log.d(TAG, "Attempted WRITE_TYPE_DEFAULT: success=$success")
        }

        Log.i(TAG, "Characteristic write initiated: success=$success for device $deviceAddress, UUID: ${write.characteristic.uuid}")

        if (!success) {
            Log.e(TAG, "Failed to initiate characteristic write for device $deviceAddress. All write types failed.")
            isWritingCharacteristics[deviceAddress] = false
            // Process next item in queue
            processCharacteristicQueue(deviceAddress)
        }
    }
}