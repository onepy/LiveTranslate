package com.livetranslate.shizuku

import kotlin.system.exitProcess
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

/** Runs inside the Shizuku-spawned process (shell uid). Must keep a no-arg constructor. */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val timedOut = AtomicBoolean(false)
        val timeout = thread(name = "Shell-timeout", isDaemon = true) {
            try {
                Thread.sleep(25_000)
                timedOut.set(true)
                process.destroyForcibly()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            return if (timedOut.get()) "exit=124\n后台设置命令超时" else "exit=$code\n$output".trim()
        } finally {
            timeout.interrupt()
            process.destroy()
        }
    }
}
