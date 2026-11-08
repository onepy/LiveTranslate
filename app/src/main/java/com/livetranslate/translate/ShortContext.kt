package com.livetranslate.translate

/** Successful replacements only. Once a draft starts, pauses never expire its frozen context. */
class ShortContext(private val now: () -> Long) {
    private val history = ArrayDeque<String>()
    private var replacedAt: Long? = null
    private var draft: List<String>? = null

    fun begin(): List<String> {
        draft?.let { return it }
        val stamp = replacedAt
        if (stamp == null || now() - stamp !in 0..5000) history.clear()
        return history.toList().also { draft = it }
    }

    fun committed(source: String) {
        if (source.isBlank()) return
        history.addLast(source.takeLast(500))
        while (history.size > 3 || history.sumOf { it.length } > 500) history.removeFirst()
        replacedAt = now()
        draft = null
    }

    fun clear() {
        history.clear()
        replacedAt = null
        draft = null
    }
}
