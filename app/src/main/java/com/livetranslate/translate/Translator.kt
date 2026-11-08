package com.livetranslate.translate

import com.livetranslate.net.HttpException
import kotlinx.coroutines.flow.Flow
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

interface Translator {
    /** Emits the cumulative translation. Streaming providers emit growing prefixes; others emit once. */
    fun translate(text: String, from: Lang, to: Lang): Flow<String>
}

class TranslateException(message: String) : Exception(message)

fun Throwable.toUserMessage(): String = when (this) {
    is TranslateException -> message.orEmpty()
    is HttpException -> when (code) {
        401, 403 -> "鉴权失败（HTTP $code），请检查 API Key 或登录状态"
        412 -> "请求被服务端拦截（HTTP 412），稍后再试"
        429 -> "请求太频繁（HTTP 429），稍后再试"
        else -> "请求失败（HTTP $code）${body.take(120).let { if (it.isBlank()) "" else "：$it" }}"
    }
    is UnknownHostException, is ConnectException -> "网络连接失败，请检查网络"
    is SocketTimeoutException -> "请求超时"
    is SSLException -> "安全连接失败（${message}）"
    is JSONException -> "翻译源返回了无法解析的数据"
    is IOException -> "网络错误：${message ?: javaClass.simpleName}"
    else -> message ?: javaClass.simpleName
}
