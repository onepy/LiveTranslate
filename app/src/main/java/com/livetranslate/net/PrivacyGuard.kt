package com.livetranslate.net

import com.livetranslate.privacy.PrivacyRules
import com.livetranslate.translate.HtmlText
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

class PrivacyBlockedException : IOException("request blocked: contains a privacy list entry")

/**
 * Last line of defence behind masking: refuses to send a request whose decoded query values or body
 * strings still contain a list entry. Built-in rules are not checked here, because fixed request
 * fields (e.g. Doubao's 18-digit tea_uuid) would trip the long-number rule.
 */
class PrivacyGuard(private val rules: () -> PrivacyRules?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val r = rules()
        if (r != null && texts(request).any(r::containsEntry)) throw PrivacyBlockedException()
        return chain.proceed(request)
    }

    companion object {
        /** Every user-visible string a request carries, decoded from URL, form, JSON and HTML escaping. */
        fun texts(request: Request): List<String> {
            val out = ArrayList<String>()
            val url = request.url
            for (i in 0 until url.querySize) url.queryParameterValue(i)?.let(out::add)
            when (val body = request.body) {
                null -> Unit
                is FormBody -> for (i in 0 until body.size) out += body.value(i)
                else -> {
                    val raw = Buffer().also(body::writeTo).readUtf8()
                    out += raw
                    runCatching { collect(JSONTokener(raw).nextValue(), out) }
                }
            }
            return out.flatMap { listOf(it, HtmlText.decode(it)) }
        }

        private fun collect(value: Any?, out: MutableList<String>) {
            when (value) {
                is String -> out += value
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), out)
                is JSONObject -> value.keys().forEach { collect(value.opt(it), out) }
            }
        }
    }
}
