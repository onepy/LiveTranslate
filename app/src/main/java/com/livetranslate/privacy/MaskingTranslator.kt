package com.livetranslate.privacy

import com.livetranslate.translate.Lang
import com.livetranslate.translate.Translator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlin.random.Random

/**
 * Wraps a provider so that only masked text leaves the device. [build] receives the masked style and
 * short-term context, because both are sent to the provider inside its prompt. Callers above this
 * layer (pipeline cache, replace checks) only ever see the original text and the restored translation.
 */
class MaskingTranslator(
    private val rules: PrivacyRules,
    private val style: String,
    private val context: List<String>,
    private val caret: Int = -1,
    private val random: Random? = null,
    private val build: (style: String, context: List<String>) -> Translator,
) : Translator {

    override fun translate(text: String, from: Lang, to: Lang): Flow<String> = flow {
        val extras = listOf(style) + context
        val mask = if (random == null) PrivacyMask.create(rules, text, caret, extras)
        else PrivacyMask.create(rules, text, caret, extras, random)
        val inner = build(mask.extras.first(), mask.extras.drop(1))
        if (mask.isEmpty) {
            emitAll(inner.translate(text, from, to))
            return@flow
        }
        var raw = ""
        var shown = ""
        inner.translate(mask.text, from, to).collect { partial ->
            raw = partial
            val restored = mask.restore(partial, final = false)
            if (restored != shown) {
                shown = restored
                emit(restored)
            }
        }
        mask.verify(raw)
        val final = mask.restore(raw, final = true)
        if (final != shown) emit(final)
    }
}
