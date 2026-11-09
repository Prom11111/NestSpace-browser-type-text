package com.coke.nestspace.container.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SystemServiceArrayRewriteTest {
    @Test
    fun preservesDeclaredArrayType() {
        val first = Item("host", 1)
        val second = Item("other", 2)
        val original = arrayOf(first, second)

        val rewritten = SystemServiceArrayRewrite.rewrite(original) { value ->
            if (value is Item && value.name == "host") {
                Item("target", value.id)
            } else {
                value
            }
        }

        assertEquals(Item::class.java, rewritten.javaClass.componentType)
        val values = rewritten as Array<*>
        assertEquals("target", (values[0] as Item).name)
        assertSame(second, values[1])
    }

    private data class Item(val name: String, val id: Int)
}
