package com.livetranslate.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.livetranslate.BuildConfig
import com.livetranslate.keepalive.KeepAlive
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ShizukuHelper {
    const val REQUEST_CODE = 7301

    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean =
        isRunning() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission() = Shizuku.requestPermission(REQUEST_CODE)

    private fun args() = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    /** Executes [command] as the shell user through a Shizuku UserService. */
    suspend fun exec(command: String): String = suspendCancellableCoroutine { cont ->
        val args = args()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val conn = this
                thread {
                    try {
                        val svc = IShellService.Stub.asInterface(binder)
                        val result = svc.exec(command)
                        if (cont.isActive) cont.resume(result)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } finally {
                        runCatching { Shizuku.unbindUserService(args, conn, true) }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        Shizuku.bindUserService(args, conn)
    }

    /**
     * One-shot "do everything" via Shizuku: WRITE_SECURE_SETTINGS (self-revive), notification permission,
     * Doze whitelist, background appops, restricted-settings unlock, standby bucket.
     */
    suspend fun boost(packageName: String): String = exec(KeepAlive.shizukuBoostCommand(packageName))
}
