package com.coke.nestspace.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventAccumulatorTest {

    @Test
    fun parsesMultilineDataAndRetryFields() {
        val parser = SseEventAccumulator()
        parser.accept(": keepalive")
        parser.accept("id: event-1")
        parser.accept("event: update")
        parser.accept("retry: 2500")
        parser.accept("data: first line")
        parser.accept("data:second line")
        assertNull(parser.accept("data"))

        val event = parser.accept("")
        assertEquals("event-1", event?.id)
        assertEquals("update", event?.event)
        assertEquals("first line\nsecond line\n", event?.data)
        assertEquals(2500, event?.retryMs)
    }

    @Test
    fun blankEventDoesNotDispatch() {
        val parser = SseEventAccumulator()
        parser.accept("event: ignored")
        assertNull(parser.accept(""))
    }
}
