package com.coke.nestspace.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationTaskRegistryTest {

    @Test
    fun taskLifecycleIsObservable() {
        val created = OperationTaskRegistry.create(
            ownerId = "test-lifecycle",
            kind = "test",
            title = "Test task"
        )
        OperationTaskRegistry.progress(created.id, 42, "working")
        val done = OperationTaskRegistry.succeed(created.id, """{"ok":true}""")

        assertNotNull(done)
        assertEquals(OperationTaskRegistry.State.SUCCEEDED, done?.state)
        assertEquals(42, done?.progressPercent)
        assertEquals("""{"ok":true}""", done?.result)
        assertTrue(done?.terminal == true)
    }

    @Test
    fun replacingAKeyCancelsPreviousTask() {
        var cancelled = false
        val first = OperationTaskRegistry.create(
            ownerId = "test-replace",
            kind = "download",
            title = "First",
            replaceKey = "single",
            onCancel = { cancelled = true }
        )
        val second = OperationTaskRegistry.create(
            ownerId = "test-replace",
            kind = "download",
            title = "Second",
            replaceKey = "single"
        )

        val old = OperationTaskRegistry.status(first.id)
        val current = OperationTaskRegistry.status(second.id)
        assertEquals(OperationTaskRegistry.State.CANCELLED, old?.state)
        assertTrue(cancelled)
        assertEquals(OperationTaskRegistry.State.RUNNING, current?.state)
        assertFalse(current?.terminal == true)
    }

    @Test
    fun ownerCannotReadAnotherSessionsTask() {
        val task = OperationTaskRegistry.create(
            ownerId = "owner-a",
            kind = "test",
            title = "Private"
        )

        assertNotNull(OperationTaskRegistry.status(task.id, "owner-a"))
        assertEquals(null, OperationTaskRegistry.status(task.id, "owner-b"))
    }
}
