package com.livetranslate.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.livetranslate.BuildConfig
import com.livetranslate.keepalive.KeepAlive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.atomic.AtomicBoolean

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

    /**
     * Executes [command] as the shell user through a Shizuku UserService. Throws after [timeoutMs]: when the
     * user service fails to start (Shizuku gives up after 30 s) no callback ever comes.
     */
    suspend fun exec(command: String, timeoutMs: Long = 35_000): String =
        withTimeoutOrNull(timeoutMs) { execUntimed(command) }
            ?: throw IllegalStateException("Shizuku 没有响应，请先打开 Shizuku App 确认服务正在运行，再回来重试")

    private suspend fun execUntimed(command: String): String = suspendCancellableCoroutine { cont ->
        val args = args()
        val finished = AtomicBoolean(false)
        val conn = object : ServiceConnection {
            private fun fail(error: Exception) {
                if (finished.compareAndSet(false, true)) {
                    cont.resumeWithException(error)
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                }
            }

            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (finished.get() || !cont.isActive) {
                    runCatching { Shizuku.unbindUserService(args, this, true) }
                    return
                }
                if (binder == null) {
                    fail(IllegalStateException("Shizuku 返回了空连接"))
                    return
                }
                val conn = this
                thread(name = "Shizuku-command", isDaemon = true) {
                    try {
                        val svc = IShellService.Stub.asInterface(binder)
                        val result = svc.exec(command)
                        if (finished.compareAndSet(false, true)) cont.resume(result)
                    } catch (e: Exception) {
                        if (finished.compareAndSet(false, true)) cont.resumeWithException(e)
                    } finally {
                        runCatching { Shizuku.unbindUserService(args, conn, true) }
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) = fail(IllegalStateException("Shizuku 连接已断开"))

            override fun onBindingDied(name: ComponentName?) = fail(IllegalStateException("Shizuku 连接已失效"))

            override fun onNullBinding(name: ComponentName?) = fail(IllegalStateException("Shizuku 无法建立连接"))
        }
        cont.invokeOnCancellation {
            finished.set(true)
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
        try {
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            if (finished.compareAndSet(false, true)) cont.resumeWithException(e)
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
    }

    /**
     * One-shot "do everything" via Shizuku: WRITE_SECURE_SETTINGS (self-revive), notification permission,
     * Doze whitelist, background appops, restricted-settings unlock, standby bucket.
     */
    suspend fun boost(packageName: String): String = exec(KeepAlive.shizukuBoostCommand(packageName))
}
