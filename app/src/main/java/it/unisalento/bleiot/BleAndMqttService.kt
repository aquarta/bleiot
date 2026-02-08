package it.unisalento.bleiot

import android.Manifest
import android.app.*
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.activity.result.launch
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.movesense.mds.Mds
import com.movesense.mds.MdsException
import com.movesense.mds.MdsNotificationListener
import com.movesense.mds.MdsSubscription
import it.unisalento.bleiot.ble.BleManager
import it.unisalento.bleiot.data.SensorDataManager
import it.unisalento.bleiot.mqtt.MqttManager
import it.unisalento.bleiot.repositories.BleRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import javax.inject.Inject
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException

private const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
private val serviceScope = CoroutineScope(Dispatchers.IO)

@AndroidEntryPoint
class BleAndMqttService : Service() {
    private val TAG = "BleAndMqttService"
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "ble_mqtt_service_channel"

    // Components
    @Inject lateinit var bleRepository: BleRepository
    @Inject lateinit var bleManager: BleManager
    @Inject lateinit var mqttManager: MqttManager
    @Inject lateinit var sensorDataManager: SensorDataManager
    @Inject lateinit var deviceConfigManager: DeviceConfigurationManager

    // Wakelock
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }
    
    // Subscriptions map
    private val mSubscriptions = mutableMapOf<String, MdsSubscription>()

    // Binder for activity communication
    private val binder = LocalBinder()

    // Legacy callbacks (to be removed once ViewModel uses Repository)
    private var statusCallback: ((String) -> Unit)? = null
    private var dataCallback: ((String) -> Unit)? = null
    private var phyCallback: ((String, Int, Int) -> Unit)? = null
    private var supportedPhyCallback: ((String, String) -> Unit)? = null
    private var rssiCallback: ((String, Int) -> Unit)? = null

    companion object {
        const val ACTION_CHARACTERISTIC_FOUND = "it.unisalento.bleiot.ACTION_CHARACTERISTIC_FOUND"
        const val ACTION_WHITEBOARD_FOUND = "it.unisalento.bleiot.ACTION_WHITEBOARD_FOUND"
        const val ACTION_ENABLE_CHAR_NOTIFY = "it.unisalento.bleiot.ACTION_ENABLE_CHAR_NOTIFY"
        const val ACTION_DISABLE_CHAR_NOTIFY = "it.unisalento.bleiot.ACTION_DISABLE_CHAR_NOTIFY"
        const val EXTRA_DEVICE_ADDRESS = "it.unisalento.bleiot.EXTRA_DEVICE_ADDRESS"
        const val EXTRA_CHARACTERISTIC_NAME = "it.unisalento.bleiot.EXTRA_CHARACTERISTIC_NAME"
        const val EXTRA_CHARACTERISTIC_PROPERTIES = "it.unisalento.bleiot.EXTRA_CHARACTERISTIC_PROPERTIES"
        const val EXTRA_WHITEBOARD = "it.unisalento.bleiot.EXTRA_WHITEBOARD"
        const val ACTION_ENABLE_WHITEBOARD_SUBSCRIBE = "it.unisalento.bleiot.ACTION_ENABLE_WHITEBOARD_SUBSCRIBE"
        const val EXTRA_WHITEBOARD_MEASURE = "it.unisalento.bleiot.EXTRA_WHITEBOARD_MEASURE"
        const val ACTION_READ_CHAR = "it.unisalento.bleiot.ACTION_READ_CHAR"
        const val ACTION_WRITE_CHAR = "it.unisalento.bleiot.ACTION_WRITE_CHAR"
        const val EXTRA_CHARACTERISTIC_VALUE = "it.unisalento.bleiot.EXTRA_CHARACTERISTIC_VALUE"
        const val ACTION_WHITEBOARD_UNSUBSCRIBE = "it.unisalento.bleiot.ACTION_WHITEBOARD_UNSUBSCRIBE"
    }

    inner class LocalBinder : Binder() {
        fun getService(): BleAndMqttService = this@BleAndMqttService
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate called")

        createNotificationChannel()
        // Call startForeground immediately to satisfy Android requirements
        val notification = createNotification("Service starting...")
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground in onCreate", e)
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BleIot::Wakelock")
        wakeLock?.acquire()

        // Setup listeners
        mqttManager.onStatusUpdate = { status -> bleRepository.updateStatus(status) }
        
        bleManager.onCharacteristicDataReceived = { gatt, value, serviceUuid, charUuid ->
            sensorDataManager.processCharacteristicData(gatt, value, serviceUuid, charUuid)
        }
        
        bleManager.onCharacteristicFound = @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) { address, name, props ->
            sendBroadcast(Intent(ACTION_CHARACTERISTIC_FOUND).apply {
                putExtra(EXTRA_DEVICE_ADDRESS, address)
                putExtra(EXTRA_CHARACTERISTIC_NAME, name)
                putExtra(EXTRA_CHARACTERISTIC_PROPERTIES, props)
            })
            
            // Auto Notify Check
            if (AppConfigurationSettings.getInstance(this).getAppConfig().autoNotify) {
                if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                     val gatt = bleManager.getGatt(address)
                     val deviceName = gatt?.device?.name ?: "Unknown"
                     val charInfo = deviceConfigManager.findConfChar(deviceName, name, address)
                                    ?: deviceConfigManager.findCharacteristicByUuid(name)
                     Log.i(TAG, "Autonotify check for $name $address $props result $charInfo")
                     if (charInfo != null) {
                         enableNotifications(address, charInfo.name)
                     }
                }
            }
        }
        
        bleManager.onWhiteboardFound = @androidx.annotation.RequiresPermission(android.Manifest.permission.BLUETOOTH_CONNECT) { address, name ->
            Log.d(TAG, "Whiteboard found: $address, $name")
            sendBroadcast(Intent(ACTION_WHITEBOARD_FOUND).apply {
                putExtra(EXTRA_DEVICE_ADDRESS, address)
                putExtra(EXTRA_WHITEBOARD, name)
            })

            // Auto Subscribe Check
            if (AppConfigurationSettings.getInstance(this).getAppConfig().autoNotify) {
                Handler(Looper.getMainLooper()).postDelayed( {
                    enableSubscriptionForWhiteBoardMeasure(address, name)
                }, 3000)
            }
        }

        // Connect Repository flows to legacy callbacks
        bleRepository.statusText.onEach { statusCallback?.invoke(it) }.launchIn(serviceScope)
        bleRepository.latestData.onEach { dataCallback?.invoke(it) }.launchIn(serviceScope)

        mqttManager.connect()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called with action: ${intent?.action}")
        
        // Try to update notification, but don't crash if we can't start foreground again
        try {
            val notification = createNotification("Service is running")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not update foreground service state: ${e.message}")
        }

        when (intent?.action) {
            "CONNECT_BLE" -> intent.getStringExtra("deviceAddress")?.let { bleManager.connectToDevice(it) }
            "DISCONNECT_BLE" -> intent.getStringExtra("deviceAddress")?.let { bleManager.disconnect(it) }
            "STOP_SERVICE" -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_ENABLE_CHAR_NOTIFY -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val charName = intent.getStringExtra(EXTRA_CHARACTERISTIC_NAME)
                Log.d(TAG, "Start notification on called with action: ${charName} ${address}")
                if (address != null && charName != null) enableNotifications(address, charName)
            }
            ACTION_DISABLE_CHAR_NOTIFY -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val charName = intent.getStringExtra(EXTRA_CHARACTERISTIC_NAME)
                if (address != null && charName != null) disableNotifications(address, charName)
            }
            ACTION_ENABLE_WHITEBOARD_SUBSCRIBE -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val measureName = intent.getStringExtra(EXTRA_WHITEBOARD_MEASURE)
                if (address != null && measureName != null) {
                    enableSubscriptionForWhiteBoardMeasure(address, measureName)
                }
            }
            ACTION_WHITEBOARD_UNSUBSCRIBE -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                val measureName = intent.getStringExtra(EXTRA_WHITEBOARD_MEASURE)
                if (address != null && measureName != null) {
                    disableSubscriptionForWhiteBoardMeasure(address, measureName)
                }
            }
        }

        return START_STICKY
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableNotifications(address: String, charName: String) {
        val gatt = bleManager.getGatt(address) ?: return
        val characteristicInfo = deviceConfigManager.findConfChar(gatt.device.name ?: "Unknown", charName, address)
        
        if (characteristicInfo == null) {
            Log.w(TAG, "Failed to find characteristic config for $charName on device $address")
            return
        }
        
        gatt.services.forEach { service ->
            service.characteristics.find { it.uuid.toString() == characteristicInfo.uuid }?.let { char ->
                if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    gatt.setCharacteristicNotification(char, true)
                    char.getDescriptor(UUID.fromString(CCCD))?.let { desc ->
                        desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        bleManager.queueDescriptorWrite(address, desc)
                    }
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun disableNotifications(address: String, charName: String) {
        val gatt = bleManager.getGatt(address) ?: return
        val characteristicInfo = deviceConfigManager.findConfChar(gatt.device.name ?: "Unknown", charName, address) ?: return
        
        gatt.services.forEach { service ->
            service.characteristics.find { it.uuid.toString() == characteristicInfo.uuid }?.let { char ->
                gatt.setCharacteristicNotification(char, false)
                char.getDescriptor(UUID.fromString(CCCD))?.let { desc ->
                    desc.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    bleManager.queueDescriptorWrite(address, desc)
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableSubscriptionForWhiteBoardMeasure(address: String, measureName: String) {
        val gatt = bleManager.getGatt(address) ?: return
        val whiteboardMeasure = deviceConfigManager.findMeasurePath(gatt.device.name ?: "Unknown", address, measureName) ?: return
        val movesenseSerial = bleManager.getMovesenseSerial(address)

        if (movesenseSerial == null) {
            Log.w(TAG, "No serial found for Movesense device $address")
            return
        }

        Log.i(TAG, "enableSubscriptionForWhiteBoardMeasure $whiteboardMeasure --> ${whiteboardMeasure.path}")
        
        if (whiteboardMeasure.methods.contains("subscribe")) {
            val mSub = Mds.builder().build(this).subscribe(
                "suunto://MDS/EventListener",
                "{\"Uri\": \"${movesenseSerial}${whiteboardMeasure.path}\"}",
                object : MdsNotificationListener {
                    override fun onNotification(data: String) {
                        try {
                            val mutableData = JSONObject(data)
                            mutableData.put("deviceName", "Movesense $movesenseSerial")
                            mutableData.put("deviceAddress", address)
                            mutableData.put("gatewayName", bluetoothAdapter?.name ?: "Unknown")
                            mutableData.put("gatewayBattery", getBatteryLevel())

                            if (mutableData.has("Body")) {
                                val body = mutableData.getJSONObject("Body")
                                if (body.has("Samples")) {
                                    val samplesArray = body.getJSONArray("Samples")
                                    val baseTimestamp = body.getLong("Timestamp")
                                    val newSamplesArray = JSONArray()

                                    for (i in 0 until samplesArray.length()) {
                                        val sampleValue = samplesArray.getInt(i)
                                        val calculatedTimestamp = baseTimestamp + (8 * i) // Assuming 125Hz approx
                                        val sampleObj = JSONObject()
                                        sampleObj.put("value", sampleValue)
                                        sampleObj.put("stimestamp", calculatedTimestamp)
                                        newSamplesArray.put(sampleObj)
                                    }
                                    body.put("Samples", newSamplesArray)
                                }
                            }
                            
                            val jsonString = mutableData.toString()
                            bleRepository.updateData("$measureName: $jsonString")
                            whiteboardMeasure.mqttTopic?.let { topic ->
                                mqttManager.publish(topic, jsonString)
                            }
                        } catch (e: JSONException) {
                            Log.e(TAG, "Error parsing JSON data: $data", e)
                        }
                    }

                    override fun onError(error: MdsException) {
                        Log.e(TAG, "MDS onError: $error")
                    }
                }
            )
            mSubscriptions[whiteboardMeasure.path] = mSub
            bleRepository.updateWhiteboardSubscriptionState(address, measureName, true)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun disableSubscriptionForWhiteBoardMeasure(address: String, measureName: String) {
        val gatt = bleManager.getGatt(address) ?: return
        val whiteboardMeasure = deviceConfigManager.findMeasurePath(gatt.device.name ?: "Unknown", address, measureName) ?: return

        val subscription = mSubscriptions[whiteboardMeasure.path]
        if (subscription != null) {
            subscription.unsubscribe()
            mSubscriptions.remove(whiteboardMeasure.path)
            Log.i(TAG, "Unsubscribed from whiteboard measure: $measureName")
            bleRepository.updateWhiteboardSubscriptionState(address, measureName, false)
        }
    }

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        bleManager.disconnect()
        mqttManager.disconnect()
    }

    fun setCallbacks(status: (String) -> Unit, data: (String) -> Unit, phy: (String, Int, Int) -> Unit, supPhy: (String, String) -> Unit, rssi: (String, Int) -> Unit) {
        this.statusCallback = status
        this.dataCallback = data
        this.phyCallback = phy
        this.supportedPhyCallback = supPhy
        this.rssiCallback = rssi
    }

    fun setAppTagName(address: String, tagName: String) {
        bleRepository.updateDeviceAppTagName(address, tagName)
    }

    fun getConnectedDeviceAddresses(): Set<String> {
        return bleRepository.scannedDevices.value.filter { it.value.isConnected }.keys
    }

    fun reloadMqttSettings() {
        mqttManager.disconnect()
        mqttManager.connect()
    }

    fun setPreferredPhy(address: String, txPhy: Int, rxPhy: Int, options: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bleManager.setPreferredPhy(address, txPhy, rxPhy, options)
        }
    }

    fun requestConnectionPriority(address: String, priority: Int) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bleManager.requestConnectionPriority(address, priority)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "BLE MQTT Service", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stopIntent = PendingIntent.getService(this, 0, Intent(this, BleAndMqttService::class.java).apply { action = "STOP_SERVICE" }, flags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BLE MQTT Service").setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent).build()
    }
}
