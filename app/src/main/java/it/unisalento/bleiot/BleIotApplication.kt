package it.unisalento.bleiot

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class BleIotApplication : Application() {
    private val TAG = "BleIotApplication"

    @Inject lateinit var deviceConfigManager: DeviceConfigurationManager

    override fun onCreate() {
        super.onCreate()

        // Initialize device configuration manager early
        initializeDeviceConfiguration()
    }

    private fun initializeDeviceConfiguration() {
        try {
            // Load configuration from storage or create default if none exists
            val existingConfig = deviceConfigManager.getDeviceConfiguration()

            if (existingConfig == null) {
                Log.i(TAG, "No existing device configuration found, creating default configuration")
                createDefaultConfiguration(deviceConfigManager)
            } else {
                Log.i(TAG, "Device configuration loaded successfully")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error initializing device configuration: ${e.message}")
        }
    }

    private fun createDefaultConfiguration(deviceConfigManager: DeviceConfigurationManager) {
        // Create a default configuration
        val defaultConfig = DeviceConfiguration(
            devices = mapOf(
                "STEVAL-STWINKT1B" to DeviceInfo(
                    name = "STEVAL-STWINKT1B",
                    shortName = "STWIN",
                    services = listOf(
                        ServiceInfo(
                            uuid = "00000000-0001-11e1-9ab4-0002a5d5c51b",
                            name = "MSSensorDemo Service",
                            characteristics = listOf(
                                CharacteristicInfo(
                                    uuid = "00140000-0001-11e1-ac36-0002a5d5c51b",
                                    name = "Temperature Data",
                                    dataType = "custom_temperature",
                                    mqttTopic = "ble/temperature",
                                    structParser = null
                                ),
                                CharacteristicInfo(
                                    uuid = "001c0000-0001-11e1-ac36-0002a5d5c51b",
                                    name = "Battery Data",
                                    dataType = "STBatteryStruct",
                                    mqttTopic = "ble/battery",
                                    structParser = null
                                )
                            )
                        )
                    )
                )
            ),
            dataTypes = mapOf(
                "custom_temperature" to DataTypeInfo(
                    size = "variable",
                    conversion = "custom",
                    description = "Temperature data with custom parsing"
                ),
                "STBatteryStruct" to DataTypeInfo(
                    size = "9_bytes",
                    conversion = "struct",
                    description = "ST Battery information structure"
                ),
                "4_byte_integer" to DataTypeInfo(
                    size = "4_bytes",
                    conversion = "little_endian_int",
                    description = "4-byte little-endian integer"
                ),
                "4_byte_double" to DataTypeInfo(
                    size = "4_bytes",
                    conversion = "little_endian_double",
                    description = "4-byte little-endian double"
                ),
                "4_byte_float" to DataTypeInfo(
                    size = "4_bytes",
                    conversion = "little_endian_float",
                    description = "4-byte little-endian float"
                )
            )
        )

        deviceConfigManager.saveConfiguration(defaultConfig)
        Log.i(TAG, "Default device configuration created and saved")
    }
}