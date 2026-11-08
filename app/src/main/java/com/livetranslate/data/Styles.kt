package com.livetranslate.data

import com.livetranslate.translate.Providers
import org.json.JSONArray
import org.json.JSONObject

/**
 * A translation style is an instruction appended to the LLM prompt. [desc] is what the user sees;
 * [instruction] is what the model gets (custom styles use the user's own words for both).
 */
data class TranslationStyle(val id: String, val name: String, val desc: String, val instruction: String) {
    val isBuiltin get() = !id.startsWith(CUSTOM_PREFIX)

    companion object {
        const val CUSTOM_PREFIX = "custom-"
    }
}

object Styles {
    const val DEFAULT_ID = "casual"

    val builtins = listOf(
        TranslationStyle(
            id = "casual",
            name = "日常口语",
            desc = "像外国人平时聊天那样说话，自然、不书面",
            instruction = "Make it sound exactly like a native speaker casually talking in everyday life: natural, " +
                "idiomatic, relaxed, using contractions and common spoken expressions. Never use stiff, textbook, " +
                "exam-style or overly formal wording.",
        ),
        TranslationStyle(
            id = "texting",
            name = "网络缩写",
            desc = "像发消息一样：u、r、lol、idk、gonna 这类",
            instruction = "Write it like a native speaker texting a friend: use common internet abbreviations and " +
                "slang where natural (u, r, ur, lol, idk, tbh, btw, gonna, wanna, gotta), relaxed punctuation, " +
                "lowercase is fine. Keep it short.",
        ),
        TranslationStyle(
            id = "literal",
            name = "忠实原文",
            desc = "准确贴近原意，不额外发挥",
            instruction = "Translate faithfully and accurately, staying close to the original meaning and structure. " +
                "Do not add, drop or embellish anything.",
        ),
        TranslationStyle(
            id = "formal",
            name = "正式礼貌",
            desc = "适合工作消息、邮件、对陌生人",
            instruction = "Use polite, professional wording suitable for work messages, emails or talking to strangers.",
        ),
    )

    fun all(s: AppSettings): List<TranslationStyle> = builtins + s.customStyles

    fun current(s: AppSettings): TranslationStyle = all(s).firstOrNull { it.id == s.styleId } ?: builtins.first()

    /** Only LLM-based providers can follow a style; machine-translation engines ignore it. */
    fun supports(providerId: String) = providerId == Providers.BILIBILI || providerId == Providers.CUSTOM

    fun newCustom(name: String, desc: String) = TranslationStyle(
        id = TranslationStyle.CUSTOM_PREFIX + System.currentTimeMillis(),
        name = name.trim(),
        desc = desc.trim(),
        instruction = desc.trim(),
    )

    fun encode(list: List<TranslationStyle>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("desc", it.desc)) }
    }.toString()

    fun decode(json: String?): List<TranslationStyle> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val desc = o.getString("desc")
            TranslationStyle(o.getString("id"), o.getString("name"), desc, desc)
        }
    }.getOrDefault(emptyList())
}
