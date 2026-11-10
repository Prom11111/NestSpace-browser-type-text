package com.coke.nestspace.container.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualWebViewDataDirectoryTest {
    @Test
    fun isolatesProcessesAndInstances() {
        val main = VirtualWebViewDataDirectory.suffix(
            "com.example.app",
            "instance-1",
            "com.example.app"
        )
        val worker = VirtualWebViewDataDirectory.suffix(
            "com.example.app",
            "instance-1",
            "com.example.app:worker"
        )
        val secondInstance = VirtualWebViewDataDirectory.suffix(
            "com.example.app",
            "instance-2",
            "com.example.app"
        )

        assertNotEquals(main, worker)
        assertNotEquals(main, secondInstance)
        assertTrue(main.matches(Regex("[A-Za-z0-9_]+")))
        assertEquals(
            main,
            VirtualWebViewDataDirectory.suffix(
                "com.example.app",
                "instance-1",
                "com.example.app"
            )
        )
    }
}
