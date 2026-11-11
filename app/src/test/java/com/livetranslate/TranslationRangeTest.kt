package com.livetranslate

import com.livetranslate.a11y.TranslationRanges
import org.junit.Assert.*
import org.junit.Test

class TranslationRangeTest {
    @Test fun onlyCurrentParagraphAndPreserveRest() {
        val doc = "旧笔记\n\n  今天吃饭吗？  \n后面的内容"
        val range = TranslationRanges.select(doc, 12, 12, false)!!
        assertEquals("今天吃饭吗？", range.text)
        assertEquals("旧笔记\n\n  Dinner tonight?  \n后面的内容", range.replace(doc, "Dinner tonight?"))
    }
    @Test fun selectedTextOnlyForExplicitTrigger() {
        val doc = "hello world\nnext"
        assertEquals("world", TranslationRanges.select(doc, 6, 11, true)!!.text)
        assertEquals("hello world", TranslationRanges.select(doc, 6, 11, false)!!.text)
        assertEquals("world", TranslationRanges.select(doc, 11, 6, true)!!.text)
    }
    @Test fun staleDocumentRejectedEvenOutsideTarget() {
        val doc = "old\nhello"
        val range = TranslationRanges.select(doc, doc.length, doc.length, false)!!
        assertNull(range.replace("changed\nhello", "你好"))
    }
    @Test fun emptyParagraphUnknownCursorAndPunctuationIgnored() {
        assertNull(TranslationRanges.select("old\n\nnext", 4, 4, false))
        assertNull(TranslationRanges.select("old\nnext", -1, -1, false))
        assertNull(TranslationRanges.select("!!!", 2, 2, false))
    }
    @Test fun crlfAndEmojiOffsets() {
        val doc = "first\r\n😀 hello\r\nlast"
        val caret = doc.indexOf("hello") + 2
        val range = TranslationRanges.select(doc, caret, caret, false)!!
        assertEquals("😀 hello", range.text)
        assertEquals("first\r\n你好\r\nlast", range.replace(doc, "你好"))
    }
    @Test fun diffFallbackInsertionAndDeletion() {
        assertEquals(6, TranslationRanges.changedCaret("old\nx", "old\nxy"))
        assertEquals(4, TranslationRanges.changedCaret("old\nx", "old\n"))
        assertEquals(2, TranslationRanges.changedCaret("abcd", "abXcd") - 1)
    }

    /**
     * The exact shape that used to blank the panel: after typing a space the caret sits one past the
     * trimmed paragraph, so the paragraph under the caret is unchanged and must be re-used as-is.
     */
    @Test fun caretJustPastTrailingSpaceKeepsTheSameParagraph() {
        val doc = "hello world "
        val typed = TranslationRanges.select(doc, 11, 11, false)!!
        assertEquals("hello world", typed.text)
        val afterSpace = TranslationRanges.select(doc, 12, 12, false)!!
        assertEquals(typed, afterSpace)
        assertFalse("caret one past the trim is outside the replaced span", typed.containsSelection(12, 12))
    }
}
