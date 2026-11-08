package com.livetranslate.translate

import android.content.Context
import android.webkit.CookieManager
import com.livetranslate.net.Http
import com.livetranslate.net.SseEvent
import com.livetranslate.net.SseParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.random.Random

/**
 * Doubao web translate (stream_article_translate), the same endpoint the Cometix extension uses.
 * Requires a logged-in doubao.com session (cookies shared from the in-app WebView).
 * [service]: "1" = 豆包, "0" = 火山, "3" = 微软.
 */
class DoubaoTranslator(private val service: String) : Translator {

    override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
        val body = JSONObject()
            .put("raw_text", JSONArray().put(text))
            .put("target_lang", to.doubao)
            .put("translate_service", service)
        val request = Request.Builder()
            .url(DoubaoSession.url(DoubaoSession.TRANSLATE_URL))
            .header("Accept", "text/event-stream")
            .header("Origin", DoubaoSession.ORIGIN)
            .post(body.toString().toRequestBody(Http.JSON))
            .build()

        val parser = SseParser()
        val raw = StringBuilder()
        var got = false

        Http.lines(request).collect { line ->
            if (raw.length < 4000) raw.append(line).append('\n')
            val event = parser.feed(line) ?: return@collect
            if (handle(event, text, to)) got = true
        }
        parser.finish()?.let { if (handle(it, text, to)) got = true }

        if (!got) {
            val obj = runCatching { JSONObject(raw.toString().trim()) }.getOrNull()
            if (obj != null) throw DoubaoSession.error(obj.opt("code")?.toString())
            throw TranslateException("豆包返回了空结果")
        }
    }

    /** Returns true if a translation was emitted. */
    private suspend fun FlowCollector<String>.handle(event: SseEvent, source: String, to: Lang): Boolean {
        when (event.event) {
            "err" -> {
                val code = runCatching { JSONObject(event.data).opt("code")?.toString() }.getOrNull()
                throw DoubaoSession.error(code)
            }
            "json" -> {
                val obj = JSONObject(event.data)
                val code = obj.opt("code")?.toString()
                if (code != "0") throw DoubaoSession.error(code)
                val items = obj.optJSONObject("data")?.optJSONArray("items") ?: return false
                var emitted = false
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    if (item.isNull("res")) continue
                    val res = item.optString("res")
                    emit(if (item.optString("detect_lang") == to.doubao) source else res)
                    emitted = true
                }
                return emitted
            }
        }
        return false
    }
}

object DoubaoSession {
    const val ORIGIN = "chrome-extension://dbjibobgilijgolhjdcbdebjhejelffo"
    const val TRANSLATE_URL = "https://www.doubao.com/samantha/plugin/stream_article_translate"
    const val HOME_URL = "https://www.doubao.com/chat/"
    private const val LAUNCH_URL = "https://www.doubao.com/alice/user/launch"

    private val mutex = Mutex()
    @Volatile private var launched = false
    private var deviceId = "0"
    private var country = ""
    private val webTabId = UUID.randomUUID().toString()
    private var teaUuid = randomTeaUuid()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("doubao", Context.MODE_PRIVATE)
        teaUuid = prefs.getString("tea_uuid", null)?.takeIf { it.length == 18 }
            ?: randomTeaUuid().also { prefs.edit().putString("tea_uuid", it).apply() }
    }

    fun isLoggedIn(): Boolean = sessionId() != null

    /** Current doubao.com `sessionid` cookie value, or null when logged out. */
    fun sessionId(): String? =
        runCatching { CookieManager.getInstance().getCookie("https://www.doubao.com") }.getOrNull()
            ?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("sessionid=") }
            ?.removePrefix("sessionid=")?.takeIf { it.isNotBlank() }

    /**
     * Imports a login copied from a desktop browser: either the whole `Cookie:` header value
     * ("a=1; sessionid=xxx; …") or just the sessionid value. Returns true if a session is now present.
     */
    fun importCookies(raw: String): Boolean {
        val text = raw.trim().removePrefix("Cookie:").removePrefix("cookie:").trim()
        if (text.isEmpty()) return false
        val pairs = if ('=' in text) {
            text.split(';').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
        } else {
            listOf("sessionid" to text)
        }
        val cm = CookieManager.getInstance()
        pairs.forEach { (k, v) -> cm.setCookie("https://www.doubao.com", "$k=$v; Domain=.doubao.com; Path=/; Secure") }
        cm.flush()
        launched = false
        return isLoggedIn()
    }

    fun logout() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        launched = false
    }

    suspend fun url(base: String): HttpUrl {
        ensureLaunched()
        return build(base)
    }

    fun error(code: String?): TranslateException = when (code) {
        "710012000", "710012001", "710022013" -> {
            launched = false
            TranslateException("豆包未登录或登录已过期，请到设置里登录豆包")
        }
        "710012007", "676010003" -> TranslateException("豆包繁忙，请稍后再试")
        "710022002", "710022004" -> TranslateException("豆包拒绝了本次翻译请求")
        else -> TranslateException("豆包返回错误码 ${code ?: "未知"}")
    }

    private suspend fun ensureLaunched() {
        if (launched) return
        mutex.withLock {
            if (launched) return
            runCatching {
                val request = Request.Builder()
                    .url(build(LAUNCH_URL))
                    .header("Content-Type", "application/json")
                    .header("Agw-Js-Conv", "str")
                    .header("Origin", ORIGIN)
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                val obj = JSONObject(Http.text(request))
                if (obj.optInt("code", -1) == 0) {
                    val data = obj.getJSONObject("data")
                    val config = data.optJSONObject("config")
                    val hasTtwid = config?.optString("ttwid").orEmpty().isNotEmpty()
                    deviceId = if (hasTtwid) config!!.optString("web_id", "0").take(64) else "0"
                    country = data.optString("country").take(64)
                }
            }
            launched = true
        }
    }

    private fun build(base: String): HttpUrl = base.toHttpUrl().newBuilder()
        .addQueryParameter("language", "zh")
        .addQueryParameter("browser_language", "zh-CN")
        .addQueryParameter("device_platform", "web")
        .addQueryParameter("aid", "586864")
        .addQueryParameter("real_aid", "586864")
        .addQueryParameter("pkg_type", "release_version")
        .addQueryParameter("device_id", deviceId)
        .addQueryParameter("tea_uuid", teaUuid)
        .addQueryParameter("web_id", teaUuid)
        .addQueryParameter("is_new_user", "0")
        .addQueryParameter("region", country)
        .addQueryParameter("sys_region", country)
        .addQueryParameter("use-olympus-account", "1")
        .addQueryParameter("samantha_web", "1")
        .addQueryParameter("version", "1.38.0")
        .addQueryParameter("version_code", "20800")
        .addQueryParameter("pc_version", "1.38.0")
        .addQueryParameter("web_tab_id", webTabId)
        .build()

    private fun randomTeaUuid(): String =
        (1..2).joinToString("") { (100_000_000 + Random.nextInt(900_000_000)).toString() }
}
