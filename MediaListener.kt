package com.exemplo.lockvisualizer

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService

/**
 * Não faz nada com as notificações: só existe porque o Android exige um
 * "ouvinte de notificações" ativo para deixar o app ver qual música está tocando.
 */
class MediaListener : NotificationListenerService() {
    companion object {
        fun hasAccess(c: Context): Boolean {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return nm.isNotificationListenerAccessGranted(ComponentName(c, MediaListener::class.java))
        }
    }
}
