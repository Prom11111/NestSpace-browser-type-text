package com.coke.nestspace.llm

import com.coke.nestspace.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class LlmHttpException(message: String) : Exception(message)

object LlmHttp {

    private const val USER_AGENT = "NestSpace/0.4"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val baseClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonClient = baseClient.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val sseClient = baseClient.newBuilder()
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: JSONObject
    ): JSONObject {
        val request = buildRequest(
            url = url,
            headers = headers,
            body = body,
            accept = "application/json",
            identityEncoding = false
        )
        return executeCall(jsonClient, request) { response ->
            val code = response.code
            if (code in 300..399) {
                throw LlmHttpException(
                    AppLanguage.tr(
                        "模型接口返回重定向（HTTP $code），请在模型设置里填写最终地址",
                        "The model endpoint redirected (HTTP $code); configure its final URL " +
                            "in Model settings"
                    )
                )
            }
            val text = response.body
                ?.let { readLimited(it.byteStream()) }
                .orEmpty()

            if (!response.isSuccessful) {
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
    ) {
        val request = buildRequest(
            url = url,
            headers = headers,
            body = body,
            accept = "text/event-stream",
            identityEncoding = true
        )
        executeCall(sseClient, request) { response ->
            val code = response.code
            if (code in 300..399) {
                throw LlmHttpException(
                    AppLanguage.tr(
                        "模型接口返回重定向（HTTP $code），请在模型设置里填写最终地址",
                        "The model endpoint redirected (HTTP $code); configure its final URL " +
                            "in Model settings"
                    )
                )
            }
            if (!response.isSuccessful) {
                val text = response.body
                    ?.let { readLimited(it.byteStream()) }
                    .orEmpty()
                throw LlmHttpException("HTTP $code: ${extractError(text)}")
            }
            val source = response.body?.source()
                ?: throw LlmHttpException(
                    AppLanguage.tr("接口返回空响应", "The API returned an empty response")
                )
            while (!source.exhausted()) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isNotEmpty() && !onData(data)) break
            }
        }
    }

    private fun buildRequest(
        url: String,
        headers: Map<String, String>,
        body: JSONObject,
        accept: String,
        identityEncoding: Boolean
    ): Request {
        val requestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)
        return Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
            .apply {
                if (identityEncoding) {
                    header("Accept-Encoding", "identity")
                }
                headers.forEach { (name, value) -> header(name, value) }
            }
            .build()
    }

    private suspend fun <T> executeCall(
        client: OkHttpClient,
        request: Request,
        block: suspend (Response) -> T
    ): T = withContext(Dispatchers.IO) {
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext()[Job]
            ?.invokeOnCompletion { call.cancel() }
        try {
            val response = call.execute()
            try {
                block(response)
            } finally {
                response.close()
            }
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw error
        } finally {
            cancellation?.dispose()
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
