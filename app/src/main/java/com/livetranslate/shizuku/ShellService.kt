package com.livetranslate.shizuku

import kotlin.system.exitProcess

/** Runs inside the Shizuku-spawned process (shell uid). Must keep a no-arg constructor. */
class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        val code = process.waitFor()
        return "exit=$code\n$out$err".trim()
    }
}
