package com.livetranslate.data

import android.content.Context
import com.livetranslate.translate.Lang
import com.livetranslate.translate.Providers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DisplayMode { ANCHORED, FLOATING }
enum class AppScope { ALL, WHITELIST }
enum class ApiFormat { CHAT, RESPONSES }
enum class TriggerMode { REALTIME, LONG_PRESS }

data class AppSettings(
    val enabled: Boolean = true,
    val providerId: String = Providers.BILIBILI,
    val biliModel: String = Providers.biliModels.first(),
    val customBaseUrl: String = "https://api.deepseek.com/v1",
    val customApiKey: String = "",
    val customModel: String = "deepseek-chat",
    val customFormat: ApiFormat = ApiFormat.CHAT,
    val sourceLang: Lang = Lang.AUTO,
    val targetLang: Lang = Lang.EN,
    val displayMode: DisplayMode = DisplayMode.ANCHORED,
    val debounceMs: Long = 400,
    val fontSizeSp: Int = 15,
    val panelOpacity: Float = 0.95f,
    val ttsEngine: String = "",
    val ttsRate: Float = 1.0f,
    val appScope: AppScope = AppScope.ALL,
    val whitelist: Set<String> = setOf("com.tencent.mm", "com.tencent.mobileqq"),
    val floatX: Int = 40,
    val floatY: Int = 300,
    val keepAliveNotification: Boolean = true,
    val triggerMode: TriggerMode = TriggerMode.REALTIME,
    val styleId: String = Styles.DEFAULT_ID,
    val shortContext: Boolean = false,
    val customStyles: List<TranslationStyle> = emptyList(),
) {
    fun allows(pkg: String) = appScope == AppScope.ALL || pkg in whitelist

    /** Swaps translation direction. From "auto", the new target is Chinese (or English if already Chinese). */
    fun swappedLanguages(): AppSettings {
        val newTarget = when {
            sourceLang != Lang.AUTO -> sourceLang
            targetLang == Lang.ZH || targetLang == Lang.ZH_TW -> Lang.EN
            else -> Lang.ZH
        }
        return copy(sourceLang = targetLang, targetLang = newTarget)
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val flow: StateFlow<AppSettings> = state
    val value: AppSettings get() = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val old = state.value
        val new = transform(old)
        if (new == old) return
        state.value = new
        save(new, old)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            providerId = prefs.getString("provider", d.providerId)!!,
            biliModel = prefs.getString("bili_model", d.biliModel)!!,
            customBaseUrl = prefs.getString("custom_base", d.customBaseUrl)!!,
            customApiKey = SecretBox.decrypt(prefs.getString("custom_key_enc", "")!!),
            customModel = prefs.getString("custom_model", d.customModel)!!,
            customFormat = runCatching { ApiFormat.valueOf(prefs.getString("custom_format", "")!!) }.getOrDefault(d.customFormat),
            sourceLang = Lang.of(prefs.getString("source_lang", null), d.sourceLang),
            targetLang = Lang.of(prefs.getString("target_lang", null), d.targetLang),
            displayMode = runCatching { DisplayMode.valueOf(prefs.getString("display", "")!!) }.getOrDefault(d.displayMode),
            debounceMs = prefs.getLong("debounce", d.debounceMs),
            fontSizeSp = prefs.getInt("font", d.fontSizeSp),
            panelOpacity = prefs.getFloat("opacity", d.panelOpacity),
            ttsEngine = prefs.getString("tts_engine", d.ttsEngine)!!,
            ttsRate = prefs.getFloat("tts_rate", d.ttsRate),
            appScope = runCatching { AppScope.valueOf(prefs.getString("scope", "")!!) }.getOrDefault(d.appScope),
            whitelist = prefs.getStringSet("whitelist", d.whitelist)!!.toSet(),
            floatX = prefs.getInt("float_x", d.floatX),
            floatY = prefs.getInt("float_y", d.floatY),
            keepAliveNotification = prefs.getBoolean("keepalive_notification", d.keepAliveNotification),
            triggerMode = runCatching { TriggerMode.valueOf(prefs.getString("trigger", "")!!) }.getOrDefault(d.triggerMode),
            shortContext = prefs.getBoolean("short_context", false),
            styleId = prefs.getString("style", d.styleId)!!,
            customStyles = Styles.decode(prefs.getString("custom_styles", null)),
        )
    }

    private fun save(s: AppSettings, old: AppSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putString("provider", s.providerId)
            .putString("bili_model", s.biliModel)
            .putString("custom_base", s.customBaseUrl)
            .putString("custom_model", s.customModel)
            .putString("custom_format", s.customFormat.name)
            .putString("source_lang", s.sourceLang.code)
            .putString("target_lang", s.targetLang.code)
            .putString("display", s.displayMode.name)
            .putLong("debounce", s.debounceMs)
            .putInt("font", s.fontSizeSp)
            .putFloat("opacity", s.panelOpacity)
            .putString("tts_engine", s.ttsEngine)
            .putFloat("tts_rate", s.ttsRate)
            .putString("scope", s.appScope.name)
            .putStringSet("whitelist", s.whitelist)
            .putInt("float_x", s.floatX)
            .putInt("float_y", s.floatY)
            .putBoolean("keepalive_notification", s.keepAliveNotification)
            .putString("trigger", s.triggerMode.name)
            .putBoolean("short_context", s.shortContext)
            .putString("style", s.styleId)
            .putString("custom_styles", Styles.encode(s.customStyles))
            .apply {
                if (s.customApiKey != old.customApiKey) putString("custom_key_enc", SecretBox.encrypt(s.customApiKey))
            }
            .apply()
    }
}
