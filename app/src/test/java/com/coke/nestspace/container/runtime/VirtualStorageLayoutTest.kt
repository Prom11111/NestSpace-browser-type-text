package com.coke.nestspace.container.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class VirtualStorageLayoutTest {
    @Test
    fun keepsDeviceAndCredentialStorageUnderTheSameInstance() {
        val credential = File("/data/instance-1")
        val device = VirtualStorageLayout.deviceProtectedRoot(credential)

        assertEquals(File("/data/instance-1/device_protected"), device)
        assertEquals(credential, device.parentFile)
        assertNotEquals(credential, device)
    }
}
