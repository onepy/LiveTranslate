package com.livetranslate.translate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

data class PipelineConfig(
    val providerKey: String,
    val providerName: String,
    val from: Lang,
    val to: Lang,
    val debounceMs: Long,
    val context: List<String> = emptyList(),
    /** Cursor offset inside the submitted text while typing in realtime mode, -1 otherwise. Not a cache key. */
    val caret: Int = -1,
)

data class TranslationUi(
    val source: String = "",
    val result: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    val providerName: String = "",
    val contextCount: Int = 0,
)

/**
 * Turns a stream of input-box texts into translation UI state:
 * debounces typing, cancels in-flight requests when the text changes, and caches results
 * so deleting back to an earlier text is instant.
 */
@OptIn(FlowPreview::class)
class TranslationPipeline(
    scope: CoroutineScope,
    private val config: StateFlow<PipelineConfig>,
    private val translatorFor: (PipelineConfig) -> Translator,
    cacheSize: Int = 128,
) {
    private data class Request(val text: String, val cfg: PipelineConfig, val attempt: Int)
    private data class CacheKey(val text: String, val providerKey: String, val from: Lang, val to: Lang, val context: List<String>)

    private data class Input(val text: String = "", val immediate: Boolean = false, val revision: Long = 0, val context: List<String> = emptyList(), val caret: Int = -1)
    private val input = MutableStateFlow(Input())
    private val retryTick = MutableStateFlow(0)
    private val _state = MutableStateFlow(TranslationUi())
    val state: StateFlow<TranslationUi> = _state.asStateFlow()

    private val cache = object : LinkedHashMap<CacheKey, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, String>) = size > cacheSize
    }

    init {
        scope.launch {
            val translationConfig = config.map { it.copy(debounceMs = 0) }.distinctUntilChanged()
            combine(input, translationConfig, retryTick) { typed, cfg, attempt -> Triple(typed, cfg, attempt) }
                .collectLatest { (typed, cfg, attempt) ->
                    // Cancel an old stream immediately, before waiting for the next typing pause.
                    _state.value = TranslationUi(source = typed.text, loading = typed.text.isNotEmpty(), providerName = cfg.providerName)
                    if (typed.text.isNotEmpty() && !typed.immediate) delay(config.value.debounceMs)
                    run(Request(typed.text, cfg.copy(context = typed.context, caret = typed.caret), attempt))
                }
        }
    }

    /** [immediate] skips the typing debounce (used for explicit triggers such as long-press). */
    fun submit(text: String, immediate: Boolean = false, context: List<String> = emptyList(), caret: Int = -1) {
        val trimmed = text.trim()
        val lead = text.length - text.trimStart().length
        val at = if (caret < 0) -1 else (caret - lead).coerceIn(0, trimmed.length)
        _state.value = TranslationUi(source = trimmed, loading = trimmed.isNotEmpty(), providerName = config.value.providerName)
        input.value = Input(trimmed, immediate, input.value.revision + 1, context.toList(), at)
    }

    fun retry() {
        retryTick.value++
    }

    private suspend fun run(req: Request) {
        if (req.text.isEmpty()) {
            _state.value = TranslationUi(providerName = req.cfg.providerName)
            return
        }
        val key = CacheKey(req.text, req.cfg.providerKey, req.cfg.from, req.cfg.to, req.cfg.context)
        cache[key]?.let {
            _state.value = TranslationUi(req.text, it, false, null, req.cfg.providerName, req.cfg.context.size)
            return
        }
        _state.value = TranslationUi(source = req.text, loading = true, providerName = req.cfg.providerName)
        var last = ""
        try {
            translatorFor(req.cfg).translate(req.text, req.cfg.from, req.cfg.to).collect { partial ->
                last = partial
                _state.value = TranslationUi(req.text, partial, true, null, req.cfg.providerName, req.cfg.context.size)
            }
            _state.value = TranslationUi(req.text, last, false, null, req.cfg.providerName, req.cfg.context.size)
            if (last.isNotBlank()) cache[key] = last
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = TranslationUi(req.text, last, false, e.toUserMessage(), req.cfg.providerName)
        }
    }
}
