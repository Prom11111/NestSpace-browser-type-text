package com.coke.nestspace.plugin

internal class SseEventAccumulator(
    private val maxEventBytes: Int = MAX_EVENT_BYTES
) {
    private val data = StringBuilder()
    private var eventType = ""
    private var lastEventId = ""
    private var retryMs = 0

    fun accept(line: String): PluginSseEvent? {
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(':')) return null

        val separator = line.indexOf(':')
        val field = if (separator < 0) line else line.substring(0, separator)
        var value = if (separator < 0) "" else line.substring(separator + 1)
        if (value.startsWith(' ')) value = value.substring(1)

        when (field) {
            "data" -> {
                data.append(value).append('\n')
                require(data.length <= maxEventBytes) {
                    "SSE event exceeds 1 MB"
                }
            }
            "event" -> eventType = value
            "id" -> {
                if ('\u0000' !in value) lastEventId = value
            }
            "retry" -> {
                if (value.isNotEmpty() && value.all(Char::isDigit)) {
                    value.toLongOrNull()
                        ?.takeIf { it <= Int.MAX_VALUE }
                        ?.let { retryMs = it.toInt() }
                }
            }
        }
        return null
    }

    private fun dispatch(): PluginSseEvent? {
        val currentEvent = eventType
        eventType = ""
        if (data.isEmpty()) return null
        data.setLength(data.length - 1)
        val currentData = data.toString()
        require(currentData.toByteArray(Charsets.UTF_8).size <= maxEventBytes) {
            "SSE event exceeds 1 MB"
        }
        val event = PluginSseEvent(
            lastEventId,
            currentEvent,
            currentData,
            retryMs
        )
        data.setLength(0)
        return event
    }

    companion object {
        const val MAX_EVENT_BYTES = 1024 * 1024
    }
}
