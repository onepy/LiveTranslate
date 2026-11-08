package com.livetranslate.privacy

import com.livetranslate.translate.TranslateException
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * One request's masking. Private spans of the paragraph and of the extra prompt texts (short-term
 * context, style) are replaced by random codes such as "ZQX7"; [restore] maps codes back on the device.
 * Codes are drawn per request, so a provider cannot link the same person across requests.
 */
class PrivacyMask private constructor(
    val text: String,
    val extras: List<String>,
    /** code -> what the restored translation shows */
    private val values: Map<String, String>,
    /** code -> occurrences in the masked paragraph (codes only used in extras are absent) */
    private val paragraphCounts: Map<String, Int>,
    /** code-shaped tokens the user typed themselves; not mistaken for damaged codes */
    private val natural: Set<String>,
) {
    val isEmpty get() = values.isEmpty()

    /** Replaces complete codes first, then (while streaming) hides a trailing code fragment. */
    fun restore(raw: String, final: Boolean): String {
        if (values.isEmpty()) return raw
        val f = PrivacyRules.fold(raw)
        val out = StringBuilder(raw.length)
        var i = 0
        for (m in CODE.findAll(f)) {
            val value = values[m.value.uppercase()] ?: continue
            var s = m.range.first
            var e = m.range.last + 1
            // Models often put spaces around a code; drop them next to CJK values unless Latin text follows.
            if (isCjk(value.first()) && s - 1 >= i && raw[s - 1] == ' ' && (s < 2 || !PrivacyRules.asciiAlnum(raw[s - 2]))) s--
            if (isCjk(value.last()) && e < raw.length && raw[e] == ' ' && (e + 1 >= raw.length || !PrivacyRules.asciiAlnum(raw[e + 1]))) e++
            out.append(raw, i, s).append(value)
            i = e
        }
        out.append(raw, i, raw.length)
        if (final) return out.toString()
        val shown = out.toString()
        val tail = PARTIAL.find(PrivacyRules.fold(shown)) ?: return shown
        val frag = tail.value.uppercase()
        return if (values.keys.any { it.startsWith(frag) }) shown.substring(0, tail.range.first) else shown
    }

    /** Throws unless every paragraph code came back (at most as often as sent) and no unknown code appeared. */
    fun verify(raw: String) {
        if (values.isEmpty()) return
        val seen = CODE.findAll(PrivacyRules.fold(raw)).map { it.value.uppercase() }.groupingBy { it }.eachCount()
        val missingOrExtra = values.keys.any { code ->
            val n = seen[code] ?: 0
            val sent = paragraphCounts[code] ?: 0
            if (sent > 0) n !in 1..sent else n > 1
        }
        val unknown = seen.keys.any { it !in values && it !in natural && ourAlphabet(it) }
        if (missingOrExtra || unknown) throw TranslateException("脱敏校验未通过：译文里的隐私占位缺失或异常，已禁止替换，可点此重试")
    }

    companion object {
        private const val LETTERS = "BCDFGHJKLMNPQRSTVWXZ"
        private const val DIGITS = "23456789"
        /** Three letters + one digit, matched on folded (lower-case, half-width) text. */
        private val CODE = Regex("(?<![a-z])[a-z]{3}[0-9](?![0-9])")
        private val PARTIAL = Regex("(?<![a-z])[a-z]{1,3}$")
        private val secureRandom: Random = SecureRandom().asKotlinRandom()

        fun create(rules: PrivacyRules, text: String, caret: Int, extras: List<String>, random: Random = secureRandom): PrivacyMask {
            val forbidden = PrivacyRules.fold(text + "\n" + extras.joinToString("\n"))
            val codes = LinkedHashMap<String, String>() // folded original -> code
            val values = HashMap<String, String>()
            fun mask(s: String, caretAt: Int): String {
                val spans = rules.spans(s, caretAt)
                if (spans.isEmpty()) return s
                val sb = StringBuilder()
                var i = 0
                for (r in spans) {
                    val original = s.substring(r.first, r.last + 1)
                    val code = codes.getOrPut(PrivacyRules.fold(original)) {
                        newCode(random, codes.values, forbidden).also { values[it] = rules.displayFor(original) }
                    }
                    sb.append(s, i, r.first).append(code)
                    i = r.last + 1
                }
                return sb.append(s, i, s.length).toString()
            }
            val maskedText = mask(text, caret)
            val maskedExtras = extras.map { mask(it, -1) }
            val counts = values.keys.associateWith { code -> Regex(code).findAll(maskedText).count() }.filterValues { it > 0 }
            val natural = CODE.findAll(PrivacyRules.fold(maskedText + "\n" + maskedExtras.joinToString("\n")))
                .map { it.value.uppercase() }.filter { it !in values }.toSet()
            return PrivacyMask(maskedText, maskedExtras, values, counts, natural)
        }

        private fun newCode(random: Random, taken: Collection<String>, forbidden: String): String {
            repeat(10_000) {
                val c = String(CharArray(4) { i -> if (i < 3) LETTERS[random.nextInt(LETTERS.length)] else DIGITS[random.nextInt(DIGITS.length)] })
                if (taken.all { t -> t.indices.count { t[it] != c[it] } >= 3 } && !forbidden.contains(c.lowercase())) return c
            }
            throw TranslateException("隐私内容太多，无法生成占位")
        }

        private fun ourAlphabet(code: String) = code.take(3).all { it in LETTERS } && code.last() in DIGITS

        private fun isCjk(c: Char) = when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }
}
