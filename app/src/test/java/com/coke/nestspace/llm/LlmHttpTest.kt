package com.coke.nestspace.llm

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmHttpTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun postJsonUsesJsonPostWithoutRedirects() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ok":true}""")
        )

        val response = LlmHttp.postJson(
            server.url("/v1/messages").toString(),
            mapOf("Authorization" to "Bearer secret"),
            JSONObject().put("model", "test")
        )

        assertEquals(true, response.getBoolean("ok"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer secret", request.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Accept"))
        assertTrue(request.body.readUtf8().contains("\"model\":\"test\""))
    }

    @Test
    fun postSseReadsDataLinesAndDisablesCompression() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"delta\":\"one\"}\n\n" +
                        "data: {\"delta\":\"two\"}\n\n"
                )
        )
        val events = mutableListOf<String>()

        LlmHttp.postSse(
            server.url("/v1/chat").toString(),
            emptyMap(),
            JSONObject().put("stream", true)
        ) { data ->
            events += data
            true
        }

        assertEquals(
            listOf("""{"delta":"one"}""", """{"delta":"two"}"""),
            events
        )
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("identity", request.getHeader("Accept-Encoding"))
        assertTrue(request.body.readUtf8().contains("\"stream\":true"))
    }

    @Test
    fun redirectsAreRejectedInsteadOfReplayingCredentials() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader(
                    "Location",
                    server.url("/redirect-target").toString()
                )
        )

        val error = runCatching {
            LlmHttp.postJson(
                server.url("/v1/messages").toString(),
                mapOf("Authorization" to "Bearer secret"),
                JSONObject()
            )
        }.exceptionOrNull()

        assertTrue(error is LlmHttpException)
        assertEquals(1, server.requestCount)
    }
}
