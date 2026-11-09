package com.coke.nestspace.plugin

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidPluginSseTest {

    @Test
    fun streamsEventsOverPlainHttp() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "id: 1\n" +
                        "event: update\n" +
                        "data: first\n" +
                        "data: second\n" +
                        "\n" +
                        "data: final\n\n"
                )
        )
        server.start()
        try {
            val opened = CountDownLatch(1)
            val received = CountDownLatch(2)
            val events = CopyOnWriteArrayList<PluginSseEvent>()
            val network = AndroidPluginNetwork(setOf(server.hostName))
            val connection = network.openSse(
                PluginSseRequest(
                    "http://${server.hostName}:${server.port}/events"
                ),
                object : PluginSseListener {
                    override fun onOpen(
                        connection: PluginSseConnection,
                        statusCode: Int,
                        contentType: String
                    ) {
                        opened.countDown()
                    }

                    override fun onEvent(
                        connection: PluginSseConnection,
                        event: PluginSseEvent
                    ) {
                        events += event
                        received.countDown()
                    }
                }
            )

            assertTrue(opened.await(5, TimeUnit.SECONDS))
            assertTrue(received.await(5, TimeUnit.SECONDS))
            assertEquals("update", events[0].event)
            assertEquals("first\nsecond", events[0].data)
            assertEquals("message", events[1].event)
            assertEquals("final", events[1].data)
            connection.close()
        } finally {
            server.shutdown()
        }
    }
}
