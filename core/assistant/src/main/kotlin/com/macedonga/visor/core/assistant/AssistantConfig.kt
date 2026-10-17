package com.macedonga.visor.core.assistant

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the assistant answers. The key stays in this app's private storage. */
data class AssistantConfig(
    val enabled: Boolean = true,
    val provider: Provider = Provider.CLAUDE,
    val apiKey: String = "",
    val model: String = "",
    // The model that writes each day's lines in the diary; empty, the answers' model.
    val linesModel: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val webSearch: Boolean = false,
    // Let a visual question use the glasses' camera.
    val vision: Boolean = true,
    // Answers in the glasses' own voice, the one that reads notifications; off, the phone's voice streamed to them.
    val glassesVoice: Boolean = true,
    val spokenProgress: Boolean = false,
    val instructions: String = "",
    // How long a conversation is remembered after its last question.
    val memoryMinutes: Int = 10,
)

class AssistantConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<AssistantConfig> = _config.asStateFlow()

    fun update(change: (AssistantConfig) -> AssistantConfig) {
        val next = change(_config.value)
        prefs.edit()
            .putBoolean("enabled", next.enabled)
            .putString("provider", next.provider.name)
            .putString("api_key", next.apiKey)
            .putString("model", next.model)
            .putString("lines_model", next.linesModel)
            .putString("base_url", next.baseUrl)
            .putBoolean("web_search", next.webSearch)
            .putBoolean("vision", next.vision)
            .putBoolean("glasses_voice", next.glassesVoice)
            .putBoolean("spoken_progress", next.spokenProgress)
            .putString("instructions", next.instructions)
            .putInt("memory_minutes", next.memoryMinutes)
            .apply()
        _config.value = next
    }

    private fun load(): AssistantConfig {
        val d = AssistantConfig()
        return AssistantConfig(
            enabled = prefs.getBoolean("enabled", d.enabled),
            provider = runCatching { Provider.valueOf(prefs.getString("provider", null).orEmpty()) }.getOrDefault(d.provider),
            apiKey = prefs.getString("api_key", d.apiKey).orEmpty(),
            model = prefs.getString("model", d.model).orEmpty(),
            linesModel = prefs.getString("lines_model", d.linesModel).orEmpty(),
            baseUrl = prefs.getString("base_url", d.baseUrl).orEmpty(),
            webSearch = prefs.getBoolean("web_search", d.webSearch),
            vision = prefs.getBoolean("vision", d.vision),
            glassesVoice = prefs.getBoolean("glasses_voice", d.glassesVoice),
            spokenProgress = prefs.getBoolean("spoken_progress", d.spokenProgress),
            instructions = prefs.getString("instructions", d.instructions).orEmpty(),
            memoryMinutes = prefs.getInt("memory_minutes", d.memoryMinutes),
        )
    }
}
