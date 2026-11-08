package com.livetranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import com.livetranslate.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

/** Wraps TextToSpeech; re-creates the engine when the chosen engine in settings changes. */
class TtsManager(
    private val context: Context,
    scope: CoroutineScope,
    private val settings: SettingsStore,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: Pair<String, Locale>? = null
    private var generation = 0
    private val _engines = MutableStateFlow<List<TextToSpeech.EngineInfo>>(emptyList())

    /** Installed TTS engines (filled once the first engine has initialised). */
    val engines: StateFlow<List<TextToSpeech.EngineInfo>> = _engines

    init {
        scope.launch {
            settings.flow.map { it.ttsEngine }.distinctUntilChanged().collect { create(it) }
        }
    }

    private fun create(engine: String) {
        tts?.shutdown()
        ready = false
        val gen = ++generation
        tts = TextToSpeech(context, { status ->
            if (gen != generation) return@TextToSpeech
            ready = status == TextToSpeech.SUCCESS
            _engines.value = tts?.engines.orEmpty()
            pending?.let { (text, locale) -> if (ready) speakNow(text, locale) }
            pending = null
        }, engine.ifBlank { null })
    }

    fun speak(text: String, locale: Locale) {
        if (text.isBlank()) return
        if (!ready) {
            pending = text to locale
            return
        }
        speakNow(text, locale)
    }

    private fun speakNow(text: String, locale: Locale) {
        val engine = tts ?: return
        engine.language = locale
        engine.setSpeechRate(settings.value.ttsRate)
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lt-${System.nanoTime()}")
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
