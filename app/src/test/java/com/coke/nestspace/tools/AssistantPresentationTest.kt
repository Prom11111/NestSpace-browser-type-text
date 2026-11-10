package com.coke.nestspace.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class AssistantPresentationTest {

    @Test
    fun autoModeAlwaysUsesMirror() {
        assertEquals(
            AssistantPresentation.HEADLESS,
            assistantPresentation(ContainerDisplayMode.AUTO)
        )
    }

    @Test
    fun mirrorAndBackgroundUseMirror() {
        assertEquals(
            AssistantPresentation.HEADLESS,
            assistantPresentation(ContainerDisplayMode.MIRROR)
        )
        assertEquals(
            AssistantPresentation.HEADLESS,
            assistantPresentation(ContainerDisplayMode.BACKGROUND)
        )
    }

    @Test
    fun explicitVisibleModeUsesVisibleApp() {
        assertEquals(
            AssistantPresentation.VISIBLE,
            assistantPresentation(ContainerDisplayMode.VISIBLE)
        )
    }
}
