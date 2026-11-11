package com.livetranslate.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.livetranslate.a11y.FocusedInput
import com.livetranslate.a11y.ServiceLog
import com.livetranslate.data.AppSettings
import com.livetranslate.data.DisplayMode
import com.livetranslate.data.SettingsStore
import com.livetranslate.translate.Lang
import com.livetranslate.translate.TranslationUi
import kotlin.math.roundToInt

interface PanelActions {
    fun clearContext() {}
    fun replace()
    fun copy()
    fun speak(text: String)
    fun cycleProvider()
    fun swapLanguages()
    fun retry()
    fun openSettings()
}

data class PanelModel(
    val state: TranslationUi = TranslationUi(),
    val mode: DisplayMode = DisplayMode.ANCHORED,
    val fontSp: Int = 15,
    val from: Lang = Lang.AUTO,
    val to: Lang = Lang.EN,
    val opacity: Float = 0.95f,
    val contextCount: Int = 0,
    /** Private spans masked in the source before sending (shown as "已脱敏N处"). */
    val maskedCount: Int = 0,
)

/**
 * Owns the TYPE_ACCESSIBILITY_OVERLAY window. No overlay permission needed, the window never takes
 * focus (so the keyboard stays up), and it is exempt from Android 12's untrusted-touch blocking.
 *
 * The window is created **once per service runtime**; showing and hiding the panel only toggles its
 * visibility. Removing and re-adding an accessibility overlay on every state change (which is what
 * happened after "替换" or a provider switch) is what made the panel disappear for good on EMUI until
 * the process was killed.
 */
