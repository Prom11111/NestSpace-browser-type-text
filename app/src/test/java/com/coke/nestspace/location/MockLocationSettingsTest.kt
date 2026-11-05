package com.coke.nestspace.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocationSettingsTest {

    @Test
    fun parsesCoordinatesInRange() {
        assertEquals(39.9088, MockLocationSettings.parseLatitude(" 39.9088 ")!!, 0.0)
        assertEquals(-180.0, MockLocationSettings.parseLongitude("-180")!!, 0.0)
        assertEquals(90.0, MockLocationSettings.parseLatitude("90")!!, 0.0)
    }

    @Test
    fun rejectsCoordinatesOutsideRangeOrNotANumber() {
        assertNull(MockLocationSettings.parseLatitude("90.1"))
        assertNull(MockLocationSettings.parseLatitude("-91"))
        assertNull(MockLocationSettings.parseLatitude("north"))
        assertNull(MockLocationSettings.parseLongitude("180.1"))
        assertNull(MockLocationSettings.parseLongitude("-181"))
        assertNull(MockLocationSettings.parseLongitude(""))
    }

    @Test
    fun formatsCoordinatesWithSixDecimals() {
        assertEquals("39.908800", MockLocationSettings.format(39.9088))
        assertEquals("-121.473700", MockLocationSettings.format(-121.4737))
    }

    @Test
    fun labelOnlyShowsEnabledSimulation() {
        val disabled = MockLocationSettings.Config(enabled = false)
        assertEquals("", MockLocationSettings.label(disabled))

        val enabled = MockLocationSettings.Config(
            enabled = true,
            latitude = 31.2304,
            longitude = 121.4737
        )
        assertEquals("31.230400, 121.473700", MockLocationSettings.label(enabled))
    }

    @Test
    fun defaultsAreValid() {
        assertTrue(
            MockLocationSettings.parseLatitude(
                MockLocationSettings.DEFAULT_LATITUDE.toString()
            ) != null
        )
        assertTrue(
            MockLocationSettings.parseLongitude(
                MockLocationSettings.DEFAULT_LONGITUDE.toString()
            ) != null
        )
        assertFalse(MockLocationSettings.Config().enabled)
    }
}
