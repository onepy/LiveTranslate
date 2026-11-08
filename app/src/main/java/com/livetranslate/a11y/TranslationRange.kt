package com.livetranslate.a11y

/** Offsets use Android/Kotlin UTF-16 indices. The snapshot prevents replacing a changed document. */
data class TranslationRange(val snapshot: String, val start: Int, val end: Int) {
    val text: String get() = snapshot.substring(start, end)

    fun replace(current: String, translated: String): String? =
        if (current == snapshot) current.replaceRange(start, end, translated) else null

    fun containsSelection(start: Int, end: Int): Boolean =
        start >= this.start && end >= this.start && start <= this.end && end <= this.end
}

object TranslationRanges {
    fun select(text: String, start: Int, end: Int, explicit: Boolean): TranslationRange? {
        if (start !in 0..text.length || end !in 0..text.length) return null
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        if (explicit && lo != hi) return trimmed(text, lo, hi)
        var left = lo
        var right = lo
        while (left > 0 && text[left - 1] != '\n' && text[left - 1] != '\r') left--
        while (right < text.length && text[right] != '\n' && text[right] != '\r') right++
        return trimmed(text, left, right)
    }

    /** Finds the end of the changed span when an editor does not expose its cursor. */
    fun changedCaret(before: String, after: String): Int {
        var prefix = 0
        while (prefix < minOf(before.length, after.length) && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < before.length - prefix && suffix < after.length - prefix &&
            before[before.lastIndex - suffix] == after[after.lastIndex - suffix]) suffix++
        return after.length - suffix
    }

    private fun trimmed(text: String, start: Int, end: Int): TranslationRange? {
        var left = start
        var right = end
        while (left < right && text[left].isWhitespace()) left++
        while (right > left && text[right - 1].isWhitespace()) right--
        if (left == right || !text.substring(left, right).any { it.isLetterOrDigit() }) return null
        return TranslationRange(text, left, right)
    }
}