class OverlayController(
    private val service: AccessibilityService,
    private val settings: SettingsStore,
    private val actions: PanelActions,
    /** Called with a message when the panel window cannot be shown, and with null once it shows again. */
    private val onWindowError: (String?) -> Unit = {},
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    private val lifecycleOwner = OverlayLifecycleOwner()
    private val model = mutableStateOf(PanelModel())
    private val window = PanelWindow(now = { SystemClock.elapsedRealtime() })
    private var view: ComposeView? = null
    private var addedAt = 0L
    private var dismissedSource: String? = null
    private var dragging = false
    private var destroyed = false
    private var reportedError: String? = null
    private var createdCount = 0
    private var hideLogged = false
    /** Runs [sync] once more when a hide is only waiting for the min-visible dwell to pass. */
    private val syncRunnable = Runnable { sync() }

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        gravity = Gravity.TOP or Gravity.START
    }

    fun render(focus: FocusedInput?, state: TranslationUi, s: AppSettings) {
        if (destroyed) return
        if (dismissedSource != null && state.source != dismissedSource) dismissedSource = null
        val hasContent = state.result.isNotBlank() || state.loading || state.error != null
        val show = s.enabled && focus != null && state.source.isNotBlank() && hasContent && dismissedSource == null
        if (show) {
            model.value = PanelModel(
                state, s.displayMode, s.fontSizeSp, s.sourceLang, s.targetLang, s.panelOpacity, state.contextCount,
                maskedCount = if (s.privacy.active) s.privacy.spans(state.source).size else 0,
            )
            if (!dragging) position(focus!!.bounds, s)
        } else if (window.visible && !hideLogged) {
            // Only on the visible → hidden transition, and only the reason category: never the text.
            hideLogged = true
            ServiceLog.add("翻译面板隐藏：${hideReason(focus, state, s, hasContent)}")
        }
        if (show) hideLogged = false
        window.want(show)
        sync()
        // Hiding waits for the dwell in PanelWindow; come back to finish the job.
        if (!show && window.visible) syncSoon()
    }

    private fun hideReason(focus: FocusedInput?, state: TranslationUi, s: AppSettings, hasContent: Boolean): String = when {
        !s.enabled -> "服务已关闭"
        focus == null -> "没有跟踪到输入框"
        dismissedSource != null -> "面板已被关闭"
        state.source.isBlank() -> "原文为空"
        !hasContent -> "还没有译文"
        else -> "状态变化"
    }

    /** The dwell in [PanelWindow] delays the hide action; ask again after it, without a timer thread. */
    private fun syncSoon() {
        val v = view ?: return
        v.removeCallbacks(syncRunnable)
        v.postDelayed(syncRunnable, DWELL_MS + HIDE_DELAY_MS + 60L)
    }

    fun dismiss() {
        dismissedSource = model.value.state.source
        window.want(false)
        sync()
        if (window.visible) syncSoon()
    }

    /**
     * Watchdog. The system may drop an accessibility overlay window without telling us, and the
     * panel then never came back. Called periodically by the service; recreates the window if the
     * view is no longer attached.
     */
    fun guard() {
        if (destroyed) return
        val v = view
        if (v == null) {
            if (window.alive || window.attached) window.windowLost(alive = false)
        } else if (window.attached && SystemClock.elapsedRealtime() - addedAt > ATTACH_GRACE_MS && !v.isAttachedToWindow) {
            ServiceLog.add("翻译面板窗口已不在，准备重建")
            // Try the removal too: if the system only detached the view, leaving it registered
            // would give us two windows, one of them invisible and impossible to reach.
            dropWindow(remove = true)
        }
        sync()
    }

    private fun sync() {
        if (destroyed) return
        repeat(MAX_STEPS) {
            when (window.next()) {
                PanelAction.NONE -> return
                PanelAction.CREATE -> create()
                PanelAction.SHOW -> show()
                PanelAction.HIDE -> hide()
            }
        }
    }

    private fun create() {
        val v = view ?: newView().also { view = it }
        window.created()
        v.visibility = View.GONE
        setTouchable(false)
        try {
            wm.addView(v, params)
            addedAt = SystemClock.elapsedRealtime()
            window.windowAdded()
            createdCount++
            if (createdCount > 1) ServiceLog.add("翻译面板窗口重建（第${createdCount}次）")
        } catch (e: Exception) {
            view = null
            runCatching { v.disposeComposition() }
            window.attachFailed()
            reportError("翻译面板无法显示：${e.javaClass.simpleName}")
        }
    }

    private fun show() {
        val v = view ?: return
        v.visibility = View.VISIBLE
        setTouchable(true)
        if (!updateLayout()) return
        window.shown()
        reportError(null)
    }

    private fun hide() {
        view?.visibility = View.GONE
        setTouchable(false)
        updateLayout()
        window.hidden()
    }

    private fun position(input: Rect, s: AppSettings) {
        val screen = wm.currentWindowMetrics.bounds
        val margin = dp(8)
        val old = Triple(params.x, params.y, params.gravity to params.width)
        when (s.displayMode) {
            DisplayMode.ANCHORED -> {
                params.width = screen.width() - margin * 2
                params.x = margin
                if (input.top > screen.height() * 0.3f) {
                    // Normal chat layout: sit right above the input box.
                    params.gravity = Gravity.BOTTOM or Gravity.START
                    params.y = screen.height() - input.top + dp(4)
                } else {
                    // Input box near the top (e.g. a search bar): show below it.
                    params.gravity = Gravity.TOP or Gravity.START
                    params.y = input.bottom + dp(4)
                }
            }
            DisplayMode.FLOATING -> {
                params.gravity = Gravity.TOP or Gravity.START
                params.width = minOf(dp(320), screen.width() - margin * 2)
                params.x = s.floatX.coerceIn(0, (screen.width() - params.width).coerceAtLeast(0))
                params.y = s.floatY.coerceIn(0, (screen.height() - dp(96)).coerceAtLeast(0))
            }
        }
        if (window.attached && old != Triple(params.x, params.y, params.gravity to params.width)) {
            updateLayout()
        }
    }

    private fun newView() = ComposeView(service).apply {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attachedView: View) = Unit

            override fun onViewDetachedFromWindow(detachedView: View) {
                if (view !== detachedView) return
                view = null
                window.windowLost(alive = false)
                runCatching { (detachedView as ComposeView).disposeComposition() }
                if (!destroyed) ServiceLog.add("翻译面板窗口被系统移除，将在需要时重建")
            }
        })
        setViewTreeLifecycleOwner(lifecycleOwner)
        setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        setContent {
            PanelTheme {
                TranslationPanel(
                    model = model.value,
                    actions = actions,
                    onDismiss = ::dismiss,
                    onDrag = ::onDrag,
                    onDragEnd = ::onDragEnd,
                )
            }
        }
    }

    private fun setTouchable(touchable: Boolean) {
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    private fun updateLayout(): Boolean {
        val v = view ?: return false
        return try {
            wm.updateViewLayout(v, params)
            true
        } catch (e: Exception) {
            // A failed relayout used to tear the window down and rebuild it, which is the expensive
            // path that can leave no panel at all. Keep the window, retry on the next sync, and let
            // the watchdog decide if it is really gone.
            ServiceLog.add("翻译面板布局更新失败：${e.javaClass.simpleName}")
            reportError("翻译面板无法显示：${e.javaClass.simpleName}")
            false
        }
    }

    /** Forgets the window and tears the composition down; the next [sync] creates a fresh one. */
    private fun dropWindow(remove: Boolean) {
        val v = view
        view = null
        window.windowLost(alive = false)
        if (v != null) {
            v.removeCallbacks(syncRunnable)
            if (remove) runCatching { wm.removeViewImmediate(v) }
            runCatching { v.disposeComposition() }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        dropWindow(remove = true)
        lifecycleOwner.destroy()
    }

    private fun onDrag(dx: Float, dy: Float) {
        if (model.value.mode != DisplayMode.FLOATING || !window.attached) return
        dragging = true
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        updateLayout()
    }

    private fun onDragEnd() {
        if (!dragging) return
        dragging = false
        settings.update { it.copy(floatX = params.x, floatY = params.y) }
    }

    private fun reportError(message: String?) {
        if (reportedError == message) return
        reportedError = message
        onWindowError(message)
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    private companion object {
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        const val MAX_STEPS = 4

        /** addView returns before the first traversal attaches the view; don't call that a loss. */
        const val ATTACH_GRACE_MS = 2000L

        /** Mirrors the min-visible dwell in [PanelWindow]: how long before finishing a pending hide. */
        const val DWELL_MS = 450L

        /** Mirrors the blank-state delay in [PanelWindow]: a momentary blank must not hide the panel. */
        const val HIDE_DELAY_MS = 400L
    }
}
