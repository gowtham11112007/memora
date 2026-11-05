package com.pulse.app.service

import android.Manifest
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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulse.app.MainActivity
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.R
import com.pulse.app.Route
import com.pulse.app.data.PulseDatabase
import com.pulse.app.data.Reminder
import com.pulse.app.data.ResendClient
import com.pulse.app.data.Todo
import com.pulse.app.domain.Category
import com.pulse.app.domain.MoneyItem
import com.pulse.app.domain.Repeat
import com.pulse.app.domain.Summaries
import com.pulse.app.domain.TimeRange
import com.pulse.app.domain.formatRupees
import com.pulse.app.domain.originLine
import com.pulse.app.domain.startDate
import com.pulse.app.domain.startMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The heads-up popups that ask before anything is set, with the choices right on the notification. */
object ReminderPrompt {
    private const val CHANNEL = "reminder_prompts"
    const val ACTION_YES = "com.pulse.app.REMINDER_YES"
    const val ACTION_NO = "com.pulse.app.REMINDER_NO"
    const val ACTION_TO_TODO = "com.pulse.app.REMINDER_TO_TODO"
    const val ACTION_DONE = "com.pulse.app.REMINDER_DONE"
    const val ACTION_SNOOZE = "com.pulse.app.REMINDER_SNOOZE"
    const val ACTION_TOMORROW = "com.pulse.app.REMINDER_TOMORROW"
    const val ACTION_TODO_YES = "com.pulse.app.TODO_YES"
    const val ACTION_TODO_REMIND = "com.pulse.app.TODO_REMIND"
    const val ACTION_TODO_NO = "com.pulse.app.TODO_NO"
    const val EXTRA_ID = "id"

    private fun notificationId(reminderId: Long) = (100_000 + reminderId).toInt()
    private fun todoNotificationId(todoId: Long) = (400_000 + todoId).toInt()

    private fun ready(context: Context): Boolean {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Reminder suggestions", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        return allowed
    }

    /** "From Rahul on WhatsApp", so a suggestion is never a mystery. */
    fun fromLine(context: Context, source: String, sender: String): String? {
        val pm = context.packageManager
        val app = if (source == "manual" || source == "demo") {
            null
        } else {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(source, 0)).toString() }.getOrNull()
        }
        return originLine(sender, app)
    }

    private fun builder(context: Context, code: Int, title: String, text: String, from: String?, opens: Route = Route.CALENDAR) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(from?.let { "$it: $text" } ?: text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOfNotNull(from, text).joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    code,
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, opens.name),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)

    /** Asks about a detected reminder. If Automatic reminders already set it, offers Undo instead. */
    fun show(context: Context, reminder: Reminder) {
        if (!ready(context)) return
        val due = Instant.ofEpochMilli(reminder.dueAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH))
        val code = notificationId(reminder.id)
        val from = fromLine(context, reminder.source, reminder.origin)
        val notification = if (reminder.confirmed) {
            builder(context, code, "Reminder set for $due", reminder.title, from)
                .addAction(0, "Undo", action(context, ACTION_NO, reminder.id))
        } else {
            builder(context, code, "Set a reminder for $due?", reminder.title, from)
                .addAction(0, "Yes, remind me", action(context, ACTION_YES, reminder.id))
                .addAction(0, "To-do instead", action(context, ACTION_TO_TODO, reminder.id))
                .addAction(0, "No", action(context, ACTION_NO, reminder.id))
        }
        NotificationManagerCompat.from(context).notify(code, notification.build())
    }

    /** Offers a message that sounds like a task, when it has no date to set a reminder from. */
    fun showTodo(context: Context, todo: Todo) {
        if (!ready(context)) return
        val code = todoNotificationId(todo.id)
        val notification = builder(context, code, "Is this something to do?", todo.text, fromLine(context, todo.source, todo.origin), Route.TODO)
            .addAction(0, "Add to-do", action(context, ACTION_TODO_YES, todo.id))
            .addAction(0, "Remind me tomorrow", action(context, ACTION_TODO_REMIND, todo.id))
            .addAction(0, "No", action(context, ACTION_TODO_NO, todo.id))
        NotificationManagerCompat.from(context).notify(code, notification.build())
    }

    /** Action and request code together keep each button's PendingIntent separate. */
    fun action(context: Context, name: String, id: Long): PendingIntent {
        val intent = Intent(context, ReminderPromptReceiver::class.java).setAction(name).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, id.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun dismiss(context: Context, reminderId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(reminderId))
    }

    fun dismissTodo(context: Context, todoId: Long) {
        NotificationManagerCompat.from(context).cancel(todoNotificationId(todoId))
    }

    /** Reminders and to-dos count their ids separately, so a to-do button must only close its own popup. */
    fun dismissFor(context: Context, action: String?, id: Long) {
        if (action == ACTION_TODO_YES || action == ACTION_TODO_REMIND || action == ACTION_TODO_NO) {
            dismissTodo(context, id)
        } else {
            dismiss(context, id)
            NotificationManagerCompat.from(context).cancel(id.toInt())
        }
    }
}

