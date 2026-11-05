package com.pulse.app.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import com.pulse.app.PulseGraph
import com.pulse.app.R
import com.pulse.app.Route
import com.pulse.app.data.Reminder
import com.pulse.app.domain.Repeat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The on-time alert. An exact alarm wakes the phone at the right moment, but Android forgets
 * alarms when the phone restarts, so each reminder also has a WorkManager job as a backup.
 */
class ExactAlarms(private val context: Context, private val demo: Boolean = false) {
    private val manager get() = context.getSystemService(AlarmManager::class.java)

    private fun intent(id: Long, once: Boolean): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            // Extras don't make a PendingIntent unique, so the action separates each kind of alarm.
            .setAction("com.pulse.app.ALARM.$demo.$once")
            .putExtra(ReminderScheduler.KEY_ID, id)
            .putExtra(ReminderScheduler.KEY_DEMO, demo)
            .putExtra(ReminderScheduler.KEY_ONCE, once)
        return PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun set(id: Long, at: Long, once: Boolean = false) {
        val alarm = intent(id, once)
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        // If Android refuses an exact alarm, a close one is still better than none.
        runCatching {
            if (canBeExact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
            }
        }.onFailure { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm) }
    }

    fun cancel(id: Long, once: Boolean = false) = manager.cancel(intent(id, once))
}

class ReminderScheduler(private val context: Context, private val demo: Boolean = false) {
    private val alarms = ExactAlarms(context, demo)

    private fun enqueue(name: String, id: Long, at: Long, once: Boolean) {
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(KEY_ID to id, KEY_DEMO to demo, KEY_ONCE to once))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, work)
    }

    fun schedule(reminder: Reminder) {
        if (!reminder.confirmed || reminder.done) return
        enqueue(name(reminder.id), reminder.id, reminder.dueAt, once = false)
        alarms.set(reminder.id, reminder.dueAt)
    }

    /** One extra alert for a repeating reminder, leaving its normal schedule alone. */
    fun snoozeOnce(reminder: Reminder, at: Long) {
        enqueue(snoozeName(reminder.id), reminder.id, at, once = true)
        alarms.set(reminder.id, at, once = true)
    }

    fun cancel(id: Long) {
        cancelWork(id, once = false)
        cancelWork(id, once = true)
        cancelAlarm(id, once = false)
        cancelAlarm(id, once = true)
    }

    fun cancelWork(id: Long, once: Boolean) {
        WorkManager.getInstance(context).cancelUniqueWork(if (once) snoozeName(id) else name(id))
    }

    fun cancelAlarm(id: Long, once: Boolean) = alarms.cancel(id, once)

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWork()
    }

    // Demo and real databases both count ids from 1, so their jobs need separate names.
    private fun name(id: Long) = if (demo) "demo-reminder-$id" else "reminder-$id"
    private fun snoozeName(id: Long) = if (demo) "demo-snooze-$id" else "snooze-$id"

    companion object {
        const val KEY_ID = "id"
        const val KEY_DEMO = "demo"
        const val KEY_ONCE = "once"
    }
}

/** Shared by the alarm and its backup job, whichever gets there first. */
object ReminderDelivery {
    /** A job or alarm left over from before the reminder moved must not fire it early. */
    private const val EARLY_MS = 60_000L
    private const val CHANNEL = "reminders"

    /** Returns true if the reminder still has an alert to come, so its other trigger must stay set. */
    suspend fun deliver(graph: PulseGraph, id: Long, once: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val reminder = graph.db.reminderDao().byId(id) ?: return false
        if (reminder.done || !reminder.confirmed) return false
        if (!once && reminder.dueAt > now + EARLY_MS) return true
        show(graph.appContext, reminder)
        val next = (if (once) null else Repeat.of(reminder.repeat).next(reminder.dueAt, now, ZoneId.systemDefault())) ?: return false
        val updated = reminder.copy(dueAt = next)
        graph.db.reminderDao().update(updated)
        graph.reminders.schedule(updated)
        return true
    }

    private fun show(context: Context, reminder: Reminder) {
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!canPost) return

        val channel = NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context,
            reminder.id.toInt(),
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, Route.CALENDAR.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Reminder")
            .setSubText(ReminderPrompt.fromLine(context, reminder.source, reminder.origin))
            .setContentText(reminder.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Done", ReminderPrompt.action(context, ReminderPrompt.ACTION_DONE, reminder.id))
            .addAction(0, "In 10 min", ReminderPrompt.action(context, ReminderPrompt.ACTION_SNOOZE, reminder.id))
            .addAction(0, "Tomorrow", ReminderPrompt.action(context, ReminderPrompt.ACTION_TOMORROW, reminder.id))
            .build()
        NotificationManagerCompat.from(context).notify(reminder.id.toInt(), notification)
    }
}

/** The exact alarm went off. It clears the backup job so the same alert doesn't fire twice. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderScheduler.KEY_ID, -1)
        val once = intent.getBooleanExtra(ReminderScheduler.KEY_ONCE, false)
        val graph = (context.applicationContext as PulseApp).graph
        // A real reminder due during a demo is left to its backup job, which waits for the demo to end.
        if (intent.getBooleanExtra(ReminderScheduler.KEY_DEMO, false) != graph.demo) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!ReminderDelivery.deliver(graph, id, once)) graph.reminders.cancelWork(id, once)
            } finally {
                pending.finish()
            }
        }
    }
}

/** The backup: WorkManager survives a restart, though Android may run it a little late. */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(ReminderScheduler.KEY_ID, -1)
        val once = inputData.getBoolean(ReminderScheduler.KEY_ONCE, false)
        val graph = (applicationContext as PulseApp).graph
        // A real reminder due during a demo waits until the demo ends, and the reverse.
        if (inputData.getBoolean(ReminderScheduler.KEY_DEMO, false) != graph.demo) return Result.retry()
        if (!ReminderDelivery.deliver(graph, id, once)) graph.reminders.cancelAlarm(id, once)
        return Result.success()
    }
}
