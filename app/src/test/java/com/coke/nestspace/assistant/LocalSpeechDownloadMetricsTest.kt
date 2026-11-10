package com.coke.nestspace.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSpeechDownloadMetricsTest {

    @Test
    fun formatsTransferSpeedAndRemainingTime() {
        val metrics = LocalSpeechModelStore.DownloadMetrics(
            bytesPerSecond = 1_572_864L,
            remainingSeconds = 65L
        )

        assertEquals("1.5 MB/s", metrics.speedText())
        assertEquals("01:05", metrics.remainingText())
    }

    @Test
    fun leavesUnknownValuesBlank() {
        val metrics = LocalSpeechModelStore.DownloadMetrics()

        assertEquals("", metrics.speedText())
        assertEquals("", metrics.remainingText())
    }
}
