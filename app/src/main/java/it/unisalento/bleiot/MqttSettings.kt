package it.unisalento.bleiot

import android.content.Context
import android.content.SharedPreferences

data class MqttConfig(
    val server: String = "broker.hivemq.com",
    val port: Int = 1883
)

class MqttSettings private constructor(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    fun saveMqttConfig(config: MqttConfig) {
        sharedPreferences.edit().apply {
            putString(KEY_MQTT_SERVER, config.server)
            putInt(KEY_MQTT_PORT, config.port)
            apply()
        }
    }

    fun getMqttConfig(): MqttConfig {
        return MqttConfig(
            server = sharedPreferences.getString(KEY_MQTT_SERVER, "broker.hivemq.com") ?: "broker.hivemq.com",
            port = sharedPreferences.getInt(KEY_MQTT_PORT, 1883)
        )
    }

    companion object {
        private const val PREFS_NAME = "mqtt_settings"
        private const val KEY_MQTT_SERVER = "mqtt_server"
        private const val KEY_MQTT_PORT = "mqtt_port"

        @Volatile
        private var INSTANCE: MqttSettings? = null

        fun getInstance(context: Context): MqttSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MqttSettings(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}