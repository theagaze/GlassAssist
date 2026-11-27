package com.glassassist.core

import android.content.Context
import android.media.AudioManager

object VolumeController {

    private fun audio(context: Context): AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun max(context: Context, stream: Int): Int = audio(context).getStreamMaxVolume(stream)

    fun current(context: Context, stream: Int): Int = audio(context).getStreamVolume(stream)

    fun set(context: Context, stream: Int, value: Int, showUi: Boolean = false) {
        val am = audio(context)
        val clamped = value.coerceIn(0, am.getStreamMaxVolume(stream))
        var flags = 0
        if (showUi) flags = flags or AudioManager.FLAG_SHOW_UI
        am.setStreamVolume(stream, clamped, flags)
    }

    /** Ajuste pelo shell root, usado pelo serviço quando não há contexto de UI. */
    fun adjustMedia(direction: Int) {
        if (direction > 0) SystemActions.volumeUp() else SystemActions.volumeDown()
    }

    /**
     * Ajuste instantâneo via AudioManager, sem shell. O háptico de cada degrau é
     * disparado pelo chamador. O shell root continua apenas como fallback.
     */
    fun adjustMedia(context: Context, direction: Int) {
        try {
            audio(context).adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        } catch (_: Exception) {
            if (direction > 0) SystemActions.volumeUp() else SystemActions.volumeDown()
        }
    }

    fun setRinger(context: Context, mode: Int) {
        audio(context).ringerMode = mode
    }

    fun ringerMode(context: Context): Int = audio(context).ringerMode

    fun ringerLabel(context: Context): String = when (audio(context).ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> "Som"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrar"
        else -> "Silencioso"
    }

    /** Som → Vibrar → Silencioso → Som. */
    fun cycleRinger(context: Context): String {
        val am = audio(context)
        when (am.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> am.ringerMode = AudioManager.RINGER_MODE_SILENT
            else -> am.ringerMode = AudioManager.RINGER_MODE_NORMAL
        }
        return ringerLabel(context)
    }
}
