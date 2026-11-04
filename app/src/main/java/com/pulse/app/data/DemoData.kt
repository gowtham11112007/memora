package com.pulse.app.data

import androidx.room.withTransaction
import com.pulse.app.domain.NoteKind
import com.pulse.app.domain.RecordType
import java.time.LocalDate
import java.time.ZoneId

/** Sample data for showing the app to people. It lives in its own database, away from real data. */
object DemoData {
    private const val DEMO = "demo"
    private const val DAY = 24L * 60 * 60 * 1000
    private const val HOUR = 60L * 60 * 1000

    suspend fun seedIfEmpty(db: PulseDatabase, prefs: MoneyPrefs, now: Long, zone: ZoneId = ZoneId.systemDefault()) {
        if (db.moneyRecordDao().all().isNotEmpty()) return
        val today = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

        fun spent(daysAgo: Int, name: String, rupees: Long) =
            MoneyRecord(0, DEMO, rupees * 100, name, RecordType.EXPENSE, timeOn(today, daysAgo, now))

        fun got(daysAgo: Int, name: String, rupees: Long) =
            MoneyRecord(0, DEMO, rupees * 100, name, RecordType.INCOME, timeOn(today, daysAgo, now))

        val records = listOf(
            spent(0, "Swiggy", 250), spent(0, "Uber India", 180), got(0, "Rahul Kumar", 500),
            spent(1, "Metro Rail", 45), spent(1, "Zomato", 340), spent(2, "Amazon", 899),
            spent(3, "Ramesh Tea Stall", 60), spent(3, "Airtel Recharge", 299), got(4, "Acme Technologies", 25000),
            spent(5, "Flipkart", 1499), spent(6, "Domino's Pizza", 520), spent(8, "Uber India", 240),
            spent(9, "Reliance Fresh", 1150), spent(10, "Swiggy", 420), spent(11, "Netflix", 199),
            spent(13, "Electricity Bill", 1240), spent(31, "Airtel Recharge", 299), spent(41, "Netflix", 199),
        )
        val notes = listOf(
            Note(text = "My Wi-Fi router is in the hall cupboard", kind = NoteKind.NOTE.name, createdAt = now - HOUR, pinned = true, source = DEMO),
            Note(text = "Your electricity bill is due on the 15th", kind = NoteKind.BILL.name, createdAt = now - 2 * HOUR, source = DEMO),
            Note(text = "Your order has shipped and arrives tomorrow", kind = NoteKind.DELIVERY.name, createdAt = now - 5 * HOUR, source = DEMO),
            Note(text = "Booking confirmed for train 12601, seat B2-34", kind = NoteKind.TICKET.name, createdAt = now - DAY, source = DEMO),
            Note(text = "Project review meeting with the team", kind = NoteKind.EVENT.name, createdAt = now - 2 * DAY, source = DEMO),
        )
        val reminders = listOf(
            Reminder(title = "Pay electricity bill", dueAt = now + 2 * DAY, confidence = 0.9, confirmed = true, source = DEMO),
            Reminder(title = "Submit lab record", dueAt = now + DAY, confidence = 1.0, confirmed = true, source = DEMO),
            Reminder(title = "Renew insurance", dueAt = now + 3 * DAY, confidence = 0.7, confirmed = false, source = DEMO),
            Reminder(title = "Call the bank", dueAt = now + HOUR, confidence = 1.0, confirmed = true, done = true, source = DEMO),
        )
        val apps = listOf(
            "com.google.android.apps.messaging" to "Messages",
            "com.google.android.apps.nbu.paisa.user" to "Google Pay",
            "com.phonepe.app" to "PhonePe",
            "net.one97.paytm" to "Paytm",
        )
        db.withTransaction {
            records.forEach { db.moneyRecordDao().insert(it) }
            notes.forEach { db.noteDao().insert(it) }
            reminders.forEach { db.reminderDao().insert(it) }
            repeat(3) { db.dropLogDao().insert(DropLog(droppedAt = now - it * DAY)) }
            apps.forEach { (pkg, label) -> db.appPermissionDao().upsert(AppPermission(pkg, label, true)) }
        }
        prefs.setOpening(2_000_000L, today - 14 * DAY)
        prefs.setBudget(1_500_000L)
    }

    /** Spreads records through the day, without ever putting one in the future. */
    private fun timeOn(today: Long, daysAgo: Int, now: Long): Long {
        val at = today - daysAgo * DAY + (9 + daysAgo % 8) * HOUR
        return minOf(at, now - (daysAgo + 1) * 60_000)
    }
}
