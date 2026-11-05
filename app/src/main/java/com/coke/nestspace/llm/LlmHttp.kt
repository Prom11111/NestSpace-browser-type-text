package com.coke.nestspace.llm

import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class LlmHttpException(message: String) : Exception(message)

object LlmHttp {

    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // Do not let HttpURLConnection replay the API key to a redirect
            // target. A provider that needs a redirect must be configured
            // with its final URL.
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 120_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "NestSpace/0.4")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

        try {
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            if (code in 300..399) {
                throw LlmHttpException(
                    AppLanguage.tr(
                        "模型接口返回重定向（HTTP $code），请在模型设置里填写最终地址",
                        "The model endpoint redirected (HTTP $code); configure its final URL " +
                            "in Model settings"
                    )
                )
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.let { readLimited(it) }.orEmpty()

            if (code !in 200..299) {
                throw LlmHttpException("HTTP $code: ${extractError(text)}")
            }
            if (text.isBlank()) throw LlmHttpException(AppLanguage.tr("接口返回空响应", "The API returned an empty response"))

            runCatching { JSONObject(text) }.getOrElse {
                throw LlmHttpException(
                    AppLanguage.tr(
                        "接口返回非 JSON：${text.take(300)}",
                        "The API returned non-JSON: ${text.take(300)}"
                    )
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * POST a streaming SSE request. [onData] receives the payload after each
     * `data:` line; providers parse their own event JSON. Compressed responses
     * are explicitly refused because some gateways compress SSE without
     * declaring the encoding, which silently produces an empty stream.
     */
    suspend fun postSse(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        onData: (String) -> Boolean
    ) = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 180_000
            doInput = true
            doOutput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "NestSpace/0.4")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        try {
            val payload = body.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            if (code in 300..399) {
                throw LlmHttpException(
                    AppLanguage.tr(
                        "模型接口返回重定向（HTTP $code），请在模型设置里填写最终地址",
                        "The model endpoint redirected (HTTP $code); configure its final URL " +
                            "in Model settings"
                    )
                )
            }
            if (code !in 200..299) {
                val text = connection.errorStream?.let { readLimited(it) }.orEmpty()
                throw LlmHttpException("HTTP $code: ${extractError(text)}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data.isNotEmpty() && !onData(data)) break
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private const val MAX_RESPONSE_BYTES = 16 * 1024 * 1024

    private fun readLimited(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw LlmHttpException(
                    AppLanguage.tr(
                        "接口响应超过 16 MB",
                        "The API response exceeds 16 MB"
                    )
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun extractError(text: String): String {
        if (text.isBlank()) return AppLanguage.tr("无错误详情", "No error details")
        return runCatching {
            val json = JSONObject(text)
            val error = json.optJSONObject("error")
            error?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("detail").takeIf { it.isNotBlank() }
                ?: text
        }.getOrDefault(text).take(600)
    }
}
