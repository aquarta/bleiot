package it.unisalento.bleiot.mqtt

import android.content.Context
import android.util.Log
import it.unisalento.bleiot.MqttSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MqttManager @Inject constructor(@ApplicationContext private val context: Context) : MqttCallbackExtended {
    private val TAG = "MqttManager"
    private var mqttClient: MqttClient? = null
    private val MQTT_CLIENT_ID = "AndroidBleClient" + System.currentTimeMillis()
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mqttSettings = MqttSettings.getInstance(context)

    // Callbacks to notify the service/repository of connection status
    var onStatusUpdate: ((String) -> Unit)? = null

    fun connect() {
        scope.launch(Dispatchers.IO) {
            try {
                val config = mqttSettings.getMqttConfig()
                val serverUri = "tcp://${config.server}:${config.port}"

                mqttClient = MqttClient(
                    serverUri,
                    MQTT_CLIENT_ID,
                    MemoryPersistence()
                )

                val options = MqttConnectOptions()
                if (config.user != null) {
                    options.userName = config.user
                }
                if (config.password != null) {
                    options.password = config.password.toCharArray()
                }
                options.isAutomaticReconnect = true
                options.isCleanSession = true
                mqttClient?.setCallback(this@MqttManager)
                
                Log.i(TAG, "Connecting to MQTT broker: $serverUri as ${options.userName}")
                mqttClient?.connect(options)
                onStatusUpdate?.invoke("Connected to MQTT broker at ${config.server}:${config.port}")

            } catch (e: MqttException) {
                Log.e(TAG, "Error setting up MQTT client: ${e.message}")
                onStatusUpdate?.invoke("MQTT connection failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        try {
            mqttClient?.disconnect()
            mqttClient?.close()
            mqttClient = null
        } catch (e: MqttException) {
            Log.e(TAG, "Error disconnecting MQTT: ${e.message}")
        }
    }

    fun publish(topic: String?, message: String) {
        if (topic == null) return
        
        scope.launch {
            try {
                if (mqttClient?.isConnected == true) {
                    val mqttMessage = MqttMessage(message.toByteArray())
                    mqttMessage.qos = 0
                    mqttClient?.publish(topic, mqttMessage)
                    Log.d(TAG, "Published to $topic: $message")
                } else {
                    Log.w(TAG, "MQTT client not connected, cannot publish to $topic")
                }
            } catch (e: MqttException) {
                Log.e(TAG, "Error publishing to MQTT: ${e.message}")
            }
        }
    }

    fun isConnected(): Boolean = mqttClient?.isConnected == true

    // MqttCallbackExtended implementation
    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        Log.i(TAG, "MQTT connection complete. Reconnect: $reconnect, URI: $serverURI")
        onStatusUpdate?.invoke("MQTT connection complete (reconnect: $reconnect)")
    }

    override fun connectionLost(cause: Throwable?) {
        Log.e(TAG, "MQTT connection lost: ${cause?.message}", cause)
        onStatusUpdate?.invoke("MQTT connection lost: ${cause?.message}")
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        Log.i(TAG, "MQTT message arrived: Topic=$topic, Message=${message.toString()}")
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {
        Log.d(TAG, "MQTT message delivered: Token=${token?.messageId}")
    }
}
