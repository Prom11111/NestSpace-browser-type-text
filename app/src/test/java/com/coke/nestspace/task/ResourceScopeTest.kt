package com.coke.nestspace.task

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ResourceScopeTest {

    @Test
    fun releasesResourcesInReverseOrder() = runBlocking {
        val order = mutableListOf<Int>()
        val scope = ResourceScope()
        scope.register { order += 1 }
        scope.register { order += 2 }
        scope.register { order += 3 }

        assertEquals(null, scope.close())
        assertEquals(listOf(3, 2, 1), order)
    }

    @Test
    fun oneStuckReleaseDoesNotBlockOthers() = runBlocking {
        val order = mutableListOf<String>()
        val scope = ResourceScope(releaseTimeoutMs = 30L)
        scope.register { order += "first" }
        scope.register {
            try {
                delay(1_000)
            } finally {
                order += "slow"
            }
        }

        assertNotNull(scope.close())
        assertEquals(listOf("slow", "first"), order)
    }
}
