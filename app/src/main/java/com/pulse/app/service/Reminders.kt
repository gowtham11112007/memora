package com.pulse.app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pulse.app.MainActivity
import com.pulse.app.PulseApp
import com.pulse.app.R
import com.pulse.app.data.Reminder
import java.util.concurrent.TimeUnit

/**
 * WorkManager runs a job later, even if the app was closed or the phone restarted.
 * Android may delay it a little to save battery, so a reminder is not exact to the second.
 */
class ReminderScheduler(private val context: Context, private val demo: Boolean = false) {
    fun schedule(reminder: Reminder) {
        if (!reminder.confirmed || reminder.done) return
        val delay = (reminder.dueAt - System.currentTimeMillis()).coerceAtLeast(0)
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_ID to reminder.id, KEY_DEMO to demo))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name(reminder.id), ExistingWorkPolicy.REPLACE, work)
    }

    fun cancel(id: Long) {
        WorkManager.getInstance(context).cancelUniqueWork(name(id))
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWork()
    }

    private fun name(id: Long) = "reminder-$id"

    companion object {
        const val KEY_ID = "id"
        const val KEY_DEMO = "demo"
    }
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(ReminderScheduler.KEY_ID, -1)
        val graph = (applicationContext as PulseApp).graph
        // A real reminder due during a demo waits until the demo ends, and the reverse.
        if (inputData.getBoolean(ReminderScheduler.KEY_DEMO, false) != graph.demo) return Result.retry()
        val reminder = graph.db.reminderDao().byId(id) ?: return Result.success()
        if (!reminder.done && reminder.confirmed) show(reminder)
        return Result.success()
    }

    private fun show(reminder: Reminder) {
        val context = applicationContext
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canPost) return

        val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Reminder")
            .setContentText(reminder.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(reminder.id.toInt(), notification)
    }

    private companion object { const val CHANNEL = "reminders" }
}
