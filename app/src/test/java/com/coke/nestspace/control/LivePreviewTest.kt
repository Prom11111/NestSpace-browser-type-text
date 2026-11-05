package com.coke.nestspace.control

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LivePreviewTest {

    @After
    fun tearDown() {
        LivePreview.clear()
    }

    @Test
    fun keepsOnlyTheLatestFrame() {
        LivePreview.publish("first", slot = 1, label = "a")
        LivePreview.publish("second", slot = 2, label = "b")
        val frame = LivePreview.frame.value!!
        assertEquals("second", frame.base64)
        assertEquals(2, frame.slot)
        assertEquals("b", frame.label)
    }

    @Test
    fun ignoresInvalidFramesAndCanClear() {
        LivePreview.publish("", slot = 1)
        LivePreview.publish("jpeg", slot = -1)
        assertNull(LivePreview.frame.value)

        LivePreview.publish("jpeg", slot = 3)
        LivePreview.clear()
        assertNull(LivePreview.frame.value)
    }
}
