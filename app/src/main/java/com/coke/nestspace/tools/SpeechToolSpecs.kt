package com.coke.nestspace.tools

import com.coke.nestspace.speech.SpeechException
import com.coke.nestspace.speech.SpeechGateway
import org.json.JSONArray
import org.json.JSONObject

internal fun ToolRegistry.speechToolSpecs(): List<ToolSpec> = listOf(
    ToolSpec(
        name = "speech_speak",
        description = "Synthesize and enqueue speech through an enabled plugin speech provider. " +
            "Returns after synthesis is queued; playback continues in the host. " +
            "If provider_id is omitted, the highest-priority enabled provider is used.",
        inputSchema = schema(
            "text" to "string",
            "provider_id" to "string",
            "voice_id" to "string",
            "locale" to "string",
            "rate" to "number",
            "pitch" to "number",
            "interrupt" to "boolean",
            required = listOf("text")
        )
    ) { args, _ ->
        val text = args.optString("text").trim()
        if (text.isBlank()) {
            return@ToolSpec ToolResult(
                false,
                "text is required",
                errorCode = "INVALID_ARGUMENT"
            )
        }
        try {
            val result = SpeechGateway.speak(
                context = context,
                text = text,
                providerId = args.optString("provider_id").takeIf { it.isNotBlank() },
                voiceId = args.optString("voice_id"),
                locale = args.optString("locale"),
                rate = args.optDouble("rate", 1.0).toFloat(),
                pitch = args.optDouble("pitch", 1.0).toFloat(),
                interrupt = args.optBoolean("interrupt", false)
            )
            ToolResult(
                true,
                JSONObject()
                    .put("request_id", result.requestId)
                    .put("provider_id", result.providerId)
                    .put("provider_name", result.providerName)
                    .put("voice_id", result.voiceId)
                    .put("duration_ms", result.durationMs)
                    .put("status", "queued")
                    .toString()
            )
        } catch (error: SpeechException) {
            ToolResult(false, error.message ?: error.code, errorCode = error.code)
        } catch (error: IllegalArgumentException) {
            ToolResult(
                false,
                error.message ?: "Invalid speech request",
                errorCode = "INVALID_ARGUMENT"
            )
        }
    },
    ToolSpec(
        name = "speech_stop",
        description = "Stop current and queued speech playback.",
        inputSchema = schema()
    ) { _, _ ->
        val stopped = SpeechGateway.stopAll()
        ToolResult(
            true,
            JSONObject()
                .put("stopped", stopped)
                .put("status", "stopped")
                .toString()
        )
    },
    ToolSpec(
        name = "speech_status",
        description = "Report the active speech request, queue size and enabled plugin providers.",
        inputSchema = schema()
    ) { _, _ ->
        val status = SpeechGateway.status()
        val providers = JSONArray()
        status.providers.forEach { provider ->
            providers.put(
                JSONObject()
                    .put("provider_id", provider.providerId)
                    .put("name", provider.displayName)
                    .put("priority", provider.descriptor.priority)
            )
        }
        ToolResult(
            true,
            JSONObject()
                .put("active_request_id", status.activeRequestId ?: JSONObject.NULL)
                .put("active_provider_id", status.activeProviderId ?: JSONObject.NULL)
                .put("queued", status.queued)
                .put("providers", providers)
                .toString()
        )
    }
)
