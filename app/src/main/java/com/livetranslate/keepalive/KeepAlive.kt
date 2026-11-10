package com.livetranslate.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.livetranslate.App
import com.livetranslate.R
import com.livetranslate.a11y.A11yEnabler
import com.livetranslate.a11y.InputWatcherService
import com.livetranslate.a11y.ServiceLog
import com.livetranslate.data.AppSettings
import com.livetranslate.ui.MainActivity
import kotlin.concurrent.thread

/**
 * Keep-alive layers, from strongest to weakest:
 * 1. WRITE_SECURE_SETTINGS (granted once via Shizuku/adb) lets the app re-enable its own accessibility
 *    service whenever the process comes back (App start, boot, watchdog job, QS tile).
 * 2. A foreground notification raised by the accessibility service lifts process priority.
 * 3. Battery-optimization whitelist + OEM autostart permission + Shizuku appops/deviceidle tweaks.
 * 4. A periodic JobScheduler watchdog that restarts the process and revives a dead service.
 */
object KeepAlive {
    const val NOTIFICATION_ID = 1001
    private const val CHANNEL = "keepalive"

    fun adbGrantCommand(pkg: String) = "adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"

    fun shizukuBoostCommand(pkg: String): String = buildList {
        add("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add("pm grant $pkg android.permission.POST_NOTIFICATIONS")
            add("cmd appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        }
        add("dumpsys deviceidle whitelist +$pkg")
        add("cmd appops set $pkg RUN_IN_BACKGROUND allow")
        add("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        add("am set-standby-bucket $pkg active")
    }.joinToString(" ; ", prefix = "failed=0 ; ", postfix = " ; exit ${'$'}failed") { "$it 2>&1 || failed=1" }

    data class Status(val autoRestore: Boolean, val notification: Boolean, val battery: Boolean) {
        val ready get() = listOf(autoRestore, notification, battery).count { it }
        val total get() = 3
    }

    fun status(context: Context, s: AppSettings) = Status(
        autoRestore = A11yEnabler.canWriteSecureSettings(context),
        notification = s.keepAliveNotification && hasNotificationPermission(context) &&
            context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
        battery = isIgnoringBatteryOptimizations(context),
    )

    fun hasNotificationPermission(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun notification(context: Context, enabled: Boolean): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "后台运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "让边写边译保持在后台运行"
                setShowBadge(false)
            }
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val toggle = PendingIntent.getBroadcast(context, 1, Intent(context, ToggleReceiver::class.java), flags)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (enabled) "边写边译 运行中" else "边写边译 已暂停")
            .setContentText(if (enabled) "在任意输入框打字即可看到翻译" else "点「恢复」继续翻译")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_tile),
                    if (enabled) "暂停" else "恢复",
                    toggle,
                ).build()
            )
            .build()
    }

    fun toggleEnabled() = App.settings.update { it.copy(enabled = !it.enabled) }

    // ---- "service stopped" alert ----

    private const val ALERT_CHANNEL = "service_alert"
    private const val ALERT_ID = 1002

    /**
     * Tells the user the service stopped while it should be running. Shown once per outage and never before
     * the first successful setup. Tapping opens the app, which recovers it automatically when it can.
     * No-op without notification permission.
     */
    fun notifyServiceLost(context: Context) {
        if (!hasNotificationPermission(context) || !ServiceLog.everConnected || ServiceLog.lossNotified) return
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "服务停止提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "无障碍服务断开连接时提醒"
                }
            )
            val open = PendingIntent.getActivity(
                context, 2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            nm.notify(
                ALERT_ID,
                Notification.Builder(context, ALERT_CHANNEL)
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle("边写边译已停止工作")
                    .setContentText("无障碍服务未连接，点此检查并恢复")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
            ServiceLog.lossNotified = true
        }
    }

    fun cancelServiceLost(context: Context) {
        ServiceLog.lossNotified = false
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID) }
    }

    // ---- OEM autostart / background-run pages ----

    private val autostartPages = listOf(
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
        "com.meizu.safe" to "com.meizu.safe.permission.SmartBGActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
    )

    /** Opens the vendor autostart manager if one exists, otherwise this app's details page. */
    fun openAutostart(context: Context): Boolean {
        for ((pkg, cls) in autostartPages) {
            val intent = Intent().setComponent(ComponentName(pkg, cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }
}

/** Periodic watchdog: wakes the process every ~15 min and revives the accessibility service if it died. */
class WatchdogJob : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = thread(name = "Service-watchdog") {
            try {
                Thread.sleep(3000)
                val ctx = applicationContext
                if (A11yEnabler.ensureEnabled(ctx, restartIfDead = true, source = "后台巡检")) Thread.sleep(3000)
                if (!Thread.currentThread().isInterrupted) {
                    if (App.settings.value.enabled && !InputWatcherService.isRunning.value) KeepAlive.notifyServiceLost(ctx)
                    jobFinished(params, false)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        return true
    }

    override fun onDestroy() {
        worker?.interrupt()
        super.onDestroy()
    }

    companion object {
        private const val JOB_ID = 4201

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, WatchdogJob::class.java))
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build()
            )
        }
    }
}

/** Notification "暂停/恢复" action. */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = KeepAlive.toggleEnabled()
}

/** Quick-settings tile: one-tap pause/resume; opening the shade also wakes the app. */
class ToggleTile : TileService() {
    override fun onStartListening() {
        A11yEnabler.ensureEnabled(this, source = "快捷开关")
        refresh()
    }

    override fun onClick() {
        KeepAlive.toggleEnabled()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = App.settings.value.enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "边写边译"
        tile.subtitle = when {
            !on -> "已暂停"
            !InputWatcherService.isRunning.value -> "服务未连接"
            InputWatcherService.faultMessage.value != null -> "服务异常"
            else -> "服务已连接"
        }
        tile.updateTile()
    }
}