/** What each notification button does. No Android UI here, so it's tested directly. */
class NotificationActions(
    private val db: PulseDatabase,
    private val schedule: (Reminder) -> Unit,
    private val cancel: (Long) -> Unit,
    private val snoozeOnce: (Reminder, Long) -> Unit,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun run(action: String?, id: Long, now: Long) {
        when (action) {
            ReminderPrompt.ACTION_TODO_YES, ReminderPrompt.ACTION_TODO_REMIND, ReminderPrompt.ACTION_TODO_NO -> onTodo(action, id, now)
            else -> db.reminderDao().byId(id)?.let { onReminder(action, it, now) }
        }
    }

    private suspend fun onReminder(action: String?, reminder: Reminder, now: Long) {
        val dao = db.reminderDao()
        val repeating = Repeat.of(reminder.repeat) != Repeat.NONE
        when (action) {
            ReminderPrompt.ACTION_YES -> setUp(reminder.copy(confirmed = true))
            ReminderPrompt.ACTION_TO_TODO -> {
                dao.delete(reminder)
                cancel(reminder.id)
                db.todoDao().insert(Todo(text = reminder.title, createdAt = now, source = reminder.source, origin = reminder.origin))
            }
            // A repeating reminder has already moved to its next date, so Done has nothing more to do.
            ReminderPrompt.ACTION_DONE -> if (!repeating) {
                dao.update(reminder.copy(done = true))
                cancel(reminder.id)
            }
            ReminderPrompt.ACTION_SNOOZE -> later(reminder, repeating, now + SNOOZE_MS)
            ReminderPrompt.ACTION_TOMORROW ->
                later(reminder, repeating, Instant.ofEpochMilli(now).atZone(zone).plusDays(1).toInstant().toEpochMilli())
            else -> {
                dao.delete(reminder)
                cancel(reminder.id)
            }
        }
    }

    private suspend fun later(reminder: Reminder, repeating: Boolean, at: Long) {
        if (repeating) snoozeOnce(reminder, at) else setUp(reminder.copy(dueAt = at))
    }

    private suspend fun setUp(reminder: Reminder) {
        db.reminderDao().update(reminder)
        schedule(reminder)
    }

    private suspend fun onTodo(action: String, id: Long, now: Long) {
        val todo = db.todoDao().byId(id) ?: return
        when (action) {
            ReminderPrompt.ACTION_TODO_YES -> db.todoDao().update(todo.copy(confirmed = true))
            ReminderPrompt.ACTION_TODO_REMIND -> {
                val due = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
                val reminder = Reminder(title = todo.text, dueAt = due, confidence = 1.0, confirmed = true, source = todo.source, origin = todo.origin)
                schedule(reminder.copy(id = db.reminderDao().insert(reminder)))
                db.todoDao().delete(todo)
            }
            else -> db.todoDao().delete(todo)
        }
    }

    private companion object { const val SNOOZE_MS = 10L * 60 * 1000 }
}

/** Runs when the user taps a button on a notification, even if the app is closed. */
class ReminderPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderPrompt.EXTRA_ID, -1)
        val action = intent.action
        val graph = (context.applicationContext as PulseApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                NotificationActions(graph.db, graph.reminders::schedule, graph.reminders::cancel, graph.reminders::snoozeOnce)
                    .run(action, id, System.currentTimeMillis())
                ReminderPrompt.dismissFor(context, action, id)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Builds and sends the summary email. It holds totals only, with no merchants or messages. */
object EmailDigest {
    private const val WORK = "weekly-summary"

    fun schedule(context: Context, on: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!on) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val work = PeriodicWorkRequestBuilder<EmailDigestWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    suspend fun bodyText(graph: PulseGraph): String {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val items = graph.db.moneyRecordDao().all().map { MoneyItem(it.amountPaise, it.merchant, it.type, it.occurredAt) }
        val rules = graph.db.merchantRuleDao().all().associate { it.merchant to Category.valueOf(it.category) }
        val week = Summaries.since(items, TimeRange.WEEK.startDate(today).startMillis(zone), rules)
        val upcoming = graph.db.reminderDao().all().count { it.confirmed && !it.done && it.dueAt >= System.currentTimeMillis() }
        val categories = week.spentByCategory.filter { it.value > 0 }.entries
            .joinToString("\n") { "  ${it.key.label}: ${formatRupees(it.value)}" }
        return buildString {
            appendLine("Your Memora summary for this week")
            appendLine()
            appendLine("Spent: ${formatRupees(week.spentPaise)}")
            if (categories.isNotEmpty()) appendLine(categories)
            appendLine("Received: ${formatRupees(week.receivedPaise)}")
            appendLine("Upcoming reminders: $upcoming")
        }
    }

    /** Null on success, otherwise a reason. */
    suspend fun send(graph: PulseGraph): String? {
        val config = graph.aiSettings.loadEmail()
        if (!config.isSet) return "Add your Resend key and email first."
        return ResendClient(config.resendKey).send(config.toAddress, "Your Memora weekly summary", bodyText(graph))
    }
}

class EmailDigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PulseApp).graph
        if (graph.demo) return Result.retry()
        if (!graph.aiSettings.loadEmail().weekly) return Result.success()
        return if (EmailDigest.send(graph) == null) Result.success() else Result.retry()
    }
}
