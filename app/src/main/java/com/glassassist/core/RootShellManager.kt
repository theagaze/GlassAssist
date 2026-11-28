package com.glassassist.core

import android.content.Context
import android.os.SystemClock
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Dois processos `su` persistentes e INDEPENDENTES:
 *
 * - [defaultShell]: keyevents, settings, provision, reboot. Nunca espera uma captura.
 * - [captureShell]: usado somente por `SystemActions.captureScreenTo/captureToGallery`.
 *
 * Cada shell tem lock, ioLock, executor, buffer de resposta e reader próprios, de modo
 * que um `screencap` de 5 s não segura o lock do shell de keyevents.
 *
 * KernelSU não exibe um pop-up automático: se o pacote não estiver habilitado na aba
 * Superuser, o processo nasce mas os comandos retornam "Permission denied". Esse estado
 * é detectado e devolvido em [ShellResult.message] para a MainActivity explicar ao usuário.
 */
object RootShellManager {

    data class ShellResult(val ok: Boolean, val output: String = "", val message: String = "")

    private class Shell(private val threadName: String) {

        private val lock = Any()
        private val ioLock = java.lang.Object()
        private val executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, threadName).apply { isDaemon = true }
        }

        private var process: Process? = null
        private var stdin: DataOutputStream? = null
        private var readerThread: Thread? = null
        private val response = StringBuilder()
        private var pendingToken: String? = null
        private var completed = true
        private var sequence = 0
        private var lastError: String? = null

        private val candidates = arrayOf("su", "/system/xbin/su", "/system/bin/su", "/data/adb/ksu/bin/su")

        @Volatile
        var connected: Boolean = false
            private set

        private fun ensure(): Boolean {
            try {
            val current = process
            if (current != null && current.isAlive && stdin != null && connected) return true
            closeLocked()

            var started: Process? = null
            var failure: IOException? = null
            for (candidate in candidates) {
                try {
                    started = ProcessBuilder(candidate)
                        .redirectErrorStream(true)
                        .start()
                    break
                } catch (e: IOException) {
                    failure = e
                }
            }
            if (started == null) {
                lastError = "Não foi possível iniciar o su (${failure?.message ?: "binário não encontrado"}). " +
                    "Verifique se o KernelSU está instalado."
                connected = false
                return false
            }

            process = started
            stdin = DataOutputStream(started.outputStream.buffered(4096))
            connected = true
            lastError = null
            startReaderLocked(started)
            return true
            } catch (t: Throwable) {
                closeLocked()
                connected = false
                lastError = "Falha ao iniciar o shell root: ${t.message ?: t.javaClass.simpleName}"
                return false
            }
        }

        private fun startReaderLocked(p: Process) {
            readerThread = Thread(Runnable {
                val reader = BufferedReader(InputStreamReader(p.inputStream, StandardCharsets.UTF_8))
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(ioLock) {
                            val token = pendingToken
                            if (token != null && line.contains(token)) {
                                completed = true
                                ioLock.notifyAll()
                            } else {
                                response.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // O shell foi fechado (reboot, shutdown ou perda de autorização).
                } finally {
                    synchronized(ioLock) {
                        completed = true
                        ioLock.notifyAll()
                    }
                }
            }, "${threadName}-reader").apply { isDaemon = true }
            readerThread?.start()
        }

        /** Executa um comando e espera o sentinel, sem abrir um novo processo `su`. */
        fun execute(command: String, timeoutMs: Long = 2500): ShellResult {
            try {
            synchronized(lock) {
                if (!ensure()) return ShellResult(false, message = lastError ?: "Root indisponível")

                val token = "__GA_DONE_${++sequence}__"
                synchronized(ioLock) {
                    response.setLength(0)
                    pendingToken = token
                    completed = false
                }

                val written = try {
                    stdin!!.writeBytes("$command\n echo $token\n")
                    stdin!!.flush()
                    true
                } catch (e: IOException) {
                    closeLocked()
                    lastError = "Shell root fechado: ${e.message}"
                    false
                }
                if (!written) return ShellResult(false, message = lastError ?: "Shell root fechado")

                synchronized(ioLock) {
                    val deadline = SystemClock.elapsedRealtime() + timeoutMs
                    while (!completed && SystemClock.elapsedRealtime() < deadline) {
                        ioLock.wait((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1))
                    }
                }

                val out: String
                val finished: Boolean
                synchronized(ioLock) {
                    out = response.toString().trim()
                    finished = completed
                    pendingToken = null
                }

                if (!finished) {
                    closeLocked()
                    return ShellResult(false, out, "Timeout ao executar o comando root")
                }

                val lower = out.lowercase()
                val denied = lower.contains("permission denied") ||
                    lower.contains("not allowed") ||
                    lower.contains("denied") ||
                    lower.contains("not authorized")
                if (denied) {
                    return ShellResult(
                        false,
                        out,
                        "Root negado. Abra o KernelSU Manager → Superuser e ative o toggle do GlassAssist."
                    )
                }
                return ShellResult(true, out)
            }
            } catch (t: Throwable) {
                closeLocked()
                return ShellResult(
                    false,
                    message = "Falha no shell root: ${t.message ?: t.javaClass.simpleName}"
                )
            }
        }

        /** Fire-and-forget (keyevents, reboot etc.). Não bloqueia a thread de UI. */
        fun send(command: String): Boolean {
            synchronized(lock) {
                if (!ensure()) return false
                return try {
                    stdin!!.writeBytes("$command\n")
                    stdin!!.flush()
                    true
                } catch (e: Throwable) {
                    closeLocked()
                    lastError = "Shell root fechado: ${e.message ?: e.javaClass.simpleName}"
                    false
                }
            }
        }

        fun sendAsync(command: String, callback: ((Boolean) -> Unit)? = null) {
            executor.execute {
                try {
                    val ok = send(command)
                    callback?.invoke(ok)
                } catch (_: Throwable) {
                }
            }
        }

        fun executeAsync(command: String, timeoutMs: Long, callback: (ShellResult) -> Unit) {
            executor.execute {
                try {
                    callback(execute(command, timeoutMs))
                } catch (t: Throwable) {
                    callback(ShellResult(false, message = t.message ?: "Falha no shell root"))
                }
            }
        }

        fun close() = synchronized(lock) { closeLocked() }

        private fun closeLocked() {
            try {
                stdin?.writeBytes("exit\n")
                stdin?.flush()
                stdin?.close()
            } catch (_: IOException) {
            }
            stdin = null
            process?.destroy()
            process = null
            readerThread = null
            connected = false
            synchronized(ioLock) {
                response.setLength(0)
                pendingToken = null
                completed = true
                ioLock.notifyAll()
            }
        }
    }

    private val defaultShell = Shell("glassassist-root")
    private val captureShell = Shell("glassassist-root-capture")

    val connected: Boolean get() = defaultShell.connected

    fun execute(command: String, timeoutMs: Long = 2500): ShellResult = defaultShell.execute(command, timeoutMs)

    /** Chamado do thread de UI: a leitura bloqueante fica no executor do shell. */
    fun executeAsync(
        command: String,
        timeoutMs: Long = 2500,
        callback: (ShellResult) -> Unit
    ) = defaultShell.executeAsync(command, timeoutMs, callback)

    /**
     * Bloqueante por design, mas executado SEMPRE em thread de fundo pelo serviço.
     * Usa o shell exclusivo de captura para não bloquear keyevents.
     */
    fun executeCapture(command: String, timeoutMs: Long = 5000): ShellResult =
        captureShell.execute(command, timeoutMs)

    fun send(command: String): Boolean = defaultShell.send(command)

    fun sendAsync(command: String, callback: ((Boolean) -> Unit)? = null) =
        defaultShell.sendAsync(command, callback)

    fun checkRoot(): Pair<Boolean, String> {
        val result = execute("id")
        if (result.ok && result.output.contains("uid=0")) {
            return true to "Root ativo (uid=0) via shell persistente"
        }
        val message = when {
            result.message.isNotBlank() -> result.message
            result.output.isNotBlank() -> "Root não confirmado: ${result.output.take(120)}"
            else -> "O comando `id` não retornou uid=0. Ative o GlassAssist no KernelSU Manager → Superuser."
        }
        return false to message
    }

    fun checkRootAsync(callback: (Boolean, String) -> Unit) {
        defaultShell.executeAsync("id", 2500) { result ->
            val ok = result.ok && result.output.contains("uid=0")
            val message = if (ok) {
                "Root ativo (uid=0) via shell persistente"
            } else {
                when {
                    result.message.isNotBlank() -> result.message
                    result.output.isNotBlank() -> "Root não confirmado: ${result.output.take(120)}"
                    else -> "O comando `id` não retornou uid=0. Ative o GlassAssist no KernelSU Manager → Superuser."
                }
            }
            callback(ok, message)
        }
    }

    /**
     * Concessão automática de privilégios. O único pré-requisito manual é o toggle do
     * pacote em KernelSU Manager → Superuser; depois que o shell responde `uid=0`,
     * o próprio app libera overlay, WRITE_SECURE_SETTINGS, notificações, câmera
     * (lanterna) e a whitelist de bateria.
     */
    fun provisionSelf(context: Context): Pair<Boolean, String> {
        val pkg = context.packageName
        val script = listOf(
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "pm grant $pkg android.permission.CAMERA",
            "dumpsys deviceidle whitelist +$pkg",
            "appops set $pkg RUN_ANY_IN_BACKEND allow || true",
            "appops set $pkg WAKE_LOCK allow || true",
            "echo __GA_PROVISION_OK__"
        ).joinToString("\n")
        val result = execute(script, 6000)
        if (!result.ok) return false to result.message
        if (!result.output.contains("__GA_PROVISION_OK__")) {
            return false to "O shell root respondeu, mas a concessão automática não confirmou execução. " +
                "Verifique o toggle do GlassAssist em KernelSU Manager → Superuser."
        }
        return true to "Root autorizado. Overlay, WRITE_SECURE_SETTINGS, notificações, câmera e " +
            "whitelist de bateria concedidos automaticamente pelo shell persistente."
    }

    fun provisionSelfAsync(context: Context, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val (ok, message) = provisionSelf(context)
                callback(ok, message)
            } catch (t: Throwable) {
                callback(false, "Falha na concessão root: ${t.message ?: t.javaClass.simpleName}")
            }
        }.apply { isDaemon = true }.start()
    }

    fun close() {
        defaultShell.close()
        captureShell.close()
    }
}
