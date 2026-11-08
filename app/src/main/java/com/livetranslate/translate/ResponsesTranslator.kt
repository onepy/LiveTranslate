package com.livetranslate.translate

import com.livetranslate.net.Http
import com.livetranslate.net.SseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** OpenAI Responses API (POST …/responses) with SSE streaming. */
class ResponsesTranslator(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val style: String = "",
    private val instruction: String? = null,
) : Translator {

    override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
        if (baseUrl.isBlank() || model.isBlank()) throw TranslateException("请先在设置中填写接口地址和模型")
        val body = JSONObject()
            .put("model", model)
            .put("instructions", instruction ?: ChatCompletionsTranslator.systemPrompt(from, to, style))
            .put("input", text)
            .put("stream", true)
            .put("store", false)
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
            when (val part = ResponsesStream.parse(event.data)) {
                is ResponsesStream.Delta -> acc.append(part.text)
                is ResponsesStream.Done -> if (acc.isEmpty()) acc.append(part.text)
                null -> return@collect
            }
            val cleaned = ChatStream.clean(acc.toString())
            if (cleaned != lastEmitted) {
                lastEmitted = cleaned
                emit(cleaned)
            }
        }
        parser.finish()?.let { ev ->
            when (val part = ResponsesStream.parse(ev.data)) {
                is ResponsesStream.Delta -> acc.append(part.text)
                is ResponsesStream.Done -> if (acc.isEmpty()) acc.append(part.text)
                null -> Unit
            }
        }
        if (acc.isEmpty()) acc.append(ResponsesStream.parseWhole(raw.toString().trim()))

        val final = ChatStream.clean(acc.toString())
        if (final.isEmpty()) throw TranslateException("翻译源返回了空结果")
        if (final != lastEmitted) emit(final)
    }

    companion object {
        fun endpoint(baseUrl: String): String {
            val base = baseUrl.trim().trimEnd('/')
            return if (base.endsWith("/responses")) base else "$base/responses"
        }
    }
}

object ResponsesStream {
    sealed interface Part
    data class Delta(val text: String) : Part
    data class Done(val text: String) : Part

    /** Parses one SSE data payload of the Responses streaming protocol. */
    fun parse(data: String): Part? {
        val trimmed = data.trim()
        if (!trimmed.startsWith("{")) return null
        val obj = JSONObject(trimmed)
        return when (obj.optString("type")) {
            "response.output_text.delta" -> Delta(obj.optString("delta"))
            "response.completed", "response.incomplete" -> Done(outputText(obj.optJSONObject("response") ?: obj))
            "response.failed" -> {
                val msg = obj.optJSONObject("response")?.optJSONObject("error")?.optString("message")
                throw TranslateException("翻译源报错：${msg ?: "response.failed"}")
            }
            "error" -> {
                val msg = obj.optString("message").ifBlank { obj.optJSONObject("error")?.optString("message").orEmpty() }
                throw TranslateException("翻译源报错：$msg")
            }
            else -> null
        }
    }

    /** Parses a complete non-streaming Responses body. */
    fun parseWhole(body: String): String {
        if (!body.startsWith("{")) return ""
        val obj = JSONObject(body)
        obj.optJSONObject("error")?.let { throw TranslateException("翻译源报错：${it.optString("message")}") }
        return outputText(obj)
    }

    fun outputText(response: JSONObject): String {
        response.optString("output_text").takeIf { it.isNotEmpty() }?.let { return it }
        val output = response.optJSONArray("output") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") sb.append(c.optString("text"))
            }
        }
        return sb.toString()
    }
}
