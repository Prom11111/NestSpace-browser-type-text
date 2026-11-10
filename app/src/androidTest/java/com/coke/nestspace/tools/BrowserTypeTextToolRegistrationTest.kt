package com.coke.nestspace.tools

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserTypeTextToolRegistrationTest {
    @Test
    fun browserTypeTextIsRegisteredWithRequiredArguments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tool = BrowserTools.create(context).firstOrNull { it.name == "browser_type_text" }

        assertNotNull("browser_type_text must be included in BrowserTools.create()", tool)
        assertEquals(
            listOf("selector", "text"),
            tool!!.inputSchema.getJSONArray("required").let { required ->
                (0 until required.length()).map { required.getString(it) }
            }
        )

        val properties = tool.inputSchema.getJSONObject("properties")
        assertEquals("string", properties.getJSONObject("selector").getString("type"))
        assertEquals("string", properties.getJSONObject("text").getString("type"))
        assertEquals("boolean", properties.getJSONObject("clear_first").getString("type"))
        assertEquals("string", properties.getJSONObject("tab_id").getString("type"))
        assertTrue(tool.description.contains("Type text"))
    }
}
