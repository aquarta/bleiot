package it.unisalento.bleiot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.util.concurrent.TimeUnit

class RemoteConfigManager private constructor(private val context: Context) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val deviceConfigManager = DeviceConfigurationManager.getInstance(context)
    private val yaml = Yaml()
    
    suspend fun downloadAndSaveConfig(configUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Downloading config from: $configUrl")
            
            val request = Request.Builder()
                .url(configUrl)
                .build()
                
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }
            
            val yamlContent = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))
            
            Log.d(TAG, "Downloaded YAML content (${yamlContent.length} chars)")
            
            // Parse YAML
            val parsedConfig = parseYamlConfig(yamlContent)
                ?: return@withContext Result.failure(IOException("Failed to parse YAML"))
            
            // Save to local storage
            deviceConfigManager.saveConfiguration(parsedConfig)
            
            // Also save raw YAML for backup
            saveRawYaml(yamlContent)
            
            Log.i(TAG, "Config downloaded and saved successfully. Found ${parsedConfig.devices.size} devices")
            Result.success("Config downloaded successfully. Found ${parsedConfig.devices.size} devices.")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading config", e)
            Result.failure(e)
        }
    }
    
    private fun parseYamlConfig(yamlContent: String): DeviceConfiguration? {
        return try {
            val yamlMap = yaml.load<Map<String, Any>>(yamlContent)
            
            val devices = mutableMapOf<String, DeviceInfo>()
            val dataTypes = mutableMapOf<String, DataTypeInfo>()
            
            // Parse devices
            (yamlMap["devices"] as? Map<String, Any>)?.forEach { (deviceKey, deviceData) ->
                val deviceMap = deviceData as? Map<String, Any> ?: return@forEach
                val deviceInfo = parseDeviceInfo(deviceMap)
                if (deviceInfo != null) {
                    devices[deviceKey] = deviceInfo
                }
            }
            
            // Parse data types
            (yamlMap["dataTypes"] as? Map<String, Any>)?.forEach { (typeKey, typeData) ->
                val typeMap = typeData as? Map<String, Any> ?: return@forEach
                val dataTypeInfo = parseDataTypeInfo(typeMap)
                if (dataTypeInfo != null) {
                    dataTypes[typeKey] = dataTypeInfo
                }
            }
            
            DeviceConfiguration(devices, dataTypes)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing YAML", e)
            null
        }
    }
    
    private fun parseDeviceInfo(deviceMap: Map<String, Any>): DeviceInfo? {
        return try {
            val name = deviceMap["name"] as? String ?: return null
            val shortName = deviceMap["shortName"] as? String ?: name
            val servicesList = deviceMap["services"] as? List<Map<String, Any>> ?: emptyList()
            
            val services = servicesList.mapNotNull { serviceMap ->
                parseServiceInfo(serviceMap)
            }
            
            DeviceInfo(name, shortName, services)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing device info", e)
            null
        }
    }
    
    private fun parseServiceInfo(serviceMap: Map<String, Any>): ServiceInfo? {
        return try {
            val uuid = serviceMap["uuid"] as? String ?: return null
            val name = serviceMap["name"] as? String ?: "Unknown Service"
            val characteristicsList = serviceMap["characteristics"] as? List<Map<String, Any>> ?: emptyList()
            
            val characteristics = characteristicsList.mapNotNull { charMap ->
                parseCharacteristicInfo(charMap)
            }
            
            ServiceInfo(uuid, name, characteristics)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing service info", e)
            null
        }
    }
    
    private fun parseCharacteristicInfo(charMap: Map<String, Any>): CharacteristicInfo? {
        return try {
            val uuid = charMap["uuid"] as? String ?: return null
            val name = charMap["name"] as? String ?: "Unknown Characteristic"
            val dataType = charMap["dataType"] as? String ?: return null
            val mqttTopic = charMap["mqttTopic"] as? String ?: "ble/data"
            val customParser = charMap["customParser"] as? String
            
            CharacteristicInfo(uuid, name, dataType, mqttTopic, customParser)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing characteristic info", e)
            null
        }
    }
    
    private fun parseDataTypeInfo(typeMap: Map<String, Any>): DataTypeInfo? {
        return try {
            val size = typeMap["size"] as? String ?: return null
            val conversion = typeMap["conversion"] as? String ?: return null
            val description = typeMap["description"] as? String
            
            DataTypeInfo(size, conversion, description)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing data type info", e)
            null
        }
    }
    
    private fun saveRawYaml(yamlContent: String) {
        try {
            val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPreferences.edit()
                .putString(KEY_RAW_YAML, yamlContent)
                .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving raw YAML", e)
        }
    }
    
    fun getLastUpdateTime(): Long {
        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPreferences.getLong(KEY_LAST_UPDATE, 0)
    }
    
    fun getRawYaml(): String? {
        val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sharedPreferences.getString(KEY_RAW_YAML, null)
    }
    
    companion object {
        private const val TAG = "RemoteConfigManager"
        private const val PREFS_NAME = "remote_config"
        private const val KEY_RAW_YAML = "raw_yaml"
        private const val KEY_LAST_UPDATE = "last_update"
        
        @Volatile
        private var INSTANCE: RemoteConfigManager? = null
        
        fun getInstance(context: Context): RemoteConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RemoteConfigManager(context).also { INSTANCE = it }
            }
        }
    }
}