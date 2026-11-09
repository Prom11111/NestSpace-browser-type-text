package com.coke.nestspace.plugin

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AndroidPluginWebSocketsTest {

    @Test
    fun connectsAndExchangesTextOverWs() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("ready")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        webSocket.send("echo:$text")
                    }
                }
            )
        )
        server.start()
        try {
            val open = CountDownLatch(1)
            val echoed = CountDownLatch(1)
            val messages = CopyOnWriteArrayList<String>()
            val url = "ws://${server.hostName}:${server.port}/socket"
            val socket = AndroidPluginWebSockets(setOf(server.hostName)).connect(
                PluginWebSocketRequest(url),
                object : PluginWebSocketListener {
                    override fun onOpen(socket: PluginWebSocket) {
                        open.countDown()
                    }

                    override fun onText(socket: PluginWebSocket, text: String) {
                        messages += text
                        if (text.startsWith("echo:")) echoed.countDown()
                    }
                }
            )

            assertTrue(open.await(5, TimeUnit.SECONDS))
            assertTrue(socket.isOpen)
            assertTrue(socket.sendText("hello"))
            assertTrue(echoed.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("ready", "echo:hello"), messages)
            assertTrue(socket.close(1000, "done"))
        } finally {
            runCatching { server.shutdown() }
        }
    }
}
