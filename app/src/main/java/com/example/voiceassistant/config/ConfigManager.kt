package com.example.voiceassistant.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuration manager using SharedPreferences.
 *
 * Settings:
 * - API Key (cloud backend)
 * - Backend type (cloud / local)
 * - Base URL (cloud or local)
 * - Model name
 * - Wake word sensitivity
 * - Max recording duration
 */
class ConfigManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voice_assistant_config", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BACKEND_TYPE = "backend_type"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_SENSITIVITY = "wake_sensitivity"
        private const val KEY_MAX_RECORDING = "max_recording_seconds"
        private const val KEY_OFFLINE_STT = "offline_stt_enabled"
        private const val KEY_ACCESS_KEY = "picovoice_access_key"
        private const val KEY_SPEECH_RATE = "speech_rate"

        const val BACKEND_CLOUD = "cloud"
        const val BACKEND_LOCAL = "local"

        const val DEFAULT_CLOUD_URL = "https://api.deepseek.com"
        const val DEFAULT_CLOUD_MODEL = "deepseek-chat"
        const val DEFAULT_LOCAL_URL = "http://192.168.1.100:11434"
        const val DEFAULT_LOCAL_MODEL = "qwen2.5:7b"
        const val DEFAULT_SENSITIVITY = 0.7f
        const val DEFAULT_MAX_RECORDING = 30
        const val DEFAULT_SPEECH_RATE = 1.3f
    }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var picovoiceAccessKey: String
        get() = prefs.getString(KEY_ACCESS_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ACCESS_KEY, value).apply()

    var backendType: String
        get() = prefs.getString(KEY_BACKEND_TYPE, BACKEND_CLOUD) ?: BACKEND_CLOUD
        set(value) = prefs.edit().putString(KEY_BACKEND_TYPE, value).apply()

    var baseUrl: String
        get() {
            val default = if (backendType == BACKEND_CLOUD) DEFAULT_CLOUD_URL else DEFAULT_LOCAL_URL
            return prefs.getString(KEY_BASE_URL, default) ?: default
        }
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var model: String
        get() {
            val default = if (backendType == BACKEND_CLOUD) DEFAULT_CLOUD_MODEL else DEFAULT_LOCAL_MODEL
            return prefs.getString(KEY_MODEL, default) ?: default
        }
        set(value) = prefs.edit().putString(KEY_MODEL, value).apply()

    var wakeSensitivity: Float
        get() = prefs.getFloat(KEY_SENSITIVITY, DEFAULT_SENSITIVITY)
        set(value) = prefs.edit().putFloat(KEY_SENSITIVITY, value.coerceIn(0f, 1f)).apply()

    var maxRecordingSeconds: Int
        get() = prefs.getInt(KEY_MAX_RECORDING, DEFAULT_MAX_RECORDING)
        set(value) = prefs.edit().putInt(KEY_MAX_RECORDING, value.coerceIn(5, 120)).apply()

    var offlineSttEnabled: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_STT, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_STT, value).apply()

    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(0.5f, 2.5f)).apply()

    /**
     * Check if the cloud backend is configured (has API key).
     */
    fun isCloudConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * Check if Picovoice is configured.
     */
    fun isPicovoiceConfigured(): Boolean = picovoiceAccessKey.isNotBlank()
}
