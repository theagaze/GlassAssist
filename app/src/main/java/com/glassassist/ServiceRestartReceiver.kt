package com.glassassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glassassist.core.Prefs
import com.glassassist.core.RootShellManager
import com.glassassist.service.FloatingBallService

/** Rede de segurança: o alarme periódico religa o serviço se o sistema o tiver matado. */
class ServiceRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Respeita o "Parar overlay": sem isso o alarme religava o serviço sozinho.
        if (intent.action != ACTION || FloatingBallService.isRunning ||
            !Prefs.get(context.applicationContext).serviceEnabled
        ) return
        val pending = goAsync()
        RootShellManager.provisionSelfAsync(context.applicationContext) { ok, _ ->
            if (ok) FloatingBallService.start(context.applicationContext)
            pending.finish()
        }
    }

    companion object {
        const val ACTION = "com.glassassist.RESTART_SERVICE"
    }
}
