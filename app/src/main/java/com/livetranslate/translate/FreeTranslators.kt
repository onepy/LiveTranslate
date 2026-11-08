package com.livetranslate.translate

import com.livetranslate.BuildConfig
import com.livetranslate.net.Http
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONTokener

/** Edge browser's free translate endpoint (same as the extension's "微软翻译"). */
class MicrosoftFreeTranslator : Translator {
    override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
        val url = "https://edge.microsoft.com/translate/translatetext".toHttpUrl().newBuilder().apply {
            if (from != Lang.AUTO) addQueryParameter("from", from.microsoft)
            addQueryParameter("to", to.microsoft)
            addQueryParameter("isEnterpriseClient", "false")
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(JSONArray().put(text).toString().toRequestBody(Http.JSON))
            .build()
        val json = JSONArray(Http.text(request))
        emit(
            json.getJSONObject(0).getJSONArray("translations").getJSONObject(0).getString("text")
        )
    }
}

/** translate.googleapis.com translate_a/t with client=gtx ("谷歌翻译"). */
class GoogleFreeTranslator : Translator {
    override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
        val url = "https://translate.googleapis.com/translate_a/t".toHttpUrl().newBuilder()
            .addQueryParameter("client", "gtx")
            .addQueryParameter("dt", "t")
            .addQueryParameter("sl", from.google)
            .addQueryParameter("tl", to.google)
            .build()
        val request = Request.Builder().url(url).post(FormBody.Builder().add("q", text).build()).build()
        val value = JSONTokener(Http.text(request).trim()).nextValue()
        val result = when (value) {
            is String -> value
            is JSONArray -> value.get(0).let { if (it is JSONArray) it.getString(0) else it.toString() }
            else -> throw TranslateException("谷歌翻译返回格式异常")
        }
        emit(HtmlText.decode(result))
    }
}

/** Chrome's built-in translateHtml endpoint ("谷歌翻译2"). */
class GoogleBrowserTranslator : Translator {
    override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
        if (!isConfigured) throw TranslateException("谷歌翻译2 未配置 API Key，请换用其它翻译源")
        val body = JSONArray()
            .put(JSONArray().put(JSONArray().put(HtmlText.escape(text))).put(from.google).put(to.google))
            .put("te_lib")
        val request = Request.Builder()
            .url("https://translate-pa.googleapis.com/v1/translateHtml")
            .header("X-Goog-Api-Key", BuildConfig.GOOGLE_TRANSLATE_API_KEY)
            .post(body.toString().toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        val root = JSONArray(Http.text(request))
        val first = root.get(0)
        val list = if (first is JSONArray) first else root
        val item = list.get(0)
        emit(HtmlText.decode(if (item is JSONArray) item.getString(0) else item.toString()))
    }

    companion object {
        // Public key embedded in Chrome's translate component. Injected at build time from
        // local.properties (GOOGLE_TRANSLATE_API_KEY) so it never lands in git.
        val isConfigured get() = BuildConfig.GOOGLE_TRANSLATE_API_KEY.isNotBlank()
    }
}

object HtmlText {
    fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun decode(s: String) = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#34;", "\"")
        .replace("&#39;", "'").replace("&#160;", " ").replace("&nbsp;", " ")
        .replace("&amp;", "&")
}
