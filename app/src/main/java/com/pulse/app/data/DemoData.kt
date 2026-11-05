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
            Reminder(title = "Pay hostel rent", dueAt = now + 5 * DAY, confidence = 1.0, confirmed = true, source = DEMO, repeat = "MONTHLY"),
        )
        val todos = listOf(
            Todo(text = "Buy a birthday gift for Amma", createdAt = now - HOUR, source = DEMO),
            Todo(text = "Finish the hackathon slides", createdAt = now - 2 * HOUR, source = DEMO),
            Todo(text = "Back up my phone", done = true, createdAt = now - DAY, source = DEMO),
            Todo(text = "Please submit the signed form to the office", confirmed = false, createdAt = now - 3 * HOUR, source = "com.whatsapp"),
        )
        val people = listOf(
            ContactRule("priya", "Priya", starred = true),
            ContactRule("college offers", "College Offers", starred = false),
        )
        val activity = listOf(
            ActivityEntry(packageName = "com.google.android.apps.nbu.paisa.user", title = "Google Pay", outcome = "STORE", at = now - 5 * 60_000),
            ActivityEntry(packageName = "com.whatsapp", title = "Priya", outcome = "STORE", at = now - 40 * 60_000),
            ActivityEntry(packageName = "com.whatsapp", title = "College Offers", outcome = "MUTED", at = now - HOUR),
            ActivityEntry(packageName = "com.google.android.apps.messaging", title = "", outcome = "DROPPED_SENSITIVE", at = now - 2 * HOUR),
            ActivityEntry(packageName = "org.telegram.messenger", title = "Rahul", outcome = "NOTHING_USEFUL", at = now - 3 * HOUR),
            ActivityEntry(packageName = "com.phonepe.app", title = "PhonePe", outcome = "DUPLICATE", at = now - 5 * HOUR),
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
            todos.forEach { db.todoDao().insert(it) }
            people.forEach { db.contactRuleDao().upsert(it) }
            activity.forEach { db.activityDao().insert(it) }
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
