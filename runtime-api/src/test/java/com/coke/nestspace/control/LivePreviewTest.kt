package com.coke.nestspace.control

import org.junit.After
import org.junit.Assert.assertArrayEquals
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
        LivePreview.publish("first".encodeToByteArray(), slot = 1, label = "a")
        LivePreview.publish("second".encodeToByteArray(), slot = 2, label = "b")
        val frame = LivePreview.frame.value!!
        assertArrayEquals("second".encodeToByteArray(), frame.jpegBytes)
        assertEquals(2, frame.slot)
        assertEquals("b", frame.label)
    }

    @Test
    fun ignoresInvalidFramesAndCanClear() {
        LivePreview.publish(ByteArray(0), slot = 1)
        LivePreview.publish("jpeg".encodeToByteArray(), slot = -1)
        assertNull(LivePreview.frame.value)

        LivePreview.publish("jpeg".encodeToByteArray(), slot = 3)
        LivePreview.clear()
        assertNull(LivePreview.frame.value)
    }
}
