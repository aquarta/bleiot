package it.unisalento.bleiot

import android.content.Context

class ExperimentSettings private constructor(context: Context) {

    private val sharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveExperimentServerUrl(url: String) {
        sharedPreferences.edit().putString(KEY_EXPERIMENT_SERVER_URL, url).apply()
    }

    fun getExperimentServerUrl(): String {
        return sharedPreferences.getString(KEY_EXPERIMENT_SERVER_URL, "http://127.0.0.1:5001") ?: "http://127.0.0.1:5001"
    }

    fun saveSelectedExperimentId(id: String) {
        sharedPreferences.edit().putString(KEY_SELECTED_EXPERIMENT_ID, id).apply()
    }

    fun getSelectedExperimentId(): String {
        return sharedPreferences.getString(KEY_SELECTED_EXPERIMENT_ID, "None") ?: "None"
    }

    companion object {
        private const val PREFS_NAME = "experiment_settings"
        private const val KEY_EXPERIMENT_SERVER_URL = "experiment_server_url"
        private const val KEY_SELECTED_EXPERIMENT_ID = "selected_experiment_id"

        @Volatile
        private var INSTANCE: ExperimentSettings? = null

        fun getInstance(context: Context): ExperimentSettings {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ExperimentSettings(context).also { INSTANCE = it }
            }
        }
    }
}
