package com.livetranslate.privacy

import org.json.JSONArray
import org.json.JSONObject

/** One list entry. [alias] (optional) is what the restored translation shows instead of [text]. */
data class PrivacyEntry(val text: String, val alias: String = "")

/**
 * Local masking rules. Matching runs on a 1:1 folded copy of the text (full-width to half-width,
 * lower case), so indices found in the folded copy are valid in the original.
 */
data class PrivacyRules(
    val enabled: Boolean = true,
    val entries: List<PrivacyEntry> = emptyList(),
    val emails: Boolean = true,
    val idCards: Boolean = true,
    val longDigits: Boolean = true,
) {
    val active get() = enabled && (entries.isNotEmpty() || emails || idCards || longDigits)

    /** Part of the translation cache key: changes whenever masking would behave differently. */
    fun fingerprint(): Int = if (active) hashCode() else 0

    /** Folded match keys, longest first: each entry plus its alias variants (IMEs may write pinyin inline). */
    private val keys: List<Pair<String, Regex>> by lazy {
        entries.flatMap { e ->
            val a = e.alias.trim()
            listOf(e.text.trim(), a, a.replace(" ", ""), a.replace(' ', '\''))
        }.map(::fold).filter { it.trim().length >= MIN_KEY }.distinct().sortedByDescending { it.length }
            .map { k -> k to Regex(k.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }) }
    }

    /**
     * Sorted, merged spans to mask. [caret] >= 0 (realtime typing) also masks an unfinished entry or
     * number typed right before the caret, so its prefix never leaves the device.
     */
    fun spans(text: String, caret: Int = -1): List<IntRange> {
        if (!active || text.isEmpty()) return emptyList()
        val f = fold(text)
        val found = ArrayList<IntRange>()
        for ((k, r) in keys) r.findAll(f).forEach { if (bounded(f, it.range, k)) found += it.range }
        if (emails) EMAIL.findAll(f).forEach { found += it.range }
        if (idCards) ID_CARD.findAll(f).filter { idChecksumOk(it.value) }.forEach { found += it.range }
        if (longDigits) LONG_DIGITS.findAll(f).forEach { found += it.range }
        if (caret in 1..text.length) typingPrefix(f, caret)?.let { found += it until caret }
        return merge(found)
    }

    /** True if [text] contains a list entry (built-in rules excluded). Used by the network guard. */
    fun containsEntry(text: String): Boolean {
        if (!enabled || keys.isEmpty()) return false
        val f = fold(text)
        return keys.any { (k, r) -> r.findAll(f).any { bounded(f, it.range, k) } }
    }

    /** What a masked span shows after restoring: the entry's alias if it has one, else the original. */
    fun displayFor(original: String): String {
        val f = fold(original.trim())
        val entry = entries.firstOrNull { e ->
            val a = e.alias.trim()
            listOf(e.text.trim(), a, a.replace(" ", ""), a.replace(' ', '\'')).any { it.isNotEmpty() && fold(it) == f }
        }
        return entry?.alias?.trim()?.takeIf { it.isNotEmpty() } ?: original
    }

    private fun typingPrefix(f: String, caret: Int): Int? {
        val before = f.substring(0, caret)
        if (longDigits) DIGITS_TAIL.find(before)?.let { return it.range.first }
        var best: Int? = null
        for ((k, _) in keys) {
            for (n in k.length - 1 downTo 1) {
                if (!before.endsWith(k.substring(0, n))) continue
                val latin = asciiAlnum(k[0])
                if (!latin || (n >= 2 && (caret - n == 0 || !asciiAlnum(before[caret - n - 1])))) {
                    best = minOf(best ?: caret, caret - n)
                }
                break
            }
        }
        return best
    }

    private fun bounded(f: String, r: IntRange, key: String): Boolean {
        if (asciiAlnum(key.first()) && r.first > 0 && asciiAlnum(f[r.first - 1])) return false
        if (asciiAlnum(key.last()) && r.last + 1 < f.length && asciiAlnum(f[r.last + 1])) return false
        return true
    }

    companion object {
        const val MIN_KEY = 2
        private val EMAIL = Regex("[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)*\\.[a-z]{2,}")
        private val ID_CARD = Regex(
            "(?<![0-9])[1-9][0-9]{5}(18|19|20)[0-9]{2}(0[1-9]|1[0-2])(0[1-9]|[12][0-9]|3[01])[0-9]{3}[0-9x](?![0-9])"
        )
        /** Six or more digits, optionally grouped by single spaces or hyphens (phones, cards, accounts). */
        private val LONG_DIGITS = Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){5,}(?![0-9])")
        private val DIGITS_TAIL = Regex("[0-9][0-9 -]*$")
        private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)

        fun fold(c: Char): Char = when (c) {
            in '\uFF01'..'\uFF5E' -> c - 0xFEE0
            '\u3000' -> ' '
            else -> c
        }.lowercaseChar()

        fun fold(s: String): String = String(CharArray(s.length) { fold(s[it]) })

        fun asciiAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

        private fun idChecksumOk(id: String): Boolean {
            val sum = (0 until 17).sumOf { (id[it] - '0') * ID_WEIGHTS[it] }
            return "10x98765432"[sum % 11] == id[17]
        }

        /** Sorts and joins overlapping or touching spans, so no entry's tail is left in clear text. */
        fun merge(spans: List<IntRange>): List<IntRange> {
            val out = ArrayList<IntRange>()
            for (r in spans.filter { !it.isEmpty() }.sortedBy { it.first }) {
                val last = out.lastOrNull()
                if (last != null && r.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, r.last)
                else out += r
            }
            return out
        }

        fun encodeEntries(list: List<PrivacyEntry>): String = JSONArray().apply {
            list.forEach { put(JSONObject().put("t", it.text).put("a", it.alias)) }
        }.toString()

        fun decodeEntries(json: String): List<PrivacyEntry> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> PrivacyEntry(o.getString("t"), o.optString("a")) } }
        }.getOrDefault(emptyList())
    }
}
