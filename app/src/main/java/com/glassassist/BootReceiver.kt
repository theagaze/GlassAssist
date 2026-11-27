package com.glassassist

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.glassassist.core.Prefs
import com.glassassist.core.RootShellManager
import com.glassassist.service.FloatingBallService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val app = context.applicationContext
        if (!Prefs.get(app).serviceEnabled) return
        val pending = goAsync()
        RootShellManager.provisionSelfAsync(app) { ok, _ ->
            if (ok) FloatingBallService.start(app)
            pending.finish()
        }
    }
}
