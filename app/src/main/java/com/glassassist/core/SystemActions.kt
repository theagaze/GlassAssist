package com.glassassist.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream

object SystemActions {

    fun keyevent(code: Int) {
        RootShellManager.sendAsync("input keyevent $code")
    }

    fun keyeventNow(code: Int): RootShellManager.ShellResult =
        RootShellManager.execute("input keyevent $code")

    fun screenOff() = keyevent(26)

    fun volumeUp() = keyevent(24)

    fun volumeDown() = keyevent(25)

    fun powerMenu() = RootShellManager.sendAsync("input keyevent --longpress 26")

    fun reboot(target: String? = null) {
        val cmd = if (target.isNullOrBlank()) "svc power reboot" else "svc power reboot $target"
        RootShellManager.sendAsync(cmd)
    }

    fun shutdown() = RootShellManager.sendAsync("svc power shutdown")

    fun expandNotifications() = RootShellManager.sendAsync("cmd statusbar expand-notifications")

    fun expandQuickSettings() = RootShellManager.sendAsync("cmd statusbar expand-settings")

    fun forceStop(packageName: String) = RootShellManager.sendAsync("am force-stop $packageName")

    /** Leitura e escrita em um único comando: nunca bloqueia a thread de UI. */
    fun setAutoRotation(enabled: Boolean) {
        if (enabled) {
            RootShellManager.sendAsync("settings put system accelerometer_rotation 1")
            return
        }
        RootShellManager.sendAsync(
            "r=\$(settings get system user_rotation); " +
                "case \"\$r\" in 0|1|2|3) ;; *) r=0 ;; esac; " +
                "settings put system accelerometer_rotation 0; " +
                "settings put system user_rotation \$r"
        )
    }

    /** Ajuste de brilho feito pelo shell root, sem depender de UI do sistema. */
    fun brightnessStep(delta: Int) {
        val step = delta.coerceIn(-255, 255)
        RootShellManager.sendAsync(
            "b=\$(settings get system screen_brightness); " +
                "case \"\$b\" in ''|null) b=128 ;; esac; " +
                "n=\$((b + $step)); " +
                "[ \$n -lt 1 ] && n=1; [ \$n -gt 255 ] && n=255; " +
                "settings put system screen_brightness_mode 0; " +
                "settings put system screen_brightness \$n"
        )
    }

    /**
     * Snapshot usado como backdrop do painel. Usa o shell `capture`, separado do
     * shell de keyevents: um `screencap` lento nunca bloqueia `send()`.
     */
    fun captureScreenTo(file: File, timeoutMs: Long = 5000): RootShellManager.ShellResult =
        RootShellManager.executeCapture("screencap -p '${file.absolutePath}'", timeoutMs)

    /** Captura limpa salva na galeria quando o keyevent 120 não está disponível. */
    fun captureToGallery(context: Context): Pair<Boolean, String> {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots")
        dir.mkdirs()
        val file = File(dir, "GlassAssist-${System.currentTimeMillis()}.png")
        val shot = captureScreenTo(file)
        if (!shot.ok) return false to shot.message
        val uri = registerInMediaStore(context, file)
        if (uri != null) {
            // A cópia já está no MediaStore: não deixa o PNG duplicado no app.
            file.delete()
            return true to uri.toString()
        }
        return true to file.absolutePath
    }

    fun registerInMediaStore(context: Context, file: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GlassAssist")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            val out = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            out.use { stream ->
                FileInputStream(file).use { input -> input.copyTo(stream) }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            return null
        }
    }
}
