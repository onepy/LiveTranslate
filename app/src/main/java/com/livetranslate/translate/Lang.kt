package com.livetranslate.translate

import java.util.Locale

enum class Lang(
    val code: String,
    val label: String,
    val short: String,
    val englishName: String,
    val microsoft: String,
    val google: String,
    val doubao: String,
) {
    AUTO("auto", "自动检测", "自动", "", "", "auto", "auto"),
    ZH("zh", "简体中文", "中", "Simplified Chinese", "zh-Hans", "zh-CN", "zh"),
    ZH_TW("zh-TW", "繁體中文", "繁", "Traditional Chinese", "zh-Hant", "zh-TW", "zh-Hant"),
    EN("en", "英语", "英", "English", "en", "en", "en"),
    JA("ja", "日语", "日", "Japanese", "ja", "ja", "ja"),
    KO("ko", "韩语", "韩", "Korean", "ko", "ko", "ko"),
    FR("fr", "法语", "法", "French", "fr", "fr", "fr"),
    DE("de", "德语", "德", "German", "de", "de", "de"),
    ES("es", "西班牙语", "西", "Spanish", "es", "es", "es"),
    RU("ru", "俄语", "俄", "Russian", "ru", "ru", "ru");

    val locale: Locale
        get() = when (this) {
            EN -> Locale.US
            ZH -> Locale.SIMPLIFIED_CHINESE
            ZH_TW -> Locale.TRADITIONAL_CHINESE
            else -> Locale.forLanguageTag(code)
        }

    companion object {
        val targets = entries.filter { it != AUTO }
        fun of(code: String?, fallback: Lang): Lang = entries.firstOrNull { it.code == code } ?: fallback
    }
}
