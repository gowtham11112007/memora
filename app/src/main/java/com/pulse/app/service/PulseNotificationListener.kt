package com.pulse.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.pulse.app.PulseApp
import kotlinx.coroutines.CoroutineExceptionHandler
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

    // One bad message must never stop reading. The log names the error only, never the message.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> Log.w("Memora", "Capture failed: ${e.javaClass.simpleName}") },
    )

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and IGNORED_FLAGS != 0 || n.category in IGNORED_CATEGORIES) return

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

    private companion object {
        // Summaries repeat other notifications. Downloads, backups, music and calls aren't messages.
        const val IGNORED_FLAGS =
            Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE
        val IGNORED_CATEGORIES = setOf(
            Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_TRANSPORT,
            Notification.CATEGORY_CALL,
            Notification.CATEGORY_SERVICE,
            Notification.CATEGORY_SYSTEM,
        )
    }
}
