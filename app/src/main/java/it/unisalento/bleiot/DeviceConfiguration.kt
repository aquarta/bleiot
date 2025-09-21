package it.unisalento.bleiot

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

data class DeviceConfiguration(
    val devices: Map<String, DeviceInfo> = emptyMap(),
    val dataTypes: Map<String, DataTypeInfo> = emptyMap()
)

data class DeviceInfo(
    val name: String,
    val shortName: String,
    val services: List<ServiceInfo> = emptyList()
)

data class ServiceInfo(
    val uuid: String,
    val name: String,
    val characteristics: List<CharacteristicInfo> = emptyList()
)

data class CharacteristicInfo(
    val uuid: String,
    val name: String,
    val dataType: String,
    val mqttTopic: String,
    val customParser: String? = null
)

data class DataTypeInfo(
    val size: String,
    val conversion: String,
    val description: String? = null
)

class DeviceConfigurationManager private constructor(context: Context) {
    private val context = context.applicationContext
    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var currentConfig: DeviceConfiguration? = null
    
    fun getDeviceConfiguration(): DeviceConfiguration? {
        if (currentConfig == null) {
            loadConfigFromStorage()
        }
        return currentConfig
    }
    
    fun saveConfiguration(config: DeviceConfiguration) {
        currentConfig = config
        // Save to SharedPreferences as JSON for persistence
        val jsonString = serializeToJson(config)
        sharedPreferences.edit()
            .putString(KEY_DEVICE_CONFIG, jsonString)
            .apply()
    }
    
    fun findDeviceConfig(deviceName: String?): DeviceInfo? {
        val config = getDeviceConfiguration() ?: return null
        
        // Try exact match first
        config.devices[deviceName]?.let { return it }
        
        // Try partial match with device name
        deviceName?.let { name ->
            config.devices.values.find { device ->
                device.name.contains(name, ignoreCase = true) ||
                device.shortName.contains(name, ignoreCase = true) ||
                name.contains(device.name, ignoreCase = true) ||
                name.contains(device.shortName, ignoreCase = true)
            }?.let { return it }
        }
        
        return null
    }
    
    fun findServiceAndCharacteristic(deviceName: String?, serviceUuid: String, characteristicUuid: String): Pair<ServiceInfo, CharacteristicInfo>? {
        val deviceConfig = findDeviceConfig(deviceName) ?: return null
        Log.i(TAG, "See if deviceName: ${deviceName} ${deviceConfig}")
        
        for (service in deviceConfig.services) {
            Log.i(TAG, "See if match serviceUuid: ${serviceUuid} ${service.uuid}")
            if (service.uuid.equals(serviceUuid, ignoreCase = true)) {
                for (characteristic in service.characteristics) {
                    Log.i(TAG, "See if match characteristic: ${characteristic}")
                    if (characteristic.uuid.equals(characteristicUuid, ignoreCase = true)) {
                        Log.d(TAG, "Pair Found:  ${deviceName} ${serviceUuid} ${characteristic}")
                        return Pair(service, characteristic)
                    }
                }
            }
        }
        return null
    }

    fun findCharacteristicByUuid(characteristicUuid: String): CharacteristicInfo? {
        val config = getDeviceConfiguration() ?: return null

        // Search through all devices, services, and characteristics
        for (device in config.devices.values) {
            for (service in device.services) {
                for (characteristic in service.characteristics) {
                    if (characteristic.uuid.equals(characteristicUuid, ignoreCase = true)) {
                        Log.d(TAG, "Found characteristic by UUID: ${characteristic.name} (${characteristicUuid})")
                        return characteristic
                    }
                }
            }
        }
        return null
    }
    
