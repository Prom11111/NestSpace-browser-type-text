package com.coke.nestspace.replay

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayManagerTest {

    @Test
    fun successfulRunWithoutToolsClearsPreviousTrace() {
        ReplayManager.begin("first")
        ReplayManager.record("space_list", JSONObject())
        ReplayManager.complete()
        assertEquals(1, ReplayManager.last()?.steps?.size)

        ReplayManager.begin("chat only")
        ReplayManager.complete()
        assertNull(ReplayManager.last())
    }

    @Test
    fun failedRunKeepsPreviousSuccessfulTrace() {
        ReplayManager.begin("first")
        ReplayManager.record("space_list", JSONObject())
        ReplayManager.complete()

        ReplayManager.begin("failed")
        ReplayManager.record("ui_tap", JSONObject().put("node_id", "1"))
        ReplayManager.abort()

        assertEquals("first", ReplayManager.last()?.goal)
        assertEquals(1, ReplayManager.last()?.steps?.size)
    }
}
