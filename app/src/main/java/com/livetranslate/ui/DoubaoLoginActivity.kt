package com.livetranslate.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.livetranslate.translate.DoubaoSession

/**
 * Doubao login page. Uses the system WebView (not bundled in the APK). Closes itself as soon as
 * a new session cookie appears, so the user only has to log in.
 */
class DoubaoLoginActivity : Activity() {
    private lateinit var web: WebView
    private var desktop = false
    private var mobileUa = ""
    private var initialSession: String? = null
    private var done = false
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            checkLogin()
            if (!done) handler.postDelayed(this, 1500)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialSession = DoubaoSession.sessionId()
        CookieManager.getInstance().setAcceptCookie(true)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                // Swallow "open in app" deep links (intent://, snssdk…) instead of showing an error page.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme !in setOf("http", "https")

                override fun onPageFinished(view: WebView, url: String) = checkLogin()
            }
        }
        mobileUa = web.settings.userAgentString

        val hint = TextView(this).apply {
            text = "登录豆包，成功后自动返回"
            textSize = 15f
            setPadding(32, 0, 0, 0)
        }
        val toggleUa = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "电脑版"
            setOnClickListener {
                desktop = !desktop
                web.settings.userAgentString = if (desktop) DESKTOP_UA else mobileUa
                text = if (desktop) "手机版" else "电脑版"
                web.reload()
            }
        }
        val close = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hint, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(toggleUa)
            addView(close)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        })
        web.loadUrl(DoubaoSession.HOME_URL)
        handler.postDelayed(poll, 1500)
    }

    private fun checkLogin() {
        if (done) return
        val session = DoubaoSession.sessionId() ?: return
        if (session == initialSession) return
        done = true
        CookieManager.getInstance().flush()
        Toast.makeText(this, "豆包登录成功", Toast.LENGTH_SHORT).show()
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else finish()
    }

    override fun onDestroy() {
        done = true
        handler.removeCallbacks(poll)
        web.destroy()
        super.onDestroy()
    }

    private companion object {
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }
}