    fun parseCharacteristicData(characteristicInfo: CharacteristicInfo, data: ByteArray, deviceName: String? = null, deviceAddress: String? = null): Any? {
        val config = getDeviceConfiguration() ?: return null
        val dataTypeInfo = config.dataTypes[characteristicInfo.dataType]
        
        return when (characteristicInfo.dataType) {
            "4_byte_double" -> {
                if (data.size >= 4) {
                    val intValue = data.sliceArray(0 until 4).foldIndexed(0) { index, acc, byte ->
                        acc or ((byte.toInt() and 0xFF) shl (8 * index))
                    }
                    intValue.toDouble()
                } else null
            }
            "4_byte_integer" -> {
                if (data.size >= 4) {
                    data.sliceArray(0 until 4).foldIndexed(0) { index, acc, byte ->
                        acc or ((byte.toInt() and 0xFF) shl (8 * index))
                    }
                } else null
            }
            "4_byte_float" -> {
                if (data.size >= 4) {
                    val intValue = data.sliceArray(0 until 4).foldIndexed(0) { index, acc, byte ->
                        acc or ((byte.toInt() and 0xFF) shl (8 * index))
                    }
                    intValue.toFloat()
                } else null
            }
            "custom_temperature" -> {
                // Use existing custom parsing logic
                if (data.size >= 6) {
                    val tempValue = data.sliceArray(6 until data.size).foldIndexed(0) { index, acc, byte ->
                        acc or ((byte.toInt() and 0xFF) shl (8 * index))
                    }
                    (tempValue / 10).toDouble()
                    // Return as a structured map
                    mapOf(
                        "temperature" to tempValue,
                    )
                } else null
            }
            "STBatteryStruct" -> {
                if (data.size >= 9) {
                    // Parse according to the BLE_BatteryUpdate struct format
                    // STORE_LE_16(buff, (HAL_GetTick() / 10));
                    val timestamp = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)

                    // STORE_LE_16(buff + 2, (BatteryLevel * 10U));
                    val batteryLevelRaw = (data[2].toInt() and 0xFF) or ((data[3].toInt() and 0xFF) shl 8)
                    val batteryLevel = batteryLevelRaw / 10.0

                    // STORE_LE_16(buff + 4, (Voltage));
                    val voltage = (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8)

                    // STORE_LE_16(buff + 6, (Current));
                    val current = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8)

                    // buff[8] = (uint8_t)Status;
                    val status = data[8].toInt() and 0xFF

                    // Return as a structured map
                    val ret = mapOf(
                        "timestamp" to timestamp,
                        "batteryLevel" to batteryLevel,
                        "voltage" to voltage,
                        "current" to current,
                        "status" to status
                    )
                    Log.w(TAG, "Battery Parsed : ${ret}")
                    return ret

                } else null
            }
            else -> {
                Log.w(TAG, "Unknown data type: ${characteristicInfo.dataType}")
                null
            }
        }
    }
    
    private fun loadConfigFromStorage() {
        val jsonString = sharedPreferences.getString(KEY_DEVICE_CONFIG, null)
        if (jsonString != null) {
            currentConfig = deserializeFromJson(jsonString)
        }
    }
    
    private fun serializeToJson(config: DeviceConfiguration): String {
        try {
            val json = JSONObject()

            // Serialize devices
            val devicesJson = JSONObject()
            config.devices.forEach { (key, device) ->
                val deviceJson = JSONObject().apply {
                    put("name", device.name)
                    put("shortName", device.shortName)

                    val servicesArray = JSONArray()
                    device.services.forEach { service ->
                        val serviceJson = JSONObject().apply {
                            put("uuid", service.uuid)
                            put("name", service.name)

                            val characteristicsArray = JSONArray()
                            service.characteristics.forEach { characteristic ->
                                val charJson = JSONObject().apply {
                                    put("uuid", characteristic.uuid)
                                    put("name", characteristic.name)
                                    put("dataType", characteristic.dataType)
                                    put("mqttTopic", characteristic.mqttTopic)
                                    characteristic.customParser?.let { put("customParser", it) }
                                }
                                characteristicsArray.put(charJson)
                            }
                            put("characteristics", characteristicsArray)
                        }
                        servicesArray.put(serviceJson)
                    }
                    put("services", servicesArray)
                }
                devicesJson.put(key, deviceJson)
            }
            json.put("devices", devicesJson)

            // Serialize dataTypes
            val dataTypesJson = JSONObject()
            config.dataTypes.forEach { (key, dataType) ->
                val dataTypeJson = JSONObject().apply {
                    put("size", dataType.size)
                    put("conversion", dataType.conversion)
                    dataType.description?.let { put("description", it) }
                }
                dataTypesJson.put(key, dataTypeJson)
            }
            json.put("dataTypes", dataTypesJson)

            return json.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Error serializing config to JSON: ${e.message}")
            return "{}"
        }
    }

    private fun deserializeFromJson(jsonString: String): DeviceConfiguration? {
        try {
            val json = JSONObject(jsonString)

            // Deserialize devices
            val devices = mutableMapOf<String, DeviceInfo>()
            val devicesJson = json.optJSONObject("devices")
            devicesJson?.keys()?.forEach { deviceKey ->
                val deviceJson = devicesJson.getJSONObject(deviceKey)

                val services = mutableListOf<ServiceInfo>()
                val servicesArray = deviceJson.optJSONArray("services")
                servicesArray?.let { array ->
                    for (i in 0 until array.length()) {
                        val serviceJson = array.getJSONObject(i)

                        val characteristics = mutableListOf<CharacteristicInfo>()
                        val characteristicsArray = serviceJson.optJSONArray("characteristics")
                        characteristicsArray?.let { charArray ->
                            for (j in 0 until charArray.length()) {
                                val charJson = charArray.getJSONObject(j)
                                characteristics.add(
                                    CharacteristicInfo(
                                        uuid = charJson.getString("uuid"),
                                        name = charJson.getString("name"),
                                        dataType = charJson.getString("dataType"),
                                        mqttTopic = charJson.getString("mqttTopic"),
                                        customParser = charJson.optString("customParser").takeIf { it.isNotEmpty() }
                                    )
                                )
                            }
                        }

                        services.add(
                            ServiceInfo(
                                uuid = serviceJson.getString("uuid"),
                                name = serviceJson.getString("name"),
                                characteristics = characteristics
                            )
                        )
                    }
                }

                devices[deviceKey] = DeviceInfo(
                    name = deviceJson.getString("name"),
                    shortName = deviceJson.getString("shortName"),
                    services = services
                )
            }

            // Deserialize dataTypes
            val dataTypes = mutableMapOf<String, DataTypeInfo>()
            val dataTypesJson = json.optJSONObject("dataTypes")
            dataTypesJson?.keys()?.forEach { dataTypeKey ->
                val dataTypeJson = dataTypesJson.getJSONObject(dataTypeKey)
                dataTypes[dataTypeKey] = DataTypeInfo(
                    size = dataTypeJson.getString("size"),
                    conversion = dataTypeJson.getString("conversion"),
                    description = dataTypeJson.optString("description").takeIf { it.isNotEmpty() }
                )
            }

            return DeviceConfiguration(devices = devices, dataTypes = dataTypes)
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing config from JSON: ${e.message}")
            return null
        }
    }
    
    companion object {
        private const val TAG = "DeviceConfigManager"
        private const val PREFS_NAME = "device_config"
        private const val KEY_DEVICE_CONFIG = "device_configuration"
        
        @Volatile
        private var INSTANCE: DeviceConfigurationManager? = null
        
        fun getInstance(context: Context): DeviceConfigurationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceConfigurationManager(context).also { INSTANCE = it }
            }
        }
    }
}