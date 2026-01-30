package it.unisalento.bleiot

import android.content.Context
import android.util.Log
import it.unisalento.bleiot.Experiment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.Result.Companion.failure

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteConfigManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceConfigManager: DeviceConfigurationManager
) {
    private val TAG = "RemoteConfigManager"
    private val GET_EXPERIMENTS_REST_PATH = "config/experiments"
    private val GET_EXPERIMENT_CONFIG_PATH = "config/experiment"
    private val KEY_RAW_YAML = "raw_yaml"
    private val KEY_LAST_UPDATE = "last_update"
    private val PREFS_NAME = "remote_config"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val yaml = Yaml()
    private val json = Json { 
        ignoreUnknownKeys = true 
        isLenient = true
        encodeDefaults = true
    }
    
    suspend fun getExperiments(serverUrl: String): List<Experiment> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$serverUrl/${GET_EXPERIMENTS_REST_PATH}")
            .build()
        
        val response = httpClient.newCall(request).execute()
        
        if (!response.isSuccessful) {
            Log.e(TAG,"HTTP ${response.code}: ${response.message}")
            throw IOException("HTTP ${response.code}: ${response.message}")
        }
        val jsonStr = response.body?.string() ?: throw IOException("Empty response body")
        return@withContext json.decodeFromString<List<Experiment>>(jsonStr)
    }

    suspend fun downloadAndSaveConfig(configUrl: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(configUrl).build()
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) return@withContext failure(IOException("HTTP ${response.code}"))
            
            val yamlContent = response.body?.string() ?: return@withContext failure(IOException("Empty body"))
            val parsedConfig = parseYamlConfig(yamlContent) ?: return@withContext failure(IOException("Parse failed"))
            
            deviceConfigManager.saveConfiguration(parsedConfig)
            saveRawYaml(yamlContent)
            
            Result.success("Config updated: ${parsedConfig.devices.size} devices")
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            failure(e)
        }
    }

    private fun parseYamlConfig(yamlContent: String): DeviceConfiguration? {
        return try {
            val yamlMap = yaml.load<Map<String, Any>>(yamlContent)
            val jsonElement = valueToJsonElement(yamlMap)
            json.decodeFromJsonElement<DeviceConfiguration>(jsonElement)
        } catch (e: Exception) {
            Log.e(TAG, "YAML parse error", e)
            null
        }
    }

    private fun valueToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is String -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.map { it.key.toString() to valueToJsonElement(it.value) }.toMap())
            is List<*> -> JsonArray(value.map { valueToJsonElement(it) })
            else -> JsonPrimitive(value.toString())
        }
    }

    suspend fun getExperimentConfig(serverUrl: String, id: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$serverUrl/$GET_EXPERIMENT_CONFIG_PATH/$id").build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            
            val body = response.body?.string() ?: ""
            parseYamlConfig(body)?.let {
                deviceConfigManager.saveConfiguration(it)
                saveRawYaml(body)
            }
            body
        } catch (e: Exception) {
            Log.e(TAG, "Experiment config error", e)
            ""
        }
    }
    
    private fun saveRawYaml(yamlContent: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_RAW_YAML, yamlContent)
            .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
            .apply()
    }
    
    fun getLastUpdateTime(): Long = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getLong(KEY_LAST_UPDATE, 0)
        fun getRawYaml(): String? = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_RAW_YAML, null)
        
        companion object {
            private const val TAG = "RemoteConfigManager"
            private const val GET_EXPERIMENT_CONFIG_PATH = "config/experiment"
        }
    }
    