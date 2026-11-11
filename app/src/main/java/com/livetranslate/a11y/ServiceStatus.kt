package com.livetranslate.a11y

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.livetranslate.App
import com.livetranslate.keepalive.KeepAlive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the home screen shows about the accessibility service. */
enum class ServiceHealth { PAUSED, OFF, STARTING, NOT_RUNNING, UNRESPONSIVE, OK }

/**
 * Small on-device timeline of service lifecycle events, automatic recoveries and crashes, so problems
 * caused by vendor background killing can be seen in the app and copied into a bug report.
 * Never contains typed text; stored only on the device.
 */
object ServiceLog {
    private const val MAX_LINES = 40
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("service_log", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun add(event: String, sync: Boolean = false) {
        Log.i("LiveTranslate", event)
        val p = prefs ?: return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val lines = (lines() + "$stamp  $event").takeLast(MAX_LINES)
        val edit = p.edit().putString("lines", lines.joinToString("\n"))
        if (sync) edit.commit() else edit.apply()
    }

    fun lines(): List<String> = prefs?.getString("lines", "").orEmpty().lines().filter { it.isNotBlank() }

    /** True once the service has connected at least once, so "off" can be told apart from "never set up". */
    var everConnected: Boolean
        get() = prefs?.getBoolean("ever_connected", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("ever_connected", v)?.apply() }

    /** Time of the last automatic re-enable the user has not acknowledged yet, or 0. */
    val unseenRecoveryAt: Long get() = prefs?.getLong("recovered_at", 0L) ?: 0L

    fun recovered(source: String) {
        add("发现无障碍被关闭，已自动重新开启（$source）")
        prefs?.edit()?.putLong("recovered_at", System.currentTimeMillis())?.apply()
    }

    fun ackRecovery() {
        prefs?.edit()?.remove("recovered_at")?.apply()
    }

    fun recordCrash(thread: Thread, e: Throwable) {
        prefs?.edit()?.putString("last_crash", "主线程：${thread === android.os.Looper.getMainLooper().thread}\n${safeTrace(e)}")?.commit()
        add("崩溃：${e.javaClass.simpleName}", sync = true)
    }

    /** Exception types and stack frames only: exception messages can quote typed or translated text. */
    private fun safeTrace(e: Throwable): String = buildString {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) append("由此引起：")
            appendLine(t.javaClass.name)
            t.stackTrace.take(15).forEach { appendLine("  at $it") }
            t = t.cause
            depth++
        }
    }.trim()

    /**
     * Records an exception that a supervisor caught and recovered from. Only the type and the stack
     * frames are stored: exception messages can quote the text the user typed or its translation.
     */
    fun recordError(label: String, e: Throwable) {
        Log.e("LiveTranslate", "$label ${safeTrace(e)}")
        add("$label：${e.javaClass.simpleName}", sync = true)
        prefs?.edit()?.putString("last_error", "$label\n${safeTrace(e)}")?.apply()
    }

    /** True once the "service stopped" alert has been shown for the current outage, so it does not nag. */
    var lossNotified: Boolean
        get() = prefs?.getBoolean("loss_notified", false) ?: false
        set(v) { prefs?.edit()?.putBoolean("loss_notified", v)?.apply() }

    val lastCrash: String get() = prefs?.getString("last_crash", "").orEmpty()

    /** Last caught-and-recovered internal error: label plus exception types and frames only. */
    val lastError: String get() = prefs?.getString("last_error", "").orEmpty()

    fun clear() {
        prefs?.edit()?.remove("lines")?.remove("last_crash")?.remove("recovered_at")?.remove("last_error")?.apply()
    }

    /** Device, version and timeline, for pasting into an issue. */
    fun report(context: Context, versionName: String): String = buildString {
        appendLine("边写边译 $versionName · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("翻译启用：${App.settings.value.enabled}；系统开关：${A11yEnabler.isEnabled(context)}；服务连接：${InputWatcherService.isConnected()}")
        appendLine("自动恢复授权：${A11yEnabler.canWriteSecureSettings(context)}；通知允许：${KeepAlive.hasNotificationPermission(context)}；电池不受限：${KeepAlive.isIgnoringBatteryOptimizations(context)}")
        InputWatcherService.faultMessage.value?.let { appendLine("当前异常：$it") }
        appendLine()
        lines().forEach(::appendLine)
        if (lastCrash.isNotBlank()) {
            appendLine()
            appendLine("上次崩溃：")
            append(lastCrash)
        }
        if (lastError.isNotBlank()) {
            appendLine()
            appendLine("最近自动恢复的错误：")
            append(lastError)
        }
    }
}
