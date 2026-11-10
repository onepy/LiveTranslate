package com.livetranslate.a11y

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import com.livetranslate.App
import com.livetranslate.keepalive.WatchdogJob

/** Reads / writes the system list of enabled accessibility services. Writing needs WRITE_SECURE_SETTINGS. */
object A11yEnabler {
    private fun component(context: Context) = ComponentName(context, InputWatcherService::class.java)

    private fun enabledList(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() }.orEmpty()

    private fun writeList(context: Context, list: List<String>): Boolean {
        val cr = context.contentResolver
        if (!Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))) return false
        return Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
    }

    private fun registration(context: Context) = ServiceRegistration(
        component(context).flattenToString(),
        { ComponentName.unflattenFromString(it) == component(context) },
        { enabledList(context) },
        { writeList(context, it) },
    )

    fun isEnabled(context: Context): Boolean {
        val me = component(context)
        return Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            enabledList(context).any { ComponentName.unflattenFromString(it) == me }
    }

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Returns true if the service is (now) enabled. */
    @Synchronized
    fun enable(context: Context): Boolean {
        if (isEnabled(context)) return true
        if (!canWriteSecureSettings(context)) return false
        return runCatching {
            registration(context).enable() && isEnabled(context)
        }.getOrDefault(false)
    }

    @Synchronized
    private fun restart(context: Context): Boolean {
        if (!canWriteSecureSettings(context)) return false
        restartingUntil = SystemClock.elapsedRealtime() + 5_000
        return try {
            registration(context).restart { Thread.sleep(500) } && isEnabled(context)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (error: Exception) {
            ServiceLog.add("重启设置写入失败：${error.javaClass.simpleName}")
            false
        }
    }

    @Volatile private var restartingUntil = 0L

    /** True while [restart] is cycling the service, so its onDestroy is not reported as a loss. */
    fun isRestarting() = SystemClock.elapsedRealtime() < restartingUntil

    enum class Recovery { ALREADY_OK, ENABLED, RESTARTED, NEEDS_USER }

    /**
     * Brings the service back without user interaction when WRITE_SECURE_SETTINGS is granted: enables it
     * if missing, or restarts it if it is listed but not running (or [force]). Blocks; call off the main thread.
     */
    @Synchronized
    fun recover(context: Context, force: Boolean): Recovery {
        if (!canWriteSecureSettings(context)) return Recovery.NEEDS_USER
        if (!isEnabled(context)) return if (enable(context)) Recovery.ENABLED else Recovery.NEEDS_USER
        if (!force && InputWatcherService.isRunning.value) return Recovery.ALREADY_OK
        return if (restart(context)) Recovery.RESTARTED else Recovery.NEEDS_USER
    }

    /**
     * Keep-alive: re-enable after the ROM killed/disabled the service, if the user wants it on.
     * Returns true if it changed the system setting; [source] names the caller in the service log.
     */
    @Synchronized
    fun ensureEnabled(context: Context, restartIfDead: Boolean = false, source: String = ""): Boolean {
        if (!App.settings.value.enabled || !canWriteSecureSettings(context)) return false
        if (!isEnabled(context)) {
            val ok = enable(context)
            if (ok && ServiceLog.everConnected) ServiceLog.recovered(source)
            return ok
        }
        if (restartIfDead && !InputWatcherService.isRunning.value && !isRestarting()) {
            ServiceLog.add("服务没有运行，自动重启（$source）")
            return restart(context)
        }
        return false
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                WatchdogJob.schedule(context)
                A11yEnabler.ensureEnabled(context, source = if (intent.action == Intent.ACTION_BOOT_COMPLETED) "开机" else "更新后")
            }
        }
    }
}
