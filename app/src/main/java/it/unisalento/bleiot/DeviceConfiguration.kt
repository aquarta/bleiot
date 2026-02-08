package it.unisalento.bleiot

import it.unisalento.bleiot.BleDataParsers
import GenericStructParser
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DeviceConfiguration(
    val devices: Map<String, DeviceInfo> = emptyMap(),
    val dataTypes: Map<String, DataTypeInfo> = emptyMap()
)

@Serializable
data class DeviceInfo(
    val name: String,
    val shortName: String,
    val address: String? = null,
    val services: List<ServiceInfo> = emptyList(),
    @SerialName("movesense_whiteboard")
    val whiteboardMeasuresWrapper: WhiteboardMeasuresWrapper? = null,
) {
    val whiteboardMeasures: List<WhiteboardMeasure>
        get() = whiteboardMeasuresWrapper?.measures ?: emptyList()
}

@Serializable
data class WhiteboardMeasuresWrapper(
    val measures: List<WhiteboardMeasure> = emptyList()
)

@Serializable
data class ServiceInfo(
    val uuid: String,
    val name: String,
    val characteristics: List<CharacteristicInfo> = emptyList()
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class WhiteboardMeasure(
    val name: String,
    val methods: List<String> = emptyList(),
    val path: String,
    val mqttTopic: String? = null,
)

@Serializable
data class CharacteristicInfo(
    val uuid: String,
    val name: String,
    val dataType: String? = null,
    val mqttTopic: String,
    val customParser: String? = null,
    val structParser: StructParserConfig? = null
)

@Serializable
data class DataTypeInfo(
    val size: String,
    val conversion: String,
    val description: String? = null
)

@Serializable
data class StructParserConfig(
    val endianness: String = "LITTLE_ENDIAN",
    val fields: List<StructField> = emptyList()
)

@Serializable
data class StructField(
    val name: String,
    val type: String
)

@Singleton
class DeviceConfigurationManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var currentConfig: DeviceConfiguration? = null
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
        coerceInputValues = true
    }
    
    fun getDeviceConfiguration(): DeviceConfiguration? {
        if (currentConfig == null) {
            loadConfigFromStorage()
        }
        return currentConfig
    }
    
    fun saveConfiguration(config: DeviceConfiguration) {
        currentConfig = config
        try {
            val jsonString = json.encodeToString(config)
            sharedPreferences.edit()
                .putString(KEY_DEVICE_CONFIG, jsonString)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving configuration: ${e.message}")
        }
    }
    
    private fun loadConfigFromStorage() {
        val jsonString = sharedPreferences.getString(KEY_DEVICE_CONFIG, null)
        if (jsonString != null) {
            try {
                currentConfig = json.decodeFromString<DeviceConfiguration>(jsonString)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading configuration from storage: ${e.message}")
            }
        }
    }
    
    fun findDeviceConfig(deviceName: String?): DeviceInfo? {
        return findDeviceConfig(deviceName, null)
    }

    fun findDeviceConfig(deviceName: String?, deviceAddress: String?): DeviceInfo? {
        val config = getDeviceConfiguration() ?: return null
        
        // Iterate through all configured devices to find a match
        return config.devices.values.find { deviceConfig ->
            // Name Match Logic
            val nameMatches = deviceName != null && (
                deviceConfig.name.contains(deviceName, ignoreCase = true) ||
                deviceConfig.shortName.contains(deviceName, ignoreCase = true) ||
                deviceName.contains(deviceConfig.name, ignoreCase = true) ||
                deviceName.contains(deviceConfig.shortName, ignoreCase = true)
            )
            
            // Address Match Logic (only if configured)
            val addressMatches = if (deviceConfig.address != null) {
                deviceAddress != null && deviceConfig.address.equals(deviceAddress, ignoreCase = true)
            } else {
                true // Config doesn't specify address, so address doesn't matter
            }

            nameMatches && addressMatches
        }
    }

    fun findWhiteboardSpecs(deviceName: String?, deviceAddress: String?): List<WhiteboardMeasure> {
        Log.d(TAG, "findWhiteboardSpecs: $deviceName ${findDeviceConfig(deviceName, deviceAddress)}")
        return findDeviceConfig(deviceName, deviceAddress)?.whiteboardMeasures ?: emptyList()
    }

    fun findConfChar(deviceName: String, charName: String, deviceAddress: String? = null): CharacteristicInfo? {
        val deviceConfig = findDeviceConfig(deviceName, deviceAddress) ?: return null
        deviceConfig.services.forEach { service ->
            service.characteristics.find { it.name == charName }?.let { return it }
        }
        return null
    }

    fun findMeasurePath(deviceName: String, deviceAddress: String?, measureName: String): WhiteboardMeasure? {
        return findDeviceConfig(deviceName, deviceAddress)?.whiteboardMeasures?.find { it.name == measureName }
    }

    fun findServiceAndCharacteristic(deviceName: String?, serviceUuid: String, characteristicUuid: String, deviceAddress: String? = null): Pair<ServiceInfo, CharacteristicInfo>? {
        val deviceConfig = findDeviceConfig(deviceName, deviceAddress) ?: return null
        deviceConfig.services.find { it.uuid.equals(serviceUuid, ignoreCase = true) }?.let { service ->
            service.characteristics.find { it.uuid.equals(characteristicUuid, ignoreCase = true) }?.let { char ->
                return Pair(service, char)
            }
        }
        return null
    }

    fun findCharacteristicByUuid(characteristicUuid: String): CharacteristicInfo? {
        val config = getDeviceConfiguration() ?: return null
        config.devices.values.forEach { device ->
            device.services.forEach { service ->
                service.characteristics.find { it.uuid.equals(characteristicUuid, ignoreCase = true) }?.let { return it }
            }
        }
        return null
    }
    
    fun parseCharacteristicData(characteristicInfo: CharacteristicInfo, data: ByteArray): Any? {
        if (characteristicInfo.structParser != null) {
            return GenericStructParser(characteristicInfo.structParser).unpack(data)
        }
        return when (characteristicInfo.dataType) {
            "4_byte_double" -> BleDataParsers.parse4ByteDouble(data)
            "4_byte_integer" -> BleDataParsers.parse4ByteInteger(data)
            "4_byte_float" -> BleDataParsers.parse4ByteFloat(data)
            "custom_temperature" -> BleDataParsers.parseCustomTemperature(data)
            "STBatteryStruct" -> BleDataParsers.parseSTBatteryStruct(data)
            "ble_heartrate_hrm" -> BleDataParsers.parseHeartRateMeasurement(data)
            "ble_battery_level" -> BleDataParsers.parseBatteryLevel(data)
            "movesense_read" -> BleDataParsers.parseMoveSenseChar(data)
            else -> {
                Log.w(TAG, "Unknown data type: ${characteristicInfo.dataType}")
                null
            }
        }
    }

    companion object {
        private const val TAG = "DeviceConfigManager"
        private const val PREFS_NAME = "device_config"
        private const val KEY_DEVICE_CONFIG = "device_configuration"
    }
}