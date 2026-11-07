package com.coke.nestspace.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LlmConfigState(
    val configs: List<LlmConfig> = emptyList(),
    val activeId: String = ""
)

class LlmConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("llm_config", Context.MODE_PRIVATE)
    private val lock: Any get() = LOCK

    fun load(): LlmConfigState = synchronized(lock) {
        val stored = prefs.getString(KEY_CONFIGS, null)
        if (stored != null) {
            val (storedConfigs, needsSave) = parseConfigs(stored)
            val configs = storedConfigs.filterNot { it.isUnusedGeneratedDefault() }
            val activeId = prefs.getString(KEY_ACTIVE_ID, "").orEmpty()
                .takeIf { id -> configs.any { it.id == id } }
                ?: configs.firstOrNull()?.id.orEmpty()
            val state = LlmConfigState(configs, activeId)
            if (needsSave || configs.size != storedConfigs.size) save(state)
            return state
        }

        migrateLegacy()?.let { migrated ->
            save(migrated)
            return migrated
        }

        val state = LlmConfigState()
        save(state)
        return state
    }

    fun save(state: LlmConfigState) = synchronized(lock) {
        val array = JSONArray()
        state.configs.forEach { config ->
            // org.json rejects NaN/Infinity when writing a double. Keep a
            // malformed editor value from crashing the save path.
            val temperature = config.temperature
                .takeIf { it.isFinite() }
                ?.coerceIn(0.0, 2.0)
                ?: 0.7
            array.put(
                JSONObject()
                    .put("id", config.id)
                    .put("name", config.name)
                    .put("protocol", config.protocol.name)
                    .put("baseUrl", config.baseUrl)
                    .put("apiKey", ApiKeyCipher.encrypt(config.apiKey))
                    .put("model", config.model)
                    .put("contextTokens", config.contextTokens)
                    .put("maxToolRounds", config.maxToolRounds)
                    .put("maxTokens", config.maxTokens)
                    .put("temperature", temperature)
                    .put("reasoningEffort", config.reasoningEffort.name)
            )
        }
        prefs.edit()
            .putString(KEY_CONFIGS, array.toString())
            .putString(KEY_ACTIVE_ID, state.activeId)
            .remove(KEY_PROTOCOL)
            .remove(KEY_BASE_URL)
            .remove(KEY_API_KEY)
            .remove(KEY_MODEL)
            .remove(KEY_SYSTEM_PROMPT)
            .remove(KEY_MAX_TOKENS)
            .remove(KEY_TEMPERATURE)
            .remove(KEY_LEGACY_REQUEST_PATH)
            .remove(KEY_LEGACY_CUSTOM_AUTH_HEADER)
            .remove(KEY_LEGACY_CUSTOM_AUTH_PREFIX)
            .remove(KEY_LEGACY_CUSTOM_HEADERS)
            .remove(KEY_LEGACY_CUSTOM_BODY)
            .remove(KEY_LEGACY_CUSTOM_RESPONSE_PATH)
            .apply()
    }

    private fun migrateLegacy(): LlmConfigState? {
        val hasLegacy = listOf(KEY_PROTOCOL, KEY_BASE_URL, KEY_API_KEY, KEY_MODEL)
            .any { prefs.contains(it) }
        if (!hasLegacy) return null

        val stored = prefs.getString(KEY_PROTOCOL, LlmProtocol.ANTHROPIC.name).orEmpty()
        val known = LlmProtocol.entries.any { it.name == stored }
        val protocol = LlmProtocol.entries.firstOrNull { it.name == stored }
            ?: LlmProtocol.ANTHROPIC
        val defaults = LlmConfig.defaultsFor(protocol)
        val baseUrl = if (known) {
            prefs.getString(KEY_BASE_URL, defaults.baseUrl).orEmpty()
        } else {
            defaults.baseUrl
        }
        val config = LlmConfig(
            id = UUID.randomUUID().toString(),
            name = when {
                protocol == LlmProtocol.OPENAI -> "OpenAI"
                else -> "Claude"
            },
            protocol = protocol,
            baseUrl = baseUrl,
            apiKey = if (known) prefs.getString(KEY_API_KEY, "").orEmpty() else "",
            model = if (known) {
                prefs.getString(KEY_MODEL, defaults.model).orEmpty()
            } else {
                defaults.model
            },
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 2048),
            temperature = prefs.getString(KEY_TEMPERATURE, "0.7")?.toDoubleOrNull() ?: 0.7
        )
        val normalized = config.withCompleteEndpoint()
        return LlmConfigState(listOf(normalized), normalized.id)
    }

    private fun parseConfigs(raw: String): Pair<List<LlmConfig>, Boolean> = runCatching {
        val array = JSONArray(raw)
        var needsSave = false
        val configs = (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            val protocol = LlmProtocol.entries
                .firstOrNull { it.name == json.optString("protocol") }
                ?: LlmProtocol.ANTHROPIC
            val defaults = LlmConfig.defaultsFor(protocol)
            val storedKey = json.optString("apiKey", "")
            if (storedKey.isNotBlank() && !ApiKeyCipher.isEncrypted(storedKey)) {
                needsSave = true
            }
            val config = LlmConfig(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = json.optString("name"),
                protocol = protocol,
                baseUrl = json.optString("baseUrl", defaults.baseUrl),
                apiKey = ApiKeyCipher.decrypt(storedKey),
                model = json.optString("model", defaults.model),
                contextTokens = json.optInt(
                    "contextTokens",
                    LlmConfig.DEFAULT_CONTEXT_TOKENS
                ).coerceAtLeast(LlmConfig.MIN_CONTEXT_TOKENS),
                maxToolRounds = json.optInt(
                    "maxToolRounds",
                    LlmConfig.DEFAULT_MAX_TOOL_ROUNDS
                ).coerceIn(LlmConfig.MIN_TOOL_ROUNDS, LlmConfig.MAX_TOOL_ROUNDS),
                maxTokens = json.optInt("maxTokens", 2048),
                temperature = json.optDouble("temperature", 0.7),
                reasoningEffort = ReasoningEffort.entries.firstOrNull {
                    it.name == json.optString("reasoningEffort")
                } ?: ReasoningEffort.MEDIUM
            )
            val normalized = config.withCompleteEndpoint()
            if (normalized != config) needsSave = true
            normalized
        }
        configs to needsSave
    }.getOrDefault(emptyList<LlmConfig>() to false)

    private fun LlmConfig.withCompleteEndpoint(): LlmConfig {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) return this
        val suffix = when (protocol) {
            LlmProtocol.ANTHROPIC -> {
                if (url.endsWith("/messages")) return copy(baseUrl = url)
                if (url.endsWith("/v1")) "/messages" else "/v1/messages"
            }
            LlmProtocol.OPENAI -> {
                if (url.endsWith("/chat/completions")) return copy(baseUrl = url)
                if (Regex("/v\\d+$").containsMatchIn(url)) {
                    "/chat/completions"
                } else {
                    "/v1/chat/completions"
                }
            }
        }
        return copy(baseUrl = url + suffix)
    }

    private fun LlmConfig.isUnusedGeneratedDefault(): Boolean =
        id == LEGACY_GENERATED_DEFAULT_ID &&
            protocol == LlmProtocol.ANTHROPIC &&
            name == "Claude" &&
            baseUrl == LlmConfig.DEFAULT_ANTHROPIC_BASE &&
            model == LlmConfig.DEFAULT_ANTHROPIC_MODEL &&
            apiKey.isBlank()

    companion object {
        // Shared across instances; settings UI and assistant runtime each
        // create their own store but must not race on the config list.
        private val LOCK = Any()
        private const val LEGACY_GENERATED_DEFAULT_ID = "claude-default"

        private const val KEY_CONFIGS = "configs"
        private const val KEY_ACTIVE_ID = "active_id"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SYSTEM_PROMPT = "system_prompt"
        private const val KEY_MAX_TOKENS = "max_tokens"
        private const val KEY_TEMPERATURE = "temperature"

        private const val KEY_LEGACY_REQUEST_PATH = "request_path"
        private const val KEY_LEGACY_CUSTOM_AUTH_HEADER = "custom_auth_header"
        private const val KEY_LEGACY_CUSTOM_AUTH_PREFIX = "custom_auth_prefix"
        private const val KEY_LEGACY_CUSTOM_HEADERS = "custom_headers"
        private const val KEY_LEGACY_CUSTOM_BODY = "custom_body"
        private const val KEY_LEGACY_CUSTOM_RESPONSE_PATH = "custom_response_path"
    }
}
