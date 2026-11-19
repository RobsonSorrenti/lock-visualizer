package com.exemplo.lockvisualizer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Liga o serviço de novo depois que o celular reinicia. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.load(context).enabled) {
            try { MusicWatchService.start(context) } catch (_: Exception) {}
        }
    }
}
