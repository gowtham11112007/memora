package com.pulse.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.pulse.app.PulseApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android calls onNotificationPosted for EVERY app's notifications once the user grants
 * "Notification access". We do almost nothing here: hand the text to CaptureRepository,
 * which applies the allow-list and privacy filter before anything is stored.
 */
class PulseNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()

        val repo = (application as PulseApp).graph.captureRepository
        scope.launch { repo.capture(sbn.packageName, title, text, sbn.postTime) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
