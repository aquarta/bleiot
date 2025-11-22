package it.unisalento.bleiot

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class DeviceConfiguration(
    val devices: Map<String, DeviceInfo> = emptyMap(),
    val dataTypes: Map<String, DataTypeInfo> = emptyMap()
)

data class DeviceInfo(
    val name: String,
    val shortName: String,
    val services: List<ServiceInfo> = emptyList(),
    val whiteboardMeasures: List<WhiteboardMeasure> = emptyList(),
)

data class ServiceInfo(
    val uuid: String,
    val name: String,
    val characteristics: List<CharacteristicInfo> = emptyList()
)

data class WhiteboardMeasure(

    val name: String,
    val methods: List<String> = emptyList(),
    val path: String,
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
            }?.let { Log.d(TAG,"Device config found ${deviceName} --> ${it}"); return it }
        }

        return null
    }

    fun findWhiteboardSpecs(deviceName: String?): List<WhiteboardMeasure>? {
        val deviceConfig = findDeviceConfig(deviceName) ?: return null
        return deviceConfig.whiteboardMeasures

//        for (whiteBoardMeasure in deviceConfig.whiteboardMeasures) {
//            Log.d(TAG, "whiteBoardMeasure Pair Found:  ${deviceName} ${whiteBoardMeasure}")
//        }
    }

    fun findConfChar(deviceName: String, charName: String ): CharacteristicInfo? {
        val deviceConfig = findDeviceConfig(deviceName) ?: return null

        for (service in deviceConfig.services) {
            //Log.d(TAG, "See if match serviceUuid: ${serviceUuid} ${service.uuid}")
            for (characteristic in service.characteristics) {
                if (characteristic.name == charName) {
                    Log.d(TAG, "Pair Found:  ${deviceName} ${service.uuid} ${characteristic}")
                    return characteristic
                }

            }
        }
        return null
    }

    fun findServiceAndCharacteristic(deviceName: String?, serviceUuid: String, characteristicUuid: String): Pair<ServiceInfo, CharacteristicInfo>? {
        val deviceConfig = findDeviceConfig(deviceName) ?: return null
        //Log.i(TAG, "See if deviceName: ${deviceName} ${deviceConfig}")

        for (service in deviceConfig.services) {
            //Log.d(TAG, "See if match serviceUuid: ${serviceUuid} ${service.uuid}")
            if (service.uuid.equals(serviceUuid, ignoreCase = true)) {
                for (characteristic in service.characteristics) {
                    //Log.d(TAG, "See if match characteristic: ${characteristicUuid} --> ${characteristic}")
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
                                Log.i(TAG,"${charJson}");
                                characteristicsArray.put(charJson)
                            }
                            put("characteristics", characteristicsArray)
                        }
                        servicesArray.put(serviceJson)
                    }
                    put("services", servicesArray)
                }
                // Add this block to serialize whiteboard measures
                if (device.whiteboardMeasures.isNotEmpty()) {
                    val wbJson = JSONObject()
                    val measuresArray = JSONArray()
                    device.whiteboardMeasures.forEach { measure ->
                        val measureJson = JSONObject()
                        measureJson.put("name", measure.name)
                        measureJson.put("path", measure.path)

                        val methodsArray = JSONArray()
                        measure.methods.forEach { method ->
                            methodsArray.put(method)
                        }
                        measureJson.put("methods", methodsArray)

                        measuresArray.put(measureJson)
                    }
                    wbJson.put("measures", measuresArray)

                    // IMPORTANT: The key here must match what deserialize expects ("movesense_whiteboard")
                    deviceJson.put("movesense_whiteboard", wbJson)
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
                // used by Movesense
                val whiteboardMeasures = mutableListOf<WhiteboardMeasure>()
                val movesenseWhiteboard = deviceJson.optJSONObject("movesense_whiteboard")
                if (movesenseWhiteboard != null) {
                    val movesenseWhiteboardMeasuredArray = movesenseWhiteboard.optJSONArray("measures")
                    movesenseWhiteboardMeasuredArray?.let { array ->
                        for (i in 0 until array.length()) {
                            val measure = array.getJSONObject(i)
                            val methodsList = mutableListOf<String>()

                            val methodsJsonArray = measure.optJSONArray("methods")
                            if (methodsJsonArray != null) {
                                for (k in 0 until methodsJsonArray.length()) {
                                    methodsList.add(methodsJsonArray.getString(k))
                                }
                            }
                            whiteboardMeasures.add(
                                WhiteboardMeasure(
                                    name = measure.getString("name"),
                                    methods = methodsList,
                                    path = measure.getString("path")
                                )
                            )
                        }
                    }
                }


                devices[deviceKey] = DeviceInfo(
                    name = deviceJson.getString("name"),
                    shortName = deviceJson.getString("shortName"),
                    services = services,
                    whiteboardMeasures = whiteboardMeasures,

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