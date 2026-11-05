package com.coke.nestspace.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BuiltinSkillTest {

    private val root: File = listOf(
        File("src/main/assets/skills"),
        File("app/src/main/assets/skills")
    ).firstOrNull { it.isDirectory }
        ?: error("built-in skills not found from ${File(".").absolutePath}")

    @Test
    fun bundledSkillsParse() {
        val skills = read()
        assertEquals(7, skills.size)
        for (skill in skills) {
            assertTrue(skill.name, SkillPolicy.isValidName(skill.name))
            assertTrue("blank description in ${skill.name}", skill.description.isNotBlank())
            assertTrue(
                "description too long in ${skill.name}",
                skill.description.length <= SkillPolicy.MAX_DESCRIPTION
            )
            assertTrue("body blank in ${skill.name}", skill.body.isNotBlank())
            assertTrue(
                "body too long in ${skill.name}",
                skill.body.length <= SkillPolicy.MAX_BODY
            )
            assertTrue("missing completion section in ${skill.name}", "怎样算完成" in skill.body)
        }
    }

    @Test
    fun namesAndDirectoriesAreUnique() {
        val skills = read()
        val names = skills.map { it.name }
        assertEquals("duplicate built-in skill name", names.size, names.distinct().size)
    }

    @Test
    fun bundledSkillsDoNotContainLocalPaths() {
        for (skill in read()) {
            assertTrue("local path in ${skill.name}", !skill.body.contains("/Users/"))
            assertTrue("home path in ${skill.name}", !skill.body.contains("C:\\Users\\"))
        }
    }

    @Test
    fun knownSkillsArePresent() {
        val names = read().map { it.name }.toSet()
        assertTrue("info-research" in names)
        assertTrue("messaging" in names)
        assertTrue("configure-nestspace" in names)
        assertTrue("skill-authoring" in names)
    }

    @Test
    fun parserRejectsInvalidInput() {
        assertNull(BuiltinSkills.parse("bad", "no front matter"))
        assertNull(
            BuiltinSkills.parse(
                "bad",
                "---\nname: Bad Name\ndescription: x\n---\nbody"
            )
        )
        assertNull(
            BuiltinSkills.parse(
                "empty",
                "---\nname: empty\ndescription: x\n---\n"
            )
        )
        assertNotNull(
            BuiltinSkills.parse(
                "ok",
                "---\nname: ok\ndescription: A valid skill\n---\n## 步骤\nDo it."
            )
        )
    }

    private fun read(): List<BuiltinSkill> {
        return root.listFiles { file -> file.isDirectory }
            .orEmpty()
            .sortedBy { it.name }
            .map { dir ->
                val file = File(dir, "SKILL.md")
                assertTrue("missing $file", file.isFile)
                val parsed = BuiltinSkills.parse(dir.name, file.readText())
                assertNotNull("cannot parse $file", parsed)
                assertEquals("name must match directory ${dir.name}", dir.name, parsed!!.name)
                parsed
            }
    }
}
