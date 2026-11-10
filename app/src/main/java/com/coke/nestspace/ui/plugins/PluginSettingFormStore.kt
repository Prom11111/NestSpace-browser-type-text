package com.coke.nestspace.ui.plugins

import com.coke.nestspace.plugin.PluginSecretsApi
import com.coke.nestspace.plugin.PluginSettingView
import com.coke.nestspace.plugin.PluginSettingsApi
import com.coke.nestspace.plugin.PluginSettingsField
import java.util.concurrent.TimeUnit

internal data class PluginSettingFormState(
    val values: Map<String, String>,
    val configuredSecrets: Set<String>
)

internal data class PluginSettingDraft(
    val values: Map<String, String>,
    val secretsToClear: Set<String> = emptySet()
)

/**
 * Loads, validates and persists one host-rendered PluginSettingView.
 */
internal class PluginSettingFormStore(
    private val settings: PluginSettingsApi,
    private val secrets: PluginSecretsApi
) {
    fun load(view: PluginSettingView): PluginSettingFormState {
        val values = LinkedHashMap<String, String>()
        val configuredSecrets = LinkedHashSet<String>()
        view.fields.forEach { field ->
            val default = field.defaultValue
            values[field.id] = when (field.type) {
                PluginSettingsField.Type.BOOLEAN -> settings.getBoolean(
                    field.id,
                    default.equals("true", ignoreCase = true)
                ).await().toString()

                PluginSettingsField.Type.SECRET -> {
                    if (secrets.contains(field.id).await()) {
                        configuredSecrets += field.id
                    }
                    ""
                }

                else -> settings.getString(field.id, default).await()
            }
        }
        return PluginSettingFormState(values, configuredSecrets)
    }

    fun validate(
        view: PluginSettingView,
        values: Map<String, String>,
        configuredSecrets: Set<String>,
        secretsToClear: Set<String>
    ): String? = view.fields.firstNotNullOfOrNull { field ->
        val value = values[field.id].orEmpty()
        if (field.type == PluginSettingsField.Type.SECRET) {
            when {
                value.isNotBlank() ->
                    field.validate(value).takeIf { it.isNotBlank() }
                field.isRequired &&
                    (field.id !in configuredSecrets || field.id in secretsToClear) ->
                    "${field.title} is required"
                else -> null
            }
        } else {
            field.validate(value).takeIf { it.isNotBlank() }
        }
    }

    fun save(
        view: PluginSettingView,
        draft: PluginSettingDraft
    ) {
        view.fields.forEach { field ->
            val value = draft.values[field.id].orEmpty()
            when (field.type) {
                PluginSettingsField.Type.BOOLEAN -> {
                    val validation = field.validate(value)
                    require(validation.isEmpty()) { validation }
                    val boolean = value.toBooleanStrictOrNull()
                        ?: field.defaultValue.toBooleanStrictOrNull()
                        ?: false
                    settings.putBoolean(field.id, boolean).await()
                }

                PluginSettingsField.Type.INTEGER,
                PluginSettingsField.Type.SELECT,
                PluginSettingsField.Type.TEXT -> {
                    val validation = field.validate(value)
                    require(validation.isEmpty()) { validation }
                    settings.putString(field.id, value).await()
                }

                PluginSettingsField.Type.SECRET -> {
                    if (field.id in draft.secretsToClear) {
                        secrets.remove(field.id).await()
                    }
                    if (value.isNotBlank()) {
                        val validation = field.validate(value)
                        require(validation.isEmpty()) { validation }
                        secrets.put(field.id, value).await()
                    }
                    if (field.isRequired) {
                        require(secrets.contains(field.id).await()) {
                            "${field.title} is required"
                        }
                    }
                }
            }
        }
    }

    private fun <T> java.util.concurrent.CompletableFuture<T>.await(): T =
        get(5, TimeUnit.SECONDS)
}
