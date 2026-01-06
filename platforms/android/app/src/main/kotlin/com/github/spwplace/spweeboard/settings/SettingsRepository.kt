package com.github.spwplace.spweeboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore instance for app settings.
 */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "spweeboard_settings")

/**
 * Theme mode options.
 */
enum class ThemeMode {
    System,
    Light,
    Dark
}

/**
 * Inference parameters for LLM.
 */
data class InferenceParams(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 128,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f
)

/**
 * All app settings.
 */
data class SpweeboardSettings(
    val hapticEnabled: Boolean = true,
    val streamingEnabled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.System,
    val defaultGroundId: String? = null,
    val lastUsedModelPath: String? = null,
    val inferenceParams: InferenceParams = InferenceParams()
)

/**
 * Repository for managing app settings using DataStore.
 * Provides type-safe access to all settings with Flow-based observation.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val HAPTIC_ENABLED = booleanPreferencesKey("haptic_enabled")
        val STREAMING_ENABLED = booleanPreferencesKey("streaming_enabled")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DEFAULT_GROUND_ID = stringPreferencesKey("default_ground_id")
        val LAST_USED_MODEL_PATH = stringPreferencesKey("last_used_model_path")

        // Inference params
        val TEMPERATURE = floatPreferencesKey("inference_temperature")
        val MAX_TOKENS = intPreferencesKey("inference_max_tokens")
        val TOP_P = floatPreferencesKey("inference_top_p")
        val TOP_K = intPreferencesKey("inference_top_k")
        val REPEAT_PENALTY = floatPreferencesKey("inference_repeat_penalty")
    }

    /**
     * Flow of all settings. Emits whenever any setting changes.
     */
    val settings: Flow<SpweeboardSettings> = context.settingsDataStore.data.map { prefs ->
        SpweeboardSettings(
            hapticEnabled = prefs[Keys.HAPTIC_ENABLED] ?: true,
            streamingEnabled = prefs[Keys.STREAMING_ENABLED] ?: true,
            themeMode = prefs[Keys.THEME_MODE]?.let { ThemeMode.valueOf(it) } ?: ThemeMode.System,
            defaultGroundId = prefs[Keys.DEFAULT_GROUND_ID],
            lastUsedModelPath = prefs[Keys.LAST_USED_MODEL_PATH],
            inferenceParams = InferenceParams(
                temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
                maxTokens = prefs[Keys.MAX_TOKENS] ?: 128,
                topP = prefs[Keys.TOP_P] ?: 0.9f,
                topK = prefs[Keys.TOP_K] ?: 40,
                repeatPenalty = prefs[Keys.REPEAT_PENALTY] ?: 1.1f
            )
        )
    }

    /**
     * Flow of just haptic enabled state.
     */
    val hapticEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.HAPTIC_ENABLED] ?: true
    }

    /**
     * Flow of just streaming enabled state.
     */
    val streamingEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.STREAMING_ENABLED] ?: true
    }

    /**
     * Flow of theme mode.
     */
    val themeMode: Flow<ThemeMode> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.THEME_MODE]?.let { ThemeMode.valueOf(it) } ?: ThemeMode.System
    }

    /**
     * Flow of inference parameters.
     */
    val inferenceParams: Flow<InferenceParams> = context.settingsDataStore.data.map { prefs ->
        InferenceParams(
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
            maxTokens = prefs[Keys.MAX_TOKENS] ?: 128,
            topP = prefs[Keys.TOP_P] ?: 0.9f,
            topK = prefs[Keys.TOP_K] ?: 40,
            repeatPenalty = prefs[Keys.REPEAT_PENALTY] ?: 1.1f
        )
    }

    // Individual setters

    suspend fun setHapticEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.HAPTIC_ENABLED] = enabled
        }
    }

    suspend fun setStreamingEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.STREAMING_ENABLED] = enabled
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THEME_MODE] = mode.name
        }
    }

    suspend fun setDefaultGroundId(groundId: String?) {
        context.settingsDataStore.edit { prefs ->
            if (groundId != null) {
                prefs[Keys.DEFAULT_GROUND_ID] = groundId
            } else {
                prefs.remove(Keys.DEFAULT_GROUND_ID)
            }
        }
    }

    suspend fun setLastUsedModelPath(path: String?) {
        context.settingsDataStore.edit { prefs ->
            if (path != null) {
                prefs[Keys.LAST_USED_MODEL_PATH] = path
            } else {
                prefs.remove(Keys.LAST_USED_MODEL_PATH)
            }
        }
    }

    suspend fun setInferenceParams(params: InferenceParams) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TEMPERATURE] = params.temperature
            prefs[Keys.MAX_TOKENS] = params.maxTokens
            prefs[Keys.TOP_P] = params.topP
            prefs[Keys.TOP_K] = params.topK
            prefs[Keys.REPEAT_PENALTY] = params.repeatPenalty
        }
    }

    suspend fun setTemperature(temperature: Float) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TEMPERATURE] = temperature.coerceIn(0f, 2f)
        }
    }

    suspend fun setMaxTokens(maxTokens: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.MAX_TOKENS] = maxTokens.coerceIn(16, 2048)
        }
    }

    /**
     * Reset all settings to defaults.
     */
    suspend fun resetToDefaults() {
        context.settingsDataStore.edit { prefs ->
            prefs.clear()
        }
    }

    companion object {
        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
