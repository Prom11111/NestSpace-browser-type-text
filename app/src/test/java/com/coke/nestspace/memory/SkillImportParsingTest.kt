package com.coke.nestspace.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkillImportParsingTest {

    @Test
    fun parsesFrontMatterSkillDocument() {
        val parsed = BuiltinSkills.parse(
            "from-disk",
            """
            ---
            name: imported-skill
            description: Imported from a SKILL.md file
            ---

            # Steps
            1. Do the thing.
            """.trimIndent()
        )
        assertNotNull(parsed)
        assertEquals("imported-skill", parsed!!.name)
        assertEquals("Imported from a SKILL.md file", parsed.description)
        assertEquals("# Steps\n1. Do the thing.", parsed.body)
    }

    @Test
    fun rejectsDocumentsWithoutFrontMatter() {
        assertNull(BuiltinSkills.parse("plain", "# Just markdown\nNo metadata"))
    }
}
