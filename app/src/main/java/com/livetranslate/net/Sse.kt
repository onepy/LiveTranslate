package com.livetranslate.net

data class SseEvent(val event: String, val data: String)

/** Minimal text/event-stream parser: feed one line at a time, get an event at each blank line. */
class SseParser {
    private var event = "message"
    private val data = StringBuilder()
    private var hasData = false

    fun feed(rawLine: String): SseEvent? {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(":")) return null
        val idx = line.indexOf(':')
        val field = if (idx < 0) line else line.substring(0, idx)
        val value = if (idx < 0) "" else line.substring(idx + 1).removePrefix(" ")
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
        }
        return null
    }

    fun finish(): SseEvent? = dispatch()

    private fun dispatch(): SseEvent? {
        val result = if (hasData || event != "message") SseEvent(event, data.toString()) else null
        event = "message"
        data.setLength(0)
        hasData = false
        return result
    }
}
