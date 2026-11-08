package com.livetranslate.a11y

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.livetranslate.App
import com.livetranslate.keepalive.WatchdogJob

/** Reads / writes the system list of enabled accessibility services. Writing needs WRITE_SECURE_SETTINGS. */
object A11yEnabler {
    private fun component(context: Context) = ComponentName(context, InputWatcherService::class.java)

    private fun enabledList(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.filter { it.isNotBlank() }.orEmpty()

    private fun writeList(context: Context, list: List<String>) {
        val cr = context.contentResolver
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    fun isEnabled(context: Context): Boolean {
        val me = component(context)
        return enabledList(context).any { ComponentName.unflattenFromString(it) == me }
    }

    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Returns true if the service is (now) enabled. */
    fun enable(context: Context): Boolean {
        if (isEnabled(context)) return true
        if (!canWriteSecureSettings(context)) return false
        return runCatching {
            writeList(context, enabledList(context) + component(context).flattenToString())
            true
        }.getOrDefault(false)
    }

    /**
     * Listed as enabled but not bound (e.g. it crashed or the ROM killed it): the system will not
     * re-bind on its own, so toggle it off and on again.
     */
    private fun restart(context: Context) {
        runCatching {
            val me = component(context)
            val others = enabledList(context).filter { ComponentName.unflattenFromString(it) != me }
            writeList(context, others)
            Thread.sleep(500)
            writeList(context, others + me.flattenToString())
        }
    }

    /** Keep-alive: re-enable after the ROM killed/disabled the service, if the user wants it on. */
    fun ensureEnabled(context: Context, restartIfDead: Boolean = false) {
        if (!App.settings.value.enabled || !canWriteSecureSettings(context)) return
        if (!isEnabled(context)) {
            enable(context)
        } else if (restartIfDead && !InputWatcherService.isRunning.value) {
            restart(context)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                WatchdogJob.schedule(context)
                A11yEnabler.ensureEnabled(context)
            }
        }
    }
}
