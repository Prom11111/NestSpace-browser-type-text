package com.coke.nestspace.task

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
        assertEquals(OperationTaskRegistry.State.CANCELLING, old?.state)
        assertTrue(cancelled)
        assertEquals(OperationTaskRegistry.State.PREPARING, current?.state)
        assertFalse(current?.terminal == true)

        OperationTaskRegistry.markCancelled(first.id, "replaced")
        assertEquals(
            OperationTaskRegistry.State.CANCELLED,
            OperationTaskRegistry.status(first.id)?.state
        )
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

    @Test
    fun progressDoesNotWakeTaskWaitButLifecycleDoes() = runBlocking {
        val task = OperationTaskRegistry.create(
            ownerId = "test-wait",
            kind = "download",
            title = "Wait"
        )
        val progressWait = async(start = CoroutineStart.UNDISPATCHED) {
            OperationTaskRegistry.wait(task.id, 80)
        }
        OperationTaskRegistry.progress(task.id, 12, "working")
        val timedOut = progressWait.await()
        assertTrue(timedOut.timedOut)
        assertFalse(timedOut.changed)

        val stateWait = async(start = CoroutineStart.UNDISPATCHED) {
            OperationTaskRegistry.wait(task.id, 1_000)
        }
        OperationTaskRegistry.transition(
            task.id,
            OperationTaskRegistry.State.WAITING_USER,
            "login required"
        )
        val changed = stateWait.await()
        assertTrue(changed.changed)
        assertFalse(changed.timedOut)
        assertEquals(
            OperationTaskRegistry.State.WAITING_USER,
            changed.task?.state
        )
    }

    @Test
    fun requestIdIsIdempotentWithinOwnerAndConflictsWithDifferentHash() {
        val first = OperationTaskRegistry.start(
            ownerId = "test-idempotency-a",
            kind = "download",
            title = "One",
            requestId = "request-1",
            requestHash = "hash-a"
        )
        val duplicate = OperationTaskRegistry.start(
            ownerId = "test-idempotency-a",
            kind = "download",
            title = "One",
            requestId = "request-1",
            requestHash = "hash-a"
        )
        val conflict = OperationTaskRegistry.start(
            ownerId = "test-idempotency-a",
            kind = "download",
            title = "Different",
            requestId = "request-1",
            requestHash = "hash-b"
        )
        val otherOwner = OperationTaskRegistry.start(
            ownerId = "test-idempotency-b",
            kind = "download",
            title = "Other",
            requestId = "request-1",
            requestHash = "hash-a"
        )

        assertTrue(first.created)
        assertFalse(duplicate.created)
        assertEquals(first.task?.id, duplicate.task?.id)
        assertEquals("REQUEST_ID_CONFLICT", conflict.errorCode)
        assertTrue(otherOwner.created)
        assertTrue(otherOwner.task?.id != first.task?.id)
    }

    @Test
    fun cancellationStagesUntilWorkerConfirmsCleanup() {
        val task = OperationTaskRegistry.create(
            ownerId = "test-cancel",
            kind = "download",
            title = "Cancel"
        )
        val requested = OperationTaskRegistry.requestCancel(
            task.id,
            "user cancelled"
        )
        assertTrue(requested.accepted)
        assertEquals(
            OperationTaskRegistry.State.CANCELLING,
            requested.task?.state
        )

        val finished = OperationTaskRegistry.markCancelled(
            task.id,
            cleanupError = "temporary file remained"
        )
        assertEquals(OperationTaskRegistry.State.CANCELLED, finished?.state)
        assertEquals("temporary file remained", finished?.cleanupError)
    }

    @Test
    fun cancellingParentCascadesToChildren() {
        val parent = OperationTaskRegistry.start(
            ownerId = "test-parent",
            kind = "assistant_task",
            title = "Parent"
        ).task!!
        val child = OperationTaskRegistry.start(
            ownerId = "test-parent",
            kind = "download",
            title = "Child",
            parentTaskId = parent.id
        ).task!!

        val result = OperationTaskRegistry.requestCancel(
            parent.id,
            ownerId = "test-parent"
        )
        assertTrue(result.accepted)
        assertEquals(
            OperationTaskRegistry.State.CANCELLING,
            OperationTaskRegistry.status(parent.id)?.state
        )
        assertEquals(
            OperationTaskRegistry.State.CANCELLING,
            OperationTaskRegistry.status(child.id)?.state
        )
        assertEquals(
            listOf(child.id),
            OperationTaskRegistry.children(parent.id).map { it.id }
        )
    }

    @Test
    fun ownerCannotCancelAnotherOwnersTask() {
        val task = OperationTaskRegistry.start(
            ownerId = "test-owner-secure",
            kind = "download",
            title = "Private"
        ).task!!

        val denied = OperationTaskRegistry.requestCancel(
            task.id,
            ownerId = "test-owner-other"
        )
        assertEquals("TASK_NOT_FOUND", denied.errorCode)
        assertEquals(
            OperationTaskRegistry.State.PREPARING,
            OperationTaskRegistry.status(task.id)?.state
        )
    }

    @Test
    fun finishedTaskCanBeDismissedButActiveTaskCannot() {
        val owner = "test-dismiss"
        val active = OperationTaskRegistry.create(
            ownerId = owner,
            kind = "tool_call",
            title = "active"
        )
        val finished = OperationTaskRegistry.create(
            ownerId = owner,
            kind = "tool_call",
            title = "finished"
        )
        OperationTaskRegistry.fail(finished.id, "interrupted")

        assertFalse(OperationTaskRegistry.dismiss(active.id, owner))
        assertTrue(OperationTaskRegistry.dismiss(finished.id, owner))
        assertEquals(null, OperationTaskRegistry.status(finished.id, owner))
        assertNotNull(OperationTaskRegistry.status(active.id, owner))
    }

    @Test
    fun dismissingTerminalTasksKeepsActiveTasks() {
        val owner = "test-dismiss-terminal"
        OperationTaskRegistry.create(ownerId = owner, kind = "tool_call", title = "active")
        repeat(3) { index ->
            val task = OperationTaskRegistry.create(
                ownerId = owner,
                kind = "tool_call",
                title = "finished-$index"
            )
            OperationTaskRegistry.succeed(task.id, "ok")
        }

        assertEquals(3, OperationTaskRegistry.dismissTerminal(owner))
        assertEquals(
            1,
            OperationTaskRegistry.tasksFlow.value.count { it.ownerId == owner }
        )
    }
}
