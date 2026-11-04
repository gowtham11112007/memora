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
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulse.app.MainActivity
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.R
import com.pulse.app.domain.Category
import com.pulse.app.domain.MoneyItem
import com.pulse.app.domain.RecordType
import com.pulse.app.domain.Summaries
import com.pulse.app.domain.TimeRange
import com.pulse.app.domain.formatRupees
import com.pulse.app.domain.startDate
import com.pulse.app.domain.startMillis
import com.pulse.app.domain.toLocalDate
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val CHANNEL = "summaries"

private fun post(context: Context, id: Int, title: String, text: String) {
    val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!allowed) return
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL, "Summaries and budget alerts", NotificationManager.IMPORTANCE_DEFAULT),
    )
    val open = PendingIntent.getActivity(
        context,
        id,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val notification = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(open)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(context).notify(id, notification)
}

/** Warns once a month at 80% and again at 100% of the budget. It stays on the phone. */
object BudgetAlert {
    private const val ID_BUDGET = 500_001

    /** Which alert is due, given what was already sent this month. Null if none. */
    fun due(spent: Long, budget: Long, alreadySent: Int): Int? {
        if (budget <= 0) return null
        val percent = spent * 100 / budget
        return when {
            percent >= 100 && alreadySent < 100 -> 100
            percent >= 80 && alreadySent < 80 -> 80
            else -> null
        }
    }

    suspend fun check(graph: PulseGraph) {
        val budget = graph.moneyPrefs.state.value.budgetPaise
        if (budget <= 0) return
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val from = TimeRange.MONTH.startDate(today).startMillis(zone)
        val spent = graph.db.moneyRecordDao().all()
            .filter { it.type == RecordType.EXPENSE && it.occurredAt >= from }
            .sumOf { it.amountPaise }
        val prefs = graph.appContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)
        val key = "budget-${graph.demo}-${today.year}-${today.monthValue}"
        val alert = due(spent, budget, prefs.getInt(key, 0)) ?: return
        prefs.edit().putInt(key, alert).apply()
        val left = budget - spent
        post(
            graph.appContext,
            ID_BUDGET,
            if (alert >= 100) "You've reached your monthly budget" else "You've used $alert% of your budget",
            if (left >= 0) {
                "${formatRupees(left)} left of ${formatRupees(budget)} this month."
            } else {
                "You're ${formatRupees(-left)} over your ${formatRupees(budget)} budget this month."
            },
        )
    }
}

/** A local recap at about 9 PM. It's skipped on days with nothing to say. */
object EveningBrief {
    private const val WORK = "evening-brief"
    const val ID = 500_002

    private fun prefs(context: Context) = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun isOn(context: Context) = prefs(context).getBoolean("evening", true)

    fun set(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("evening", on).apply()
        apply(context)
    }

    /** Safe to call at every launch: a recap that's already scheduled keeps its time. */
    fun apply(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!isOn(context)) {
            manager.cancelUniqueWork(WORK)
            return
        }
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(21, 0))
        if (next <= now) next = next.plusDays(1)
        val work = PeriodicWorkRequestBuilder<EveningBriefWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    /** Null when there's nothing worth interrupting for. */
    fun message(spentTodayPaise: Long, receivedTodayPaise: Long, dueTomorrow: Int): String? {
        val parts = buildList {
            if (spentTodayPaise > 0) add("You spent ${formatRupees(spentTodayPaise)} today.")
            if (receivedTodayPaise > 0) add("You received ${formatRupees(receivedTodayPaise)}.")
            if (dueTomorrow == 1) add("1 reminder tomorrow.")
            if (dueTomorrow > 1) add("$dueTomorrow reminders tomorrow.")
        }
        return parts.joinToString(" ").ifEmpty { null }
    }
}

class EveningBriefWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as PulseApp).graph
        if (graph.demo || !EveningBrief.isOn(applicationContext)) return Result.success()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val items = graph.db.moneyRecordDao().all().map { MoneyItem(it.amountPaise, it.merchant, it.type, it.occurredAt) }
        val summary = Summaries.since(items, today.startMillis(zone), emptyMap<String, Category>())
        val tomorrow = graph.db.reminderDao().all().count {
            it.confirmed && !it.done && it.dueAt.toLocalDate(zone) == today.plusDays(1)
        }
        EveningBrief.message(summary.spentPaise, summary.receivedPaise, tomorrow)?.let {
            post(applicationContext, EveningBrief.ID, "Your day", it)
        }
        return Result.success()
    }
}
