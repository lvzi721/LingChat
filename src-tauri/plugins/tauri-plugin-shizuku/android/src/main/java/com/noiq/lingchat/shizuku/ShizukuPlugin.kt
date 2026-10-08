package com.noiq.lingchat.shizuku

import android.app.Activity
import android.content.pm.PackageManager
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * LingChat Shizuku 插件（Android 端）。
 *
 * 与 Rust 侧契约（见 plugins/tauri-plugin-shizuku/src/mobile.rs + models.rs）：
 *   execute   入参 {command, timeoutMs, cwd}   返回 {stdout, stderr, exitCode, timedOut}
 *   available 入参 {}                          返回 {available, granted}
 *   check     入参 {}                          返回 {granted, message}
 *   request   入参 {requestCode}               返回 {granted, message}
 *
 * 目标：以 shell(uid=2000) 身份执行命令（需 Shizuku 以 ADB 模式启动）。
 */
@TauriPlugin
class ShizukuPlugin(private val activity: Activity) : Plugin(activity) {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private fun isAvailable(): Boolean =
        try { Shizuku.pingBinder() } catch (t: Throwable) { false }

    private fun isGranted(): Boolean =
        try {
            isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) { false }

    @Command
    fun available(invoke: Invoke) {
        val out = JSObject()
        out.put("available", isAvailable())
        out.put("granted", isGranted())
        invoke.resolve(out)
    }

    @Command
    fun check(invoke: Invoke) {
        val out = JSObject()
        val granted = isGranted()
        out.put("granted", granted)
        if (!granted) out.put("message", "Shizuku 权限未授予")
        invoke.resolve(out)
    }

    @Command
    fun request(invoke: Invoke) {
        val code = invoke.getArgs().optInt("requestCode", 1001)
        val out = JSObject()
        try {
            if (isGranted()) {
                out.put("granted", true)
            } else {
                Shizuku.requestPermission(code)
                out.put("granted", false)
                out.put("message", "已弹出授权请求，请在系统弹窗中允许")
            }
        } catch (t: Throwable) {
            out.put("granted", false)
            out.put("message", "请求权限失败: " + (t.message ?: "未知错误"))
        }
        invoke.resolve(out)
    }

    @Command
    fun execute(invoke: Invoke) {
        val args = invoke.getArgs()
        val command = args.optString("command", "")
        val timeoutMs = args.optLong("timeoutMs", 60000L)
        val cwd = if (args.has("cwd") && !args.isNull("cwd")) args.optString("cwd", "") else ""

        executor.execute {
            val out = JSObject()
            try {
                if (command.isBlank()) {
                    out.put("stdout", "")
                    out.put("stderr", "命令不能为空")
                    out.put("exitCode", -1)
                    out.put("timedOut", false)
                    invoke.resolve(out)
                    return@execute
                }
                if (!isAvailable()) {
                    out.put("stdout", "")
                    out.put("stderr", "Shizuku 服务未运行")
                    out.put("exitCode", -1)
                    out.put("timedOut", false)
                    invoke.resolve(out)
                    return@execute
                }
                if (!isGranted()) {
                    out.put("stdout", "")
                    out.put("stderr", "Shizuku 权限未授予")
                    out.put("exitCode", -1)
                    out.put("timedOut", false)
                    invoke.resolve(out)
                    return@execute
                }

                val process = Shizuku.newProcess(
                    arrayOf("sh", "-c", command),
                    null,
                    cwd.ifBlank { null }
                )

                val stdoutBuf = StringBuilder()
                val stderrBuf = StringBuilder()
                val tOut = Thread { readAll(process.inputStream, stdoutBuf) }
                val tErr = Thread { readAll(process.errorStream, stderrBuf) }
                tOut.start()
                tErr.start()

                var timedOut = false
                val deadline = System.currentTimeMillis() + timeoutMs
                while (true) {
                    try {
                        process.exitValue()
                        break
                    } catch (e: IllegalThreadStateException) {
                        if (timeoutMs > 0L && System.currentTimeMillis() > deadline) {
                            timedOut = true
                            process.destroy()
                            break
                        }
                        Thread.sleep(50)
                    }
                }

                tOut.join(2000)
                tErr.join(2000)

                val exitCode = try { process.exitValue() } catch (t: Throwable) { -1 }

                out.put("stdout", stdoutBuf.toString())
                out.put("stderr", stderrBuf.toString())
                out.put("exitCode", exitCode)
                out.put("timedOut", timedOut)
                invoke.resolve(out)
            } catch (t: Throwable) {
                out.put("stdout", "")
                out.put("stderr", "执行失败: " + (t.message ?: "未知错误"))
                out.put("exitCode", -1)
                out.put("timedOut", false)
                invoke.resolve(out)
            }
        }
    }

    private fun readAll(stream: InputStream, sb: StringBuilder) {
        try {
            val reader = BufferedReader(InputStreamReader(stream))
            val buf = CharArray(4096)
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
        } catch (t: Throwable) {
            // 忽略读取异常
        }
    }
}
