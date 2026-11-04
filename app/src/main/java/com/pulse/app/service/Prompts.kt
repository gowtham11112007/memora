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
import com.pulse.app.data.Reminder
import com.pulse.app.data.ResendClient
import com.pulse.app.domain.Category
import com.pulse.app.domain.MoneyItem
import com.pulse.app.domain.Summaries
import com.pulse.app.domain.TimeRange
import com.pulse.app.domain.formatRupees
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

/** The heads-up popup that asks "Set a reminder?", with Yes and No right on the notification. */
object ReminderPrompt {
    private const val CHANNEL = "reminder_prompts"
    const val ACTION_YES = "com.pulse.app.REMINDER_YES"
    const val ACTION_NO = "com.pulse.app.REMINDER_NO"
    const val EXTRA_ID = "id"

    private fun notificationId(reminderId: Long) = (100_000 + reminderId).toInt()

    fun show(context: Context, reminder: Reminder) {
        val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminder suggestions", NotificationManager.IMPORTANCE_HIGH),
        )
        val due = Instant.ofEpochMilli(reminder.dueAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH))
        val open = PendingIntent.getActivity(
            context,
            notificationId(reminder.id),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Set a reminder for $due?")
            .setContentText(reminder.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.title))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Yes, remind me", action(context, ACTION_YES, reminder.id))
            .addAction(0, "No", action(context, ACTION_NO, reminder.id))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(reminder.id), notification)
    }

    private fun action(context: Context, name: String, id: Long): PendingIntent {
        val intent = Intent(context, ReminderPromptReceiver::class.java).setAction(name).putExtra(EXTRA_ID, id)
        val code = (if (name == ACTION_YES) 200_000 else 300_000) + id.toInt()
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun dismiss(context: Context, reminderId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(reminderId))
    }
}

/** Runs when the user taps Yes or No on the popup, even if the app is closed. */
class ReminderPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(ReminderPrompt.EXTRA_ID, -1)
        val action = intent.action
        val graph = (context.applicationContext as PulseApp).graph
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reminder = graph.db.reminderDao().byId(id)
                if (reminder != null) {
                    if (action == ReminderPrompt.ACTION_YES) {
                        val confirmed = reminder.copy(confirmed = true)
                        graph.db.reminderDao().update(confirmed)
                        graph.reminders.schedule(confirmed)
                    } else {
                        graph.db.reminderDao().delete(reminder)
                    }
                }
                ReminderPrompt.dismiss(context, id)
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
