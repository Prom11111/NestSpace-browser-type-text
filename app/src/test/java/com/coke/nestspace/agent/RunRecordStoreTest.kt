package com.coke.nestspace.agent

import com.coke.nestspace.data.AgentMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RunRecordStoreTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("nestspace-runs").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun recordsEventsAndSummary() {
        val recorder = RunRecordStore.begin(root, "打开 B 站并搜索测试")
        recorder.record(AgentMessage.Role.USER, "打开 B 站并搜索测试")
        recorder.record(
            AgentMessage.Role.TOOL,
            "space_launch -> ok\n{\"launched\":true,\"package\":\"tv.danmaku.bili\"}"
        )
        recorder.record(AgentMessage.Role.AGENT, "已经打开 B 站。")
        val completed = recorder.finish(
            RunRecordStore.STATUS_DONE,
            metrics = RunMetricsSnapshot(
                modelRequests = 2,
                toolCalls = 1,
                inputTokens = 1_200,
                outputTokens = 80,
                reasoningTokens = 30,
                cachedInputTokens = 400
            )
        )

        val runs = RunRecordStore.recent(root)
        assertEquals(1, runs.size)
        assertEquals(RunRecordStore.STATUS_DONE, runs[0].status)
        assertEquals(3, runs[0].eventCount)
        assertEquals("打开 B 站并搜索测试", runs[0].command)
        assertEquals(2, runs[0].modelRequests)
        assertEquals(1, runs[0].toolCalls)
        assertEquals(1_200L, runs[0].inputTokens)
        assertEquals(80L, runs[0].outputTokens)
        assertEquals(30L, runs[0].reasoningTokens)
        assertEquals(400L, runs[0].cachedInputTokens)
        assertEquals(runs[0].id, completed.id)
        assertEquals(runs[0].totalTokens, completed.totalTokens)
        assertTrue(runs[0].firstFeedbackMs >= 0L)

        val events = RunRecordStore.events(root, runs[0].id)
        assertEquals(3, events.size)
        assertEquals(AgentMessage.Role.TOOL.name, events[1].role)
        assertTrue(events[1].text.contains("space_launch"))
    }

    @Test
    fun stopSummaryNamesGoalToolAndLastOutput() {
        val recorder = RunRecordStore.begin(root, "总结 B 站首页的前几个视频")
        recorder.record(AgentMessage.Role.USER, "总结 B 站首页的前几个视频")
        recorder.record(AgentMessage.Role.TOOL, "ui_observe -> ok\n{\"package\":\"tv.danmaku.bili\"}")
        recorder.record(AgentMessage.Role.AGENT, "已经读到首页推荐。")

        val summary = recorder.stopSummary(chinese = true)
        assertTrue(summary.contains("已停止"))
        assertTrue(summary.contains("总结 B 站首页"))
        assertTrue(summary.contains("ui_observe"))
        assertTrue(summary.contains("已经读到首页推荐"))
        assertTrue(summary.contains("继续"))

        recorder.finish(RunRecordStore.STATUS_STOPPED, summary)
        val runs = RunRecordStore.recent(root)
        assertEquals(RunRecordStore.STATUS_STOPPED, runs[0].status)
        assertTrue(runs[0].summary.contains("已停止"))
    }

    @Test
    fun recoversRunningRecordsOnce() {
        val interrupted = RunRecordStore.begin(root, "总结 B 站首页")
        interrupted.record(AgentMessage.Role.USER, "总结 B 站首页")
        interrupted.record(
            AgentMessage.Role.TOOL,
            "ui_observe -> ok\n{\"package\":\"tv.danmaku.bili\"}"
        )
        val finished = RunRecordStore.begin(root, "已经完成的任务")
        finished.finish(RunRecordStore.STATUS_DONE, "done")

        val recovered = RunRecordStore.recoverInterrupted(root, chinese = true)
        assertEquals(1, recovered.size)
        assertEquals(RunRecordStore.STATUS_INTERRUPTED, recovered[0].status)
        assertTrue(recovered[0].summary.contains("上次任务被中断"))
        assertTrue(recovered[0].summary.contains("总结 B 站首页"))
        assertTrue(recovered[0].summary.contains("ui_observe"))
        assertFalse(recovered[0].summary.contains("继续"))

        val runs = RunRecordStore.recent(root)
        assertEquals(2, runs.size)
        assertEquals(
            RunRecordStore.STATUS_INTERRUPTED,
            runs.first { it.command == "总结 B 站首页" }.status
        )
        assertEquals(
            RunRecordStore.STATUS_DONE,
            runs.first { it.command == "已经完成的任务" }.status
        )

        // Recovery is idempotent: the record is no longer "running".
        assertTrue(RunRecordStore.recoverInterrupted(root, chinese = true).isEmpty())
    }

    @Test
    fun recoveryDoesNotInterruptRunStartedAfterProcessStart() {
        val recorder = RunRecordStore.begin(root, "当前进程的新任务")
        val beforeProcessStart = recorder.startedAt

        assertTrue(
            RunRecordStore.recoverInterrupted(
                root,
                chinese = true,
                startedBefore = beforeProcessStart
            ).isEmpty()
        )
        assertEquals(
            RunRecordStore.STATUS_RUNNING,
            RunRecordStore.recent(root).single().status
        )

        recorder.finish(RunRecordStore.STATUS_DONE, "done")
    }

    @Test
    fun interruptedSummaryKeepsStopDetails() {
        val summary = RunRecordStore.buildStopSummary(
            command = "检查天气",
            events = listOf(
                RunEvent(
                    at = 2,
                    role = AgentMessage.Role.TOOL.name,
                    text = "browser_open -> ok"
                )
            ),
            chinese = false,
            interrupted = true
        )
        assertTrue(summary.contains("The previous run was interrupted"))
        assertTrue(summary.contains("browser_open"))
        assertTrue(summary.contains("recovery record"))
        assertFalse(summary.contains("\"continue\""))
    }

    @Test
    fun stopSummaryWithoutToolsIsStillUseful() {
        val summary = RunRecordStore.buildStopSummary(
            command = "先看看",
            events = listOf(
                RunEvent(
                    at = 1,
                    role = AgentMessage.Role.USER.name,
                    text = "先看看"
                )
            ),
            chinese = false
        )
        assertTrue(summary.contains("Stopped this task"))
        assertTrue(summary.contains("no completed tool calls yet"))
        assertTrue(summary.contains("continue"))
    }
}
