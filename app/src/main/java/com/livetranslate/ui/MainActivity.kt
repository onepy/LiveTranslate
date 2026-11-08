package com.livetranslate.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.livetranslate.App
import com.livetranslate.a11y.A11yEnabler
import com.livetranslate.keepalive.KeepAlive
import com.livetranslate.overlay.PanelTheme
import com.livetranslate.shizuku.ShizukuHelper
import com.livetranslate.translate.Providers
import com.livetranslate.translate.TranslationPipeline
import com.livetranslate.translate.toPipelineConfig
import com.livetranslate.tts.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

interface SettingsHost {
    fun oneTapSetup()
    fun runShizukuBoost()
    fun openAppDetails()
    fun requestIgnoreBatteryOptimizations()
    fun openAutostart()
    fun requestNotificationPermission()
    fun openDoubaoLogin()
    fun copyText(text: String)
    fun speak(text: String)
    fun toast(msg: String)
}

class MainActivity : ComponentActivity(), SettingsHost {
    /** Bumped on resume / after actions so status rows re-read system state. */
    private val refreshTick = mutableIntStateOf(0)
    private var pendingShizuku = false
    private lateinit var pipeline: TranslationPipeline
    private lateinit var tts: TtsManager

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick.intValue++ }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != ShizukuHelper.REQUEST_CODE || !pendingShizuku) return@OnRequestPermissionResultListener
        pendingShizuku = false
        if (result == PackageManager.PERMISSION_GRANTED) runShizukuBoost() else toast("没有授予 Shizuku 权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }

        val settings = App.settings
        val config = settings.flow.map { it.toPipelineConfig() }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, settings.value.toPipelineConfig())
        pipeline = TranslationPipeline(lifecycleScope, config, translatorFor = { Providers.create(settings.value.providerId, settings.value) })
        tts = TtsManager(this, lifecycleScope, settings)

        setContent {
            PanelTheme {
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = settingsOpen) { settingsOpen = false }
                if (settingsOpen) {
                    SettingsPage(this, tts, refreshTick.intValue) { settingsOpen = false }
                } else {
                    HomeScreen(this, pipeline, refreshTick.intValue) { settingsOpen = true }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        A11yEnabler.ensureEnabled(this)
        refreshTick.intValue++
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        tts.shutdown()
        super.onDestroy()
    }

    // ---- SettingsHost ----

    override fun oneTapSetup() {
        if (ShizukuHelper.isRunning()) runShizukuBoost()
        else startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun runShizukuBoost() {
        if (!ShizukuHelper.isRunning()) {
            toast("Shizuku 没有运行，请先打开 Shizuku 启动服务")
            return
        }
        if (!ShizukuHelper.hasPermission()) {
            pendingShizuku = true
            ShizukuHelper.requestPermission()
            return
        }
        lifecycleScope.launch {
            val out = try {
                ShizukuHelper.boost(packageName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message.orEmpty()
            }
            val ok = A11yEnabler.enable(this@MainActivity)
            toast(if (ok) "搞定：已开启并加固后台" else "没有成功：${out.take(80)}")
            refreshTick.intValue++
        }
    }

    override fun openAppDetails() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    override fun requestIgnoreBatteryOptimizations() {
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    override fun openAutostart() {
        if (!KeepAlive.openAutostart(this)) toast("没找到自启动设置，请在应用信息里找「自启动」或「后台运行」")
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun openDoubaoLogin() = startActivity(Intent(this, DoubaoLoginActivity::class.java))

    override fun copyText(text: String) {
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
        toast("已复制")
    }

    override fun speak(text: String) = tts.speak(text, App.settings.value.targetLang.locale)

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
