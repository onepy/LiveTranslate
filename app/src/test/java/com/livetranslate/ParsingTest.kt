package com.livetranslate

import com.livetranslate.net.SseEvent
import com.livetranslate.net.SseParser
import com.livetranslate.overlay.Words
import com.livetranslate.translate.ChatCompletionsTranslator
import com.livetranslate.translate.ChatStream
import com.livetranslate.translate.ResponsesStream
import com.livetranslate.translate.ResponsesTranslator
import com.livetranslate.translate.TranslateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class ParsingTest {

    private fun parseAll(vararg lines: String): List<SseEvent> {
        val p = SseParser()
        return lines.mapNotNull { p.feed(it) } + listOfNotNull(p.finish())
    }

    @Test
    fun sse_openAiChunks() {
        val events = parseAll(
            "data: {\"a\":1}", "",
            ": keep-alive", "",
            "data: [DONE]", "",
        )
        assertEquals(listOf(SseEvent("message", "{\"a\":1}"), SseEvent("message", "[DONE]")), events)
    }

    @Test
    fun sse_doubaoNamedEvents_andCrLf() {
        val events = parseAll(
            "event: json\r", "data: {\"code\":0}\r", "\r",
            "event: done", "",
        )
        assertEquals(listOf(SseEvent("json", "{\"code\":0}"), SseEvent("done", "")), events)
    }

    @Test
    fun sse_lastEventWithoutTrailingBlankLine() {
        assertEquals(listOf(SseEvent("message", "x")), parseAll("data: x"))
    }

    @Test
    fun chat_deltaExtraction() {
        // Captured from index-translate.bilibili.com on 2026-11-07.
        val first = """{"id":"c","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}"""
        val word = """{"id":"c","choices":[{"index":0,"delta":{"content":" you"},"finish_reason":null}]}"""
        val nullContent = """{"choices":[{"index":0,"delta":{"content":null},"finish_reason":"stop"}]}"""
        assertEquals("", ChatStream.deltaOf(first))
        assertEquals(" you", ChatStream.deltaOf(word))
        assertNull(ChatStream.deltaOf(nullContent))
        assertNull(ChatStream.deltaOf("[DONE]"))
    }

    @Test(expected = TranslateException::class)
    fun chat_errorObjectThrows() {
        ChatStream.deltaOf("""{"error":{"message":"bad key"}}""")
    }

    @Test
    fun chat_parseWholeNonStreaming() {
        assertEquals("Hi", ChatStream.parseWhole("""{"choices":[{"message":{"content":"Hi"}}]}"""))
    }

    @Test
    fun chat_cleanThinkBlocks() {
        assertEquals("Hello", ChatStream.clean("<think>reasoning</think>\n Hello "))
        assertEquals("", ChatStream.clean("<think>still going"))
    }

    @Test
    fun responses_streamEvents() {
        assertEquals(
            ResponsesStream.Delta("Hel"),
            ResponsesStream.parse("""{"type":"response.output_text.delta","item_id":"m1","output_index":0,"content_index":0,"delta":"Hel"}"""),
        )
        assertNull(ResponsesStream.parse("""{"type":"response.created","response":{"id":"r1"}}"""))
        val completed = """{"type":"response.completed","response":{"id":"r1","output":[
            {"type":"reasoning","summary":[]},
            {"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello","annotations":[]}]}
        ]}}"""
        assertEquals(ResponsesStream.Done("Hello"), ResponsesStream.parse(completed))
    }

    @Test(expected = TranslateException::class)
    fun responses_failedThrows() {
        ResponsesStream.parse("""{"type":"response.failed","response":{"error":{"message":"quota"}}}""")
    }

    @Test
    fun responses_parseWholeNonStreaming() {
        val body = """{"object":"response","output":[{"type":"message","content":[{"type":"output_text","text":"Hi there"}]}]}"""
        assertEquals("Hi there", ResponsesStream.parseWhole(body))
    }

    @Test
    fun endpoints() {
        assertEquals("https://x/v1/responses", ResponsesTranslator.endpoint("https://x/v1/"))
        assertEquals("https://x/v1/responses", ResponsesTranslator.endpoint("https://x/v1/responses"))
        assertEquals("https://x/v1/chat/completions", ChatCompletionsTranslator.endpoint("https://x/v1"))
    }

    @Test
    fun words_splitEnglish() {
        assertEquals(
            listOf("Shall", "we", "have", "dinner", "tonight", "don't"),
            Words.split("Shall we have dinner, tonight? don't", Locale.US),
        )
    }
}
