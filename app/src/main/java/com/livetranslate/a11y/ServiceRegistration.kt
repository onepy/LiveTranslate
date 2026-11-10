package com.livetranslate.a11y

internal class ServiceRegistration(
    private val entry: String,
    private val isSelf: (String) -> Boolean,
    private val read: () -> List<String>,
    private val write: (List<String>) -> Boolean,
) {
    fun enable(): Boolean {
        val current = read()
        return write(if (current.any(isSelf)) current else current + entry)
    }

    fun restart(pause: () -> Unit): Boolean {
        val removed: Boolean
        val restored: Boolean
        try {
            removed = write(read().filterNot(isSelf))
            if (removed) pause()
        } finally {
            restored = enable()
        }
        return removed && restored
    }
}
