package com.coke.nestspace.container.compat

import com.coke.nestspace.container.compat.p74332.ProfileP74332
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionedProfileFingerprintTest {

    @Test
    fun fingerprintContainsOnlyOpaqueHashes() {
        assertTrue(ProfileP74332.requiredHashes.isNotEmpty())
        assertTrue(
            ProfileP74332.requiredHashes.all {
                it.matches(Regex("[0-9a-f]{64}"))
            }
        )
    }

    @Test
    fun profileIdIsOpaqueAndDeterministic() {
        assertTrue(ProfileP74332.id.matches(Regex("p-[0-9a-f]{12}")))
        assertTrue(!ProfileP74332.id.contains("app", ignoreCase = true))
        assertEquals(ProfileP74332.id, ProfileP74332.id)
    }
}
