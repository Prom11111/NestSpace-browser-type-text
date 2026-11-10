package com.coke.nestspace.ui.plugins

import com.coke.nestspace.plugin.PluginSecretsApi
import com.coke.nestspace.plugin.PluginSettingView
import com.coke.nestspace.plugin.PluginSettingsApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CompletableFuture

class PluginSettingFormStoreTest {

    private val view = PluginSettingView.importSchema(
        """
        {
          "title": "Speech",
          "fields": [
            {
              "id": "endpoint",
              "title": "Endpoint",
              "type": "text",
              "format": "url",
              "required": true
            },
            {
              "id": "api_key",
              "title": "API key",
              "type": "secret",
              "required": true
            },
            {
              "id": "enabled",
              "title": "Enabled",
              "type": "boolean",
              "default": "true"
            }
          ]
        }
        """.trimIndent()
    )

    @Test
    fun loadsSavedValuesAndConfiguredSecret() {
        val settings = FakeSettings()
        val secrets = FakeSecrets()
        settings.strings["endpoint"] = "https://saved.example.com"
        settings.booleans["enabled"] = false
        secrets.values["api_key"] = "secret-value"
        val store = PluginSettingFormStore(settings, secrets)

        val loaded = store.load(view)

        assertEquals(
            "https://saved.example.com",
            loaded.values["endpoint"]
        )
        assertEquals("false", loaded.values["enabled"])
        assertEquals(setOf("api_key"), loaded.configuredSecrets)
    }

    @Test
    fun validatesRequiredAndFormatsBeforeSave() {
        val store = PluginSettingFormStore(FakeSettings(), FakeSecrets())

        assertEquals(
            "Endpoint is required",
            store.validate(view, emptyMap(), emptySet(), emptySet())
        )
        assertEquals(
            "Endpoint must be an HTTP(S) URL",
            store.validate(
                view,
                mapOf(
                    "endpoint" to "not a url",
                    "api_key" to "long-enough-key"
                ),
                emptySet(),
                emptySet()
            )
        )
        assertNull(
            store.validate(
                view,
                mapOf(
                    "endpoint" to "https://api.example.com",
                    "api_key" to "long-enough-key"
                ),
                emptySet(),
                emptySet()
            )
        )
    }

    @Test
    fun savesValuesForTheNextOpen() {
        val settings = FakeSettings()
        val secrets = FakeSecrets()
        val store = PluginSettingFormStore(settings, secrets)

        store.save(
            view,
            PluginSettingDraft(
                values = mapOf(
                    "endpoint" to "https://api.example.com",
                    "api_key" to "new-secret",
                    "enabled" to "false"
                )
            )
        )
        val reopened = store.load(view)

        assertEquals(
            "https://api.example.com",
            reopened.values["endpoint"]
        )
        assertEquals("false", reopened.values["enabled"])
        assertEquals(setOf("api_key"), reopened.configuredSecrets)
        assertEquals("new-secret", secrets.values["api_key"])
    }

    @Test
    fun keepsExistingSecretWhenFieldIsBlank() {
        val settings = FakeSettings()
        val secrets = FakeSecrets().apply {
            values["api_key"] = "existing-secret"
        }
        val store = PluginSettingFormStore(settings, secrets)
        val values = mapOf(
            "endpoint" to "https://api.example.com",
            "api_key" to "",
            "enabled" to "true"
        )

        assertNull(
            store.validate(
                view,
                values,
                setOf("api_key"),
                emptySet()
            )
        )
        store.save(view, PluginSettingDraft(values))

        assertEquals("existing-secret", secrets.values["api_key"])
    }

    private class FakeSettings : PluginSettingsApi {
        val strings = mutableMapOf<String, String>()
        val booleans = mutableMapOf<String, Boolean>()

        override fun contains(key: String) =
            completed(key in strings || key in booleans)

        override fun getString(key: String, fallback: String?) =
            completed(strings[key] ?: fallback.orEmpty())

        override fun getBoolean(key: String, fallback: Boolean) =
            completed(booleans[key] ?: fallback)

        override fun getLong(key: String, fallback: Long) = completed(fallback)

        override fun putString(key: String, value: String?) =
            completed<Void?>(null).also { strings[key] = value.orEmpty() }

        override fun putBoolean(key: String, value: Boolean) =
            completed<Void?>(null).also { booleans[key] = value }

        override fun putLong(key: String, value: Long) = completed<Void?>(null)

        override fun remove(key: String) =
            completed<Void?>(null).also {
                strings.remove(key)
                booleans.remove(key)
            }

        override fun keys() = completed((strings.keys + booleans.keys).toList())
    }

    private class FakeSecrets : PluginSecretsApi {
        val values = mutableMapOf<String, String>()

        override fun put(key: String, value: String?) =
            completed<Void?>(null).also {
                if (value.isNullOrEmpty()) values.remove(key)
                else values[key] = value
            }

        override fun get(key: String, fallback: String?) =
            completed(values[key] ?: fallback.orEmpty())

        override fun contains(key: String) = completed(key in values)

        override fun remove(key: String) =
            completed<Void?>(null).also { values.remove(key) }

        override fun keys() = completed(values.keys.toList())
    }

    private companion object {
        fun <T> completed(value: T): CompletableFuture<T> =
            CompletableFuture.completedFuture(value)
    }
}
