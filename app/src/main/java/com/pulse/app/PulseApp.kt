package com.pulse.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.pulse.app.data.AiSettings
import com.pulse.app.data.CaptureRepository
import com.pulse.app.data.DemoData
import com.pulse.app.data.MIGRATION_1_2
import com.pulse.app.data.MIGRATION_2_3
import com.pulse.app.data.MIGRATION_3_4
import com.pulse.app.data.MIGRATION_4_5
import com.pulse.app.data.MIGRATION_5_6
import com.pulse.app.data.MoneyPrefs
import com.pulse.app.data.PulseDatabase
import com.pulse.app.data.SmartReader
import com.pulse.app.service.BudgetAlert
import com.pulse.app.service.ReminderPrompt
import com.pulse.app.service.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tiny hand-made "service locator": one place that builds the database and repository.
 * Demo mode uses its own database and money settings, so real data is never mixed in or changed.
 */
class PulseGraph(val appContext: Context, val demo: Boolean = false) {
    val db: PulseDatabase =
        Room.databaseBuilder(appContext, PulseDatabase::class.java, if (demo) "pulse-demo.db" else "pulse.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build()
    val aiSettings = AiSettings(appContext)
    val moneyPrefs = MoneyPrefs(appContext, demo)
    val reminders = ReminderScheduler(appContext, demo)
    val captureRepository = CaptureRepository(
        db,
        onReminderDetected = { reminder ->
            if (reminder.confirmed) reminders.schedule(reminder)
            ReminderPrompt.show(appContext, reminder)
        },
        onBalanceSeen = moneyPrefs::setBank,
        isPaused = aiSettings::isPaused,
        onRecordSaved = { BudgetAlert.check(this) },
        autoConfirmSure = aiSettings::isAutoReminders,
        onTodoSuggested = { ReminderPrompt.showTodo(appContext, it) },
        smartReadingOn = { aiSettings.isSmartReading() && aiSettings.load().isSet },
        smartReader = { sender, text, at -> SmartReader.read(aiSettings.load().apiKey, sender, text, at) },
    )
}

class PulseApp : Application() {
    @Volatile private var current: PulseGraph? = null

    val graph: PulseGraph
        get() = current ?: synchronized(this) {
            current ?: PulseGraph(applicationContext, demoOn()).also { current = it }
        }

    private fun demoOn() = getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("demo", false)

    /** Swaps to the other database. The caller restarts the screen so it reads from the new one. */
    fun switchDemo(on: Boolean) {
        getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("demo", on).apply()
        val next = PulseGraph(applicationContext, on)
        synchronized(this) { current = next }
        if (on) {
            CoroutineScope(Dispatchers.IO).launch {
                DemoData.seedIfEmpty(next.db, next.moneyPrefs, System.currentTimeMillis())
            }
        }
    }
}
