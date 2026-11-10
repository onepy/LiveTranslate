package com.livetranslate.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.livetranslate.a11y.FocusedInput
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
    private var view: ComposeView? = null
    private var attached = false
    private var dismissedSource: String? = null
    private var dragging = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        gravity = Gravity.TOP or Gravity.START
    }

    fun render(focus: FocusedInput?, state: TranslationUi, s: AppSettings) {
        if (dismissedSource != null && state.source != dismissedSource) dismissedSource = null
        val hasContent = state.result.isNotBlank() || state.loading || state.error != null
        if (!s.enabled || focus == null || state.source.isBlank() || !hasContent || dismissedSource != null) {
            detach()
            return
        }
        model.value = PanelModel(state, s.displayMode, s.fontSizeSp, s.sourceLang, s.targetLang, s.panelOpacity, state.contextCount,
            maskedCount = if (s.privacy.active) s.privacy.spans(state.source).size else 0)
        if (!dragging) position(focus.bounds, s)
        attach()
    }

    fun dismiss() {
        dismissedSource = model.value.state.source
        detach()
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
        if (attached && old != Triple(params.x, params.y, params.gravity to params.width)) {
            updatePosition()
        }
    }

    private fun attach() {
        if (attached) return
        val first = view ?: createView().also { view = it }
        tryAdd(first) ?: return
        detach()
        val retry = createView().also { view = it }
        val second = tryAdd(retry) ?: return
        detach()
        onWindowError("翻译面板无法显示：${second.javaClass.simpleName}")
    }

    /** Returns null on success. */
    private fun tryAdd(v: ComposeView): Exception? = try {
        wm.addView(v, params)
        attached = true
        onWindowError(null)
        null
    } catch (e: Exception) {
        e
    }

    private fun detach() {
        val previous = view ?: return
        attached = false
        view = null
        runCatching { wm.removeViewImmediate(previous) }
        previous.disposeComposition()
    }

    fun destroy() {
        detach()
        view = null
        lifecycleOwner.destroy()
    }

    private fun onDrag(dx: Float, dy: Float) {
        if (model.value.mode != DisplayMode.FLOATING || !attached) return
        dragging = true
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        if (!updatePosition()) attach()
    }

    private fun updatePosition(): Boolean = try {
        wm.updateViewLayout(view, params)
        true
    } catch (error: Exception) {
        detach()
        onWindowError("翻译面板无法显示：${error.javaClass.simpleName}")
        false
    }

    private fun onDragEnd() {
        if (!dragging) return
        dragging = false
        settings.update { it.copy(floatX = params.x, floatY = params.y) }
    }

    private fun createView() = ComposeView(service).apply {
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(attachedView: View) = Unit

            override fun onViewDetachedFromWindow(detachedView: View) {
                if (view === detachedView && attached) {
                    attached = false
                    view = null
                    (detachedView as ComposeView).disposeComposition()
                    onWindowError("翻译面板无法显示：窗口已被系统移除")
                }
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

    private fun dp(v: Int) = (v * density).roundToInt()
}
