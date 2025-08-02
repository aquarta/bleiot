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
import java.util.*

class BleAndMqttService : Service() {
    private val TAG = "BleAndMqttService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "ble_mqtt_service_channel"

    // MQTT properties
    private var mqttClient: MqttClient? = null
    private val MQTT_SERVER_URI = "tcp://broker.hivemq.com:1883"
    private val MQTT_CLIENT_ID = "AndroidBleClient" + System.currentTimeMillis()
    private val MQTT_TOPIC = "ble/temperature"
    private val MQTT_USERNAME = "your_username" // Optional
    private val MQTT_PASSWORD = "your_password" // Optional

    // BLE properties
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null

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
                Log.i(TAG, "received device disconnection command")
                disconnectBle()
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
            mqttClient = MqttClient(
                MQTT_SERVER_URI,
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
            updateStatus("Connected to MQTT broker")

        } catch (e: MqttException) {
            Log.e(TAG, "Error setting up MQTT client: ${e.message}")
            updateStatus("MQTT connection failed: ${e.message}")
        }
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
                connectedDevice = it
                updateStatus("Connecting to ${it.name ?: "Unknown Device"}...")
                bluetoothGatt = it.connectGatt(this, true, gattCallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to device: ${e.message}")
            updateStatus("Error connecting: ${e.message}")
        }
    }

    private fun disconnectBle() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        ) {
            return
        }

        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        connectedDevice = null
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
                    Log.i(TAG, "Disconnected from GATT server.")
                    updateStatus("Disconnected from device")
                    updateNotification("Disconnected from BLE device")
                    updateData("")
                }
            } else {
                Log.w(TAG, "Error $status encountered! Disconnecting...")
                updateStatus("Connection error: $status")
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered")

                // Find our service and characteristic
                val service = gatt.getService(SERVICE_UUID)
                if (service != null) {
                    val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
                    if (characteristic != null) {
                        if (ActivityCompat.checkSelfPermission(
                                this@BleAndMqttService,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ) {
                            return
                        }

                        // Enable notifications
                        gatt.setCharacteristicNotification(characteristic, true)

                        // Enable the Client Characteristic Configuration Descriptor (CCCD)
                        val desc_uuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        val descriptor = characteristic.getDescriptor(desc_uuid)
                        if (descriptor != null) {
                            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            gatt.writeDescriptor(descriptor)
                            updateStatus("Notifications enabled")
                        }
                    } else {
                        updateStatus("Characteristic not found")
                    }
                } else {
                    updateStatus("Service not found")
                }
            } else {
                updateStatus("Service discovery failed: $status")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val data = parseTemperatureDeta(value)
                val formattedData = "Temperature: $data C"
                updateData(formattedData)
                publishToMqtt(MQTT_TOPIC, data.toString())
            }
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
                if (characteristic.uuid == CHARACTERISTIC_UUID) {
                    val data = parseTemperatureDeta(value)
                    val formattedData = "Temperature: $data C"
                    updateData(formattedData)
                    publishToMqtt(MQTT_TOPIC, data.toString())
                }
            }
        }
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
}