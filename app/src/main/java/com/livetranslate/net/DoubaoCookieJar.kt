package com.livetranslate.net

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Shares cookies for doubao.com with the WebView cookie store, so logging in through
 * DoubaoLoginActivity makes the session available to OkHttp. Other hosts get no cookies.
 */
object DoubaoCookieJar : CookieJar {
    private fun handles(url: HttpUrl) = url.host == "doubao.com" || url.host.endsWith(".doubao.com")

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!handles(url) || cookies.isEmpty()) return
        val cm = CookieManager.getInstance()
        cookies.forEach { cm.setCookie(url.toString(), it.toString()) }
        cm.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!handles(url)) return emptyList()
        val raw = CookieManager.getInstance().getCookie(url.toString()) ?: return emptyList()
        return raw.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
