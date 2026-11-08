package com.livetranslate.overlay

import java.text.BreakIterator
import java.util.Locale

object Words {
    /** Splits text into word tokens (punctuation and whitespace dropped). */
    fun split(text: String, locale: Locale): List<String> {
        val it = BreakIterator.getWordInstance(locale)
        it.setText(text)
        val out = ArrayList<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val token = text.substring(start, end)
            if (token.any { c -> c.isLetterOrDigit() }) out += token
            start = end
            end = it.next()
        }
        return out
    }
}
