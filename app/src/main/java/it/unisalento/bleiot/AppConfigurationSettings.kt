package it.unisalento.bleiot

import android.content.Context
import android.content.SharedPreferences

data class BleScanConfig(
    val scanTime: Int = 10,
    val showOnlyKnownDevices: Boolean = false,
    val autoConnect: Boolean = false,
    val autoNotify: Boolean = false
)

class AppConfigurationSettings private constructor(context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    fun saveAppConfig(config: BleScanConfig) {
        sharedPreferences.edit().apply {            
            putInt(KEY_SCAN_TIME, config.scanTime)
            putBoolean(KEY_SHOW_ONLY_KNOWN, config.showOnlyKnownDevices)
            putBoolean(KEY_AUTO_CONNECT, config.autoConnect)
            putBoolean(KEY_AUTO_NOTIFY, config.autoNotify)
            apply()
        }
    }

    fun getAppConfig(): BleScanConfig {
        return BleScanConfig(
            scanTime = sharedPreferences.getInt(KEY_SCAN_TIME, 10),
            showOnlyKnownDevices = sharedPreferences.getBoolean(KEY_SHOW_ONLY_KNOWN, false),
            autoConnect = sharedPreferences.getBoolean(KEY_AUTO_CONNECT, false),
            autoNotify = sharedPreferences.getBoolean(KEY_AUTO_NOTIFY, false)
        )
    }


    companion object {
        private const val PREFS_NAME = "app_config_settings"
        private const val KEY_SCAN_TIME = "app_ble_scan_time"
        private const val KEY_SHOW_ONLY_KNOWN = "app_show_only_known"
        private const val KEY_AUTO_CONNECT = "app_auto_connect"
        private const val KEY_AUTO_NOTIFY = "app_auto_notify"
        
        @Volatile
        private var INSTANCE: AppConfigurationSettings? = null

        fun getInstance(context: Context): AppConfigurationSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppConfigurationSettings(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}