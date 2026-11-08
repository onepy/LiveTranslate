package com.livetranslate.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.livetranslate.App
import com.livetranslate.data.SettingsStore
import com.livetranslate.data.TriggerMode
import com.livetranslate.keepalive.KeepAlive
import com.livetranslate.overlay.OverlayController
import com.livetranslate.overlay.PanelActions
import com.livetranslate.translate.Providers
import com.livetranslate.translate.TranslationPipeline
import com.livetranslate.translate.toPipelineConfig
import com.livetranslate.tts.TtsManager
import com.livetranslate.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InputWatcherService : AccessibilityService(), PanelActions {

    private var scope: CoroutineScope? = null
    private lateinit var settings: SettingsStore
    private lateinit var pipeline: TranslationPipeline
    private lateinit var overlay: OverlayController
    private lateinit var tts: TtsManager

    private val shortContext = com.livetranslate.translate.ShortContext { android.os.SystemClock.elapsedRealtime() }
    private var draftContext: List<String> = emptyList()
    private var contextEditor: AccessibilityNodeInfo? = null
    private var contextPackage: String? = null
    private val focused = MutableStateFlow<FocusedInput?>(null)
    /** Text we just wrote into the box via "替换"; not re-translated until the user edits it. */
    private var suppressedText: String? = null
    private var targetRange: TranslationRange? = null
    private var refreshJob: Job? = null
    private var nodeDiagnostic: NodeDiagnostic? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val scope = MainScope().also { this.scope = it }
        settings = App.settings
        nodeDiagnostic = NodeDiagnostic(this).also { it.register() }
        val config = settings.flow.map { it.toPipelineConfig() }
            .stateIn(scope, SharingStarted.Eagerly, settings.value.toPipelineConfig())
        pipeline = TranslationPipeline(scope, config, translatorFor = { cfg -> Providers.create(settings.value.providerId, settings.value, cfg.context, cfg.caret) })
        tts = TtsManager(this, scope, settings)
        overlay = OverlayController(this, settings, this)

        scope.launch {
            combine(focused, pipeline.state, settings.flow) { f, st, s -> Triple(f, st, s) }
                .collect { (f, st, s) -> overlay.render(f, st, s) }
        }
        scope.launch {
            settings.flow.map { listOf(it.enabled, it.appScope, it.whitelist, it.triggerMode, it.shortContext, it.providerId, it.sourceLang, it.targetLang, it.privacy) }
                .distinctUntilChanged().drop(1)
                .collect {
                    shortContext.clear()
                    draftContext = emptyList()
                    targetRange = null
                    pipeline.submit("")
                    recheckFocus()
                }
        }
        scope.launch {
            settings.flow.map { it.keepAliveNotification to it.enabled }
                .distinctUntilChanged()
                .collect { (keep, enabled) -> updateForeground(keep, enabled) }
        }
        running.value = true
        recheckFocus()
    }

    /** A foreground notification raises process priority so ROMs are less eager to kill us. */
    private fun updateForeground(keep: Boolean, enabled: Boolean) {
        if (!keep) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val notification = KeepAlive.notification(this, enabled)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(KeepAlive.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(KeepAlive.NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "startForeground failed", it) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (scope == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                rootInActiveWindow?.packageName?.toString() == packageName) {
                shortContext.clear()
                draftContext = emptyList()
                contextEditor = null
                contextPackage = null
            }
            return
        }
        if (contextPackage != null && pkg != contextPackage &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            rootInActiveWindow?.packageName?.toString() == pkg) {
            shortContext.clear()
            draftContext = emptyList()
            contextEditor = null
            contextPackage = null
        }
        if (!settings.value.enabled) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val node = event.source
                if (InputNodes.isUsable(node)) {
                    if (settings.value.allows(pkg)) onInput(
                        node!!, pkg, explicit = isExplicitTrigger(event),
                        textChanged = event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                        selectionChanged = event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
                    ) else clearFocus()
                } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    recheckFocus()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> recheckFocus()
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleRefresh()
        }
    }

    /**
     * Long-press mode trigger. We only *observe* the long-click (never consume it), so the app's own
     * paste / select-all / copy menu still appears. Long-pressing text usually selects a word, so a
     * non-empty selection also counts — this covers editors that don't report the long-click itself.
     */
    private fun isExplicitTrigger(event: AccessibilityEvent): Boolean = when (event.eventType) {
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> true
        AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> event.fromIndex >= 0 && event.fromIndex != event.toIndex
        else -> false
    }

    private fun onInput(
        node: AccessibilityNodeInfo,
        pkg: String,
        explicit: Boolean = false,
        textChanged: Boolean = false,
        selectionChanged: Boolean = false,
    ) {
        val text = InputNodes.read(node)
        if (contextEditor != null && (contextEditor != node || contextPackage != pkg)) {
            shortContext.clear()
            draftContext = emptyList()
        }
        contextEditor = node
        contextPackage = pkg
        val contextEnabled = settings.value.shortContext && Providers.supportsContext(settings.value.providerId)
        // Freeze at the first actual text edit, not after debounce or long-press.
        if (textChanged && contextEnabled && text != suppressedText) draftContext = shortContext.begin()
        val previous = focused.value
        val sameEditor = previous != null && previous.pkg == pkg && previous.node == node &&
            previous.node.windowId == node.windowId
        val changed = textChanged || (sameEditor && previous!!.text != text)
        if (changed && contextEnabled && text != suppressedText) draftContext = shortContext.begin()
        if (!sameEditor) {
            targetRange = null
            suppressedText = null
            pipeline.submit("")
        }
        focused.value = FocusedInput(pkg, text, InputNodes.bounds(node), node)
        if (suppressedText != null) {
            if (text == suppressedText) return
            suppressedText = null
        }

        val shouldTranslate = when (settings.value.triggerMode) {
            TriggerMode.REALTIME -> changed
            TriggerMode.LONG_PRESS -> explicit
        }
        if (!shouldTranslate) {
            val target = targetRange
            if (target != null && (target.snapshot != text ||
                    (selectionChanged && !target.containsSelection(node.textSelectionStart, node.textSelectionEnd)))) {
                targetRange = null
                pipeline.submit("")
            }
            return
        }

        val fallback = if (changed && sameEditor) TranslationRanges.changedCaret(previous!!.text, text) else -1
        val start = node.textSelectionStart.takeIf { it in 0..text.length } ?: fallback
        val end = node.textSelectionEnd.takeIf { it in 0..text.length } ?: start
        val range = TranslationRanges.select(text, start, end, explicit && settings.value.triggerMode == TriggerMode.LONG_PRESS)
        targetRange = range
        // Realtime typing: tell masking where the cursor is, so a half-typed entry or number is masked too.
        val caret = if (range != null && settings.value.triggerMode == TriggerMode.REALTIME && start >= range.start)
            (start - range.start).coerceAtMost(range.text.length) else -1
        pipeline.submit(range?.text.orEmpty(), immediate = settings.value.triggerMode == TriggerMode.LONG_PRESS,
            context = if (contextEnabled) draftContext else emptyList(), caret = caret)
    }

    private fun clearFocus() {
        focused.value = null
        suppressedText = null
        targetRange = null
        pipeline.submit("")
    }

    private fun recheckFocus() {
        val s = settings.value
        if (!s.enabled) {
            clearFocus()
            return
        }
        val node = runCatching { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val pkg = node?.packageName?.toString()
        if (!InputNodes.isUsable(node) || pkg == null || pkg == packageName || !s.allows(pkg)) {
            clearFocus()
            return
        }
        onInput(node!!, pkg)
    }

    /** Content changes are very frequent (e.g. keyboard animation); refresh the input box at most every ~120ms. */
    private fun scheduleRefresh() {
        if (focused.value == null || refreshJob?.isActive == true) return
        refreshJob = scope?.launch {
            delay(120)
            val cur = focused.value ?: return@launch
            if (!cur.node.refresh() || !cur.node.isFocused) {
                recheckFocus()
                return@launch
            }
            val text = InputNodes.read(cur.node)
            if (text != cur.text) {
                onInput(cur.node, cur.pkg)
            } else {
                val bounds = InputNodes.bounds(cur.node)
                if (bounds != cur.bounds) focused.value = cur.copy(bounds = bounds)
            }
        }
    }

    // ---- Panel actions ----

    override fun replace() {
        val cur = focused.value ?: return
        val range = targetRange ?: return
        val state = pipeline.state.value
        val result = state.result.takeIf { it.isNotBlank() && !state.loading && state.error == null && state.source == range.text } ?: return
        val active = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (active == null || active != cur.node || active.windowId != cur.node.windowId ||
            !InputNodes.isUsable(active) || InputNodes.read(active) != range.snapshot) {
            targetRange = null
            pipeline.submit("")
            toast("原文或输入框已变化，请重新翻译")
            return
        }
        suppressedText = range.replace(range.snapshot, result)
        if (!InputNodes.replace(this, active, range, result)) {
            suppressedText = null
            InputNodes.copy(this, result)
            toast("未替换，译文已复制，请手动粘贴")
        } else {
            if (settings.value.shortContext && Providers.supportsContext(settings.value.providerId)) {
                shortContext.committed(range.text)
                draftContext = emptyList()
            }
            toast("已替换")
        }
        targetRange = null
        pipeline.submit("")
    }

    override fun copy() {
        val result = pipeline.state.value.result.takeIf { it.isNotBlank() } ?: return
        InputNodes.copy(this, result)
        toast("已复制译文")
    }

    override fun speak(text: String) = tts.speak(text, settings.value.targetLang.locale)

    override fun cycleProvider() = settings.update { it.copy(providerId = Providers.next(it.providerId, it)) }

    override fun swapLanguages() = settings.update { it.swappedLanguages() }

    override fun clearContext() {
        shortContext.clear()
        draftContext = emptyList()
        targetRange?.let { pipeline.submit(it.text, immediate = true) }
    }

    override fun retry() = pipeline.retry()

    override fun openSettings() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        running.value = false
        nodeDiagnostic?.let { runCatching { unregisterReceiver(it) } }
        nodeDiagnostic = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        scope?.cancel()
        scope = null
        if (::overlay.isInitialized) overlay.destroy()
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "InputWatcher"
        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running
    }
}
