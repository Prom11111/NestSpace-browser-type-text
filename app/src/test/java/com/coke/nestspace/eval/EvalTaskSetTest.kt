package com.coke.nestspace.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvalTaskSetTest {

    @Test
    fun parsesTasksAndDefaults() {
        val tasks = EvalTaskSet.parse(
            """
            {
              "tasks": [
                {
                  "id": "one",
                  "title": "First",
                  "goal": "do one",
                  "requires_app": "com.example",
                  "expect_tools": ["tool_a", "tool_b"],
                  "expect_contains": ["ok"],
                  "timeout_s": 5
                },
                {
                  "id": "two",
                  "goal": "do two"
                },
                {
                  "id": "",
                  "goal": "invalid"
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(2, tasks.size)
        assertEquals("one", tasks[0].id)
        assertEquals("com.example", tasks[0].requiresApp)
        assertEquals(listOf("tool_a", "tool_b"), tasks[0].expectTools)
        assertEquals(listOf("ok"), tasks[0].expectContains)
        assertEquals(10, tasks[0].timeoutSeconds)
        assertEquals("two", tasks[1].title)
        assertTrue(tasks[1].expectTools.isEmpty())
    }

    @Test
    fun invalidJsonIsEmpty() {
        assertTrue(EvalTaskSet.parse("not json").isEmpty())
    }
}
