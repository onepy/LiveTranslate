package com.livetranslate.translate

import com.livetranslate.net.Http
import com.livetranslate.net.SseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI-compatible /chat/completions with SSE streaming (Bilibili Index-Translate, DeepSeek, Ollama…). */
class ChatCompletionsTranslator(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val style: String = "",
    private val instruction: String? = null,
    private val extraBody: JSONObject.() -> Unit = {},
) : Translator {

    override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
        if (baseUrl.isBlank() || model.isBlank()) throw TranslateException("请先在设置中填写接口地址和模型")
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", instruction ?: systemPrompt(from, to, style)))
                    .put(JSONObject().put("role", "user").put("content", text))
            )
            extraBody()
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl))
            .header("Accept", "text/event-stream")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val parser = SseParser()
        val acc = StringBuilder()
        val raw = StringBuilder()
        var lastEmitted = ""

        Http.lines(request).collect { line ->
            if (raw.length < 64_000) raw.append(line).append('\n')
            val event = parser.feed(line) ?: return@collect
            val delta = ChatStream.deltaOf(event.data) ?: return@collect
            acc.append(delta)
            val cleaned = ChatStream.clean(acc.toString())
            if (cleaned != lastEmitted) {
                lastEmitted = cleaned
                emit(cleaned)
            }
        }
        parser.finish()?.let { ev -> ChatStream.deltaOf(ev.data)?.let { acc.append(it) } }

        if (acc.isEmpty()) {
            // Server ignored stream=true or returned an error object with HTTP 200.
            val whole = ChatStream.parseWhole(raw.toString().trim())
            acc.append(whole)
        }
        val final = ChatStream.clean(acc.toString())
        if (final.isEmpty()) throw TranslateException("翻译源返回了空结果")
        if (final != lastEmitted) emit(final)
    }

    companion object {
        fun endpoint(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

        fun systemPrompt(from: Lang, to: Lang, style: String = ""): String {
            val fromPart = if (from == Lang.AUTO) "" else "from ${from.englishName} "
            val stylePart = if (style.isBlank()) "" else " Style: ${style.trim()}"
            return "You are a professional translation engine. Translate the text provided by the user " +
                "${fromPart}into ${to.englishName}. Output only the translation itself, without explanations, " +
                "notes, quotes or the original text.$stylePart"
        }
    }
}

object ChatStream {
    private val thinkBlock = Regex("(?s)<think>.*?</think>")

    /** Returns the content delta of one SSE data payload, or null for [DONE] / non-content chunks. */
    fun deltaOf(data: String): String? {
        val trimmed = data.trim()
        if (trimmed.isEmpty() || trimmed == "[DONE]" || !trimmed.startsWith("{")) return null
        val obj = JSONObject(trimmed)
        obj.optJSONObject("error")?.let { throw TranslateException("翻译源报错：${it.optString("message")}") }
        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: return null
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return null
        if (delta.isNull("content")) return null
        return delta.optString("content")
    }

    /** Parses a complete non-streaming response body. */
    fun parseWhole(body: String): String {
        if (!body.startsWith("{")) return ""
        val obj = JSONObject(body)
        obj.optJSONObject("error")?.let { throw TranslateException("翻译源报错：${it.optString("message")}") }
        obj.optString("detail").takeIf { it.isNotBlank() }?.let { throw TranslateException("翻译源报错：$it") }
        val msg = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return if (msg.isNull("content")) "" else msg.optString("content")
    }

    /** Drops reasoning blocks some models emit and trims whitespace. */
    fun clean(text: String): String {
        var t = thinkBlock.replace(text, "")
        if (t.trimStart().startsWith("<think>")) t = "" // still thinking
        return t.trim()
    }
}
