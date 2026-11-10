package com.livetranslate.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
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
import com.livetranslate.a11y.InputWatcherService
import com.livetranslate.a11y.ServiceHealth
import com.livetranslate.a11y.ServiceLog
import com.livetranslate.keepalive.KeepAlive
import com.livetranslate.overlay.PanelTheme
import com.livetranslate.shizuku.ShizukuHelper
import com.livetranslate.translate.Providers
import com.livetranslate.translate.TranslationPipeline
import com.livetranslate.translate.toPipelineConfig
import com.livetranslate.tts.TtsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

interface SettingsHost {
    fun oneTapSetup()
    fun runShizukuBoost()
    fun restartService()
    fun openAccessibilitySettings()
    fun openAppDetails()
    fun requestIgnoreBatteryOptimizations()
    fun openAutostart()
    fun requestNotificationPermission()
    fun openNotificationSettings()
    fun openDoubaoLogin()
    fun copyText(text: String)
    fun speak(text: String)
    fun toast(msg: String)
}

class MainActivity : ComponentActivity(), SettingsHost {
    /** Bumped on resume / after actions so status rows re-read system state. */
    private val refreshTick = mutableIntStateOf(0)
    private val health = mutableStateOf(ServiceHealth.STARTING)
    private var checking = false
    private var repairing = false
    private var healthJob: Job? = null
    private var pendingShizuku = false
    private var pendingForceRestart = false
    private var boostJob: Job? = null
    private lateinit var pipeline: TranslationPipeline
    private lateinit var tts: TtsManager

