package com.livetranslate.overlay

import java.text.BreakIterator
import java.util.Locale

object Words {
    /** Splits text into word tokens (punctuation and whitespace dropped). */
    fun split(text: String, locale: Locale): List<String> = tokens(text, locale).map { it.first }

    /** Word tokens with their [start, end) offsets in [text]. */
    fun tokens(text: String, locale: Locale): List<Pair<String, IntRange>> {
        val it = BreakIterator.getWordInstance(locale)
        it.setText(text)
        val out = ArrayList<Pair<String, IntRange>>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val token = text.substring(start, end)
            if (token.any { c -> c.isLetterOrDigit() }) out += token to (start until end)
            start = end
            end = it.next()
        }
        return out
    }
}