    private val accessibilityObserver = object : ContentObserver(Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshTick.intValue++
            refreshHealth()
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshTick.intValue++ }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (code != ShizukuHelper.REQUEST_CODE || !pendingShizuku) return@OnRequestPermissionResultListener
        pendingShizuku = false
        if (result == PackageManager.PERMISSION_GRANTED) boost(pendingForceRestart) else toast("没有授予 Shizuku 权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        runCatching { Shizuku.addRequestPermissionResultListener(shizukuListener) }
        listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED).forEach { key ->
            contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, accessibilityObserver)
        }

        val settings = App.settings
        val config = settings.flow.map { it.toPipelineConfig() }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, settings.value.toPipelineConfig())
        pipeline = TranslationPipeline(lifecycleScope, config, translatorFor = { Providers.create(settings.value.providerId, settings.value) })
        tts = TtsManager(this, lifecycleScope, settings)
        lifecycleScope.launch {
            combine(InputWatcherService.isRunning, InputWatcherService.faultMessage, settings.flow.map { it.enabled }) { _, _, _ -> }
                .collect { refreshHealth() }
        }

        setContent {
            PanelTheme {
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = settingsOpen) { settingsOpen = false }
                if (settingsOpen) {
                    SettingsPage(this, tts, refreshTick.intValue) { settingsOpen = false }
                } else {
                    HomeScreen(this, pipeline, refreshTick.intValue, health.value) { settingsOpen = true }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        A11yEnabler.ensureEnabled(this, source = "打开App")
        refreshTick.intValue++
        checking = true
        healthJob?.cancel()
        healthJob = lifecycleScope.launch {
            refreshHealth()
            delay(2500)
            checking = false
            refreshHealth()
            val h = health.value
            val now = SystemClock.elapsedRealtime()
            if ((h == ServiceHealth.NOT_RUNNING || h == ServiceHealth.UNRESPONSIVE) &&
                A11yEnabler.canWriteSecureSettings(this@MainActivity) && now - lastAutoRestartAt > 60_000 &&
                boostJob?.isActive != true
            ) {
                lastAutoRestartAt = now
                ServiceLog.add("服务${if (h == ServiceHealth.NOT_RUNNING) "没有运行" else "没有响应"}，打开App时自动重启")
                restartService()
            }
        }
    }

    override fun onPause() {
        healthJob?.cancel()
        checking = false
        super.onPause()
    }

    private fun evaluate(): ServiceHealth = when {
        !App.settings.value.enabled -> ServiceHealth.PAUSED
        repairing -> ServiceHealth.STARTING
        !A11yEnabler.isEnabled(this) -> ServiceHealth.OFF
        !InputWatcherService.isConnected() -> if (checking) ServiceHealth.STARTING else ServiceHealth.NOT_RUNNING
        InputWatcherService.faultMessage.value != null -> ServiceHealth.UNRESPONSIVE
        else -> ServiceHealth.OK
    }

    private fun refreshHealth() {
        health.value = evaluate()
    }

    private data class ServiceRecovery(val operation: A11yEnabler.Recovery, val connected: Boolean)

    private suspend fun recoverService(force: Boolean): ServiceRecovery {
        repairing = true
        refreshHealth()
        val previousConnection = InputWatcherService.connectionId
        try {
            val result = withContext(Dispatchers.IO) { A11yEnabler.recover(this@MainActivity, force) }
            val connected = if (result != A11yEnabler.Recovery.NEEDS_USER) {
                withTimeoutOrNull(5000) {
                    InputWatcherService.isRunning.first { connected ->
                        connected && InputWatcherService.isConnected() &&
                            (result != A11yEnabler.Recovery.RESTARTED || InputWatcherService.connectionId > previousConnection)
                    }
                } == true
            } else false
            if (result != A11yEnabler.Recovery.NEEDS_USER && !connected) ServiceLog.add("等待服务重新连接超时")
            return ServiceRecovery(result, connected)
        } finally {
            repairing = false
            checking = false
            refreshTick.intValue++
            refreshHealth()
        }
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(accessibilityObserver)
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuListener) }
        tts.shutdown()
        super.onDestroy()
    }

    // ---- SettingsHost ----

    override fun oneTapSetup() {
        if (ShizukuHelper.isRunning()) runShizukuBoost()
        else openAccessibilitySettings()
    }

    override fun runShizukuBoost() = boost(forceRestart = false)

    private fun boost(forceRestart: Boolean) {
        if (boostJob?.isActive == true || pendingShizuku) return
        if (!ShizukuHelper.isRunning()) {
            toast("Shizuku 没有运行，请先打开 Shizuku 启动服务")
            return
        }
        if (!ShizukuHelper.hasPermission()) {
            pendingShizuku = true
            pendingForceRestart = forceRestart
            runCatching { ShizukuHelper.requestPermission() }.onFailure {
                pendingShizuku = false
                toast("Shizuku 授权请求失败，请确认服务已启动后重试")
            }
            return
        }
        toast("正在通过 Shizuku 处理，请稍等…")
        boostJob = lifecycleScope.launch {
            val broken = forceRestart || !InputWatcherService.isConnected() || health.value == ServiceHealth.UNRESPONSIVE
            var result = recoverService(force = broken)
            var boostError: String? = null
            try {
                val output = ShizukuHelper.boost(packageName)
                if (!output.startsWith("exit=0\n") && output != "exit=0") boostError = "部分后台设置未生效，请在设置页检查"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServiceLog.add("Shizuku 执行失败：${e.javaClass.simpleName}")
                boostError = "Shizuku 未完成，请确认服务已启动后重试"
            }
            if (result.operation == A11yEnabler.Recovery.NEEDS_USER) {
                result = recoverService(force = broken)
            }
            ServiceLog.add("Shizuku 加固：${if (boostError == null) "完成" else "未完成"}；服务操作：${result.operation.name}")
            refreshTick.intValue++
            refreshHealth()
            toast(when {
                boostError != null -> boostError
                !result.connected || !InputWatcherService.isConnected() -> "后台设置已处理，尚未确认服务连接，请到系统无障碍设置检查"
                InputWatcherService.faultMessage.value != null -> "服务仍有异常，请查看诊断信息"
                else -> "服务已连接，后台加固完成"
            })
        }
    }

    override fun restartService() {
        if (boostJob?.isActive == true || pendingShizuku) return
        when {
            A11yEnabler.canWriteSecureSettings(this) -> boostJob = lifecycleScope.launch {
                ServiceLog.add("手动重启服务")
                val result = recoverService(force = true)
                toast(when {
                    result.operation == A11yEnabler.Recovery.NEEDS_USER -> "重启失败，请到系统无障碍设置重新开启"
                    !result.connected || !InputWatcherService.isConnected() -> "已请求重启，尚未确认重新连接，请查看诊断信息"
                    InputWatcherService.faultMessage.value != null -> "服务仍有异常，请查看诊断信息"
                    else -> "服务已重新连接"
                })
            }
            ShizukuHelper.isRunning() -> boost(forceRestart = true)
            InputWatcherService.softReset() -> {
                refreshHealth()
                toast("已重建面板和输入监听，请返回输入框继续打字")
            }
            else -> {
                openAccessibilitySettings()
                toast("请在列表里把「边写边译」关掉再打开")
            }
        }
    }

    override fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
        } else {
            openNotificationSettings()
        }
    }

    override fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    override fun openDoubaoLogin() = startActivity(Intent(this, DoubaoLoginActivity::class.java))

    override fun copyText(text: String) {
        if (text.isBlank()) return
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", text))
        toast("已复制")
    }

    override fun speak(text: String) = tts.speak(text, App.settings.value.targetLang.locale)

    override fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private companion object {
        /** Process-wide, so reopening the screen does not restart the service in a loop. */
        var lastAutoRestartAt = -60_000L
    }
}
