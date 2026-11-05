package com.pulse.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pulse.app.domain.CaptureDecision
import com.pulse.app.service.NotificationActions
import com.pulse.app.service.ReminderPrompt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Star and mute rules, the bell's log, predicted to-dos, automatic reminders and notification buttons. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeopleAndActionsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneOffset.UTC
    private val clock = LocalDateTime.of(2026, 11, 4, 14, 0).toInstant(zone).toEpochMilli()
    private val day = 24L * 60 * 60 * 1000
    private lateinit var db: PulseDatabase
    private val prompted = mutableListOf<Reminder>()
    private val suggested = mutableListOf<Todo>()
    private var auto = false

    private val scheduled = mutableListOf<Reminder>()
    private val cancelled = mutableListOf<Long>()
    private val snoozed = mutableListOf<Pair<Reminder, Long>>()

    private lateinit var repo: CaptureRepository
    private lateinit var actions: NotificationActions

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(
            db,
            zone,
            onReminderDetected = { prompted += it },
            autoConfirmSure = { auto },
            onTodoSuggested = { suggested += it },
            now = { clock },
        )
        actions = NotificationActions(db, { scheduled += it }, { cancelled += it }, { r, at -> snoozed += r to at }, zone)
        runBlocking {
            listOf(WHATSAPP, SMS, "gmail", "gpay").forEach { db.appPermissionDao().upsert(AppPermission(it, it, true)) }
        }
    }

    @After
    fun tearDown() = db.close()

    private fun log() = runBlocking { db.activityDao().observeRecent().first() }

    private fun star(name: String) = runBlocking { db.contactRuleDao().upsert(ContactRule(name.lowercase(), name, true)) }

    private fun mute(name: String) = runBlocking { db.contactRuleDao().upsert(ContactRule(name.lowercase(), name, false)) }

    @Test
    fun `a muted contact is skipped and nothing about the message is kept`() = runBlocking {
        mute("Rahul")
        val decision = repo.capture(WHATSAPP, "Rahul", "Don't forget the exam on 10 Nov at 9 am", clock)

        assertEquals(CaptureDecision.MUTED, decision)
        assertTrue(db.rawEventDao().observeRecent(10).first().isEmpty())
        assertTrue(db.reminderDao().all().isEmpty())
        assertTrue(db.todoDao().all().isEmpty())
        assertEquals("MUTED", log().single().outcome)
    }

    @Test
    fun `a muted name does not stop a bank alert from a payment app`() = runBlocking {
        mute("Google Pay")
        assertEquals(CaptureDecision.STORE, repo.capture("gpay", "Google Pay", "Paid ₹250.00 to Swiggy", clock))
        assertEquals(1, db.moneyRecordDao().all().size)
    }

    @Test
    fun `an ordinary chat is read but not saved`() = runBlocking {
        assertEquals(CaptureDecision.NOTHING_USEFUL, repo.capture(WHATSAPP, "Rahul", "Good morning, how was the trip yesterday?", clock))
        assertTrue(db.noteDao().all().isEmpty())
        assertTrue(db.rawEventDao().observeRecent(10).first().isEmpty())
    }

    @Test
    fun `a starred contact's message becomes a People note, but short ones don't`() = runBlocking {
        star("Priya")
        assertEquals(CaptureDecision.NOTHING_USEFUL, repo.capture(WHATSAPP, "Priya", "ok", clock))
        assertEquals(CaptureDecision.STORE, repo.capture(WHATSAPP, "Priya", "The hall booking moved to the second floor", clock))

        val note = db.noteDao().all().single()
        assertEquals("PEOPLE", note.kind)
        assertEquals("From Priya: The hall booking moved to the second floor", note.text)
    }

    @Test
    fun `a code from a starred contact is still dropped and its sender hidden`() = runBlocking {
        star("Priya")
        assertEquals(CaptureDecision.DROPPED_SENSITIVE, repo.capture(WHATSAPP, "Priya", "482913 is your OTP for login", clock))
        assertTrue(db.noteDao().all().isEmpty())
        assertEquals("", log().single().title)
    }

    @Test
    fun `a friend saying they paid is not my expense, even if starred`() = runBlocking {
        star("Priya")
        repo.capture(WHATSAPP, "Priya", "Paid ₹500 to Rahul for the tickets yesterday", clock)
        assertTrue(db.moneyRecordDao().all().isEmpty())
    }

    @Test
    fun `the log holds only the sender, and skips apps that aren't allowed`() = runBlocking {
        repo.capture(WHATSAPP, "Rahul", "My secret plan is here", clock)
        repo.capture("not.allowed", "Someone", "Paid ₹250.00 to Swiggy", clock)

        val entry = log().single()
        assertEquals(WHATSAPP, entry.packageName)
        assertEquals("Rahul", entry.title)
        assertEquals("NOTHING_USEFUL", entry.outcome)
    }

    @Test
    fun `the log forgets entries after 7 days`() = runBlocking {
        db.activityDao().insert(ActivityEntry(packageName = WHATSAPP, title = "old", outcome = "STORE", at = clock - 8 * day))
        repo.capture(WHATSAPP, "Rahul", "hello there", clock)
        assertEquals(listOf("Rahul"), log().map { it.title })
    }

    @Test
    fun `a request in a chat offers a to-do and asks first`() = runBlocking {
        assertEquals(CaptureDecision.STORE, repo.capture(WHATSAPP, "Rahul", "Please send the signed form to the office", clock))

        val todo = db.todoDao().all().single()
        assertFalse(todo.confirmed)
        assertEquals("Please send the signed form to the office", todo.text)
        assertEquals(listOf(todo), suggested)

        repo.capture(WHATSAPP, "Rahul", "Please send the signed form to the office", clock)
        assertEquals("the same message doesn't ask twice", 1, suggested.size)
    }

    @Test
    fun `a request in a personal SMS offers a to-do too`() = runBlocking {
        assertEquals(CaptureDecision.STORE, repo.capture(SMS, "Rahul", "Please send the signed form", clock))
        assertEquals(1, suggested.size)
    }

    @Test
    fun `the real delivery message with typos is understood`() = runBlocking {
        val message = "Heyy gowtham  your delivery is arriving Tommorow morning 9.00am.your order is out of delivery"
        assertEquals(CaptureDecision.STORE, repo.capture(WHATSAPP, "Rahul (2 messages)", message, clock))

        val note = db.noteDao().all().single()
        assertEquals("DELIVERY", note.kind)
        assertEquals("Heyy gowtham  your delivery is arriving Tommorow morning 9.00am", note.text)

        val reminder = prompted.single()
        assertFalse("a chat always asks first", reminder.confirmed)
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0).toInstant(zone).toEpochMilli(), reminder.dueAt)
        assertEquals("Heyy gowtham  your delivery is arriving Tommorow morning 9.00am", reminder.title)
        assertEquals("Rahul", log().single().title)
        assertTrue("a delivery isn't money", db.moneyRecordDao().all().isEmpty())
    }

    @Test
    fun `chats with useful words reach Second Brain, chatter and adverts don't`() = runBlocking {
        repo.capture(WHATSAPP, "Rahul", "My parcel from Amazon has come", clock)
        repo.capture(WHATSAPP, "Rahul", "Viva is next week", clock)
        repo.capture(WHATSAPP, "Rahul", "Good morning machi, what's up", clock)
        repo.capture(WHATSAPP, "Shop", "Big sale on delivery today, 50% off", clock)
        assertEquals(listOf("DELIVERY", "EVENT"), db.noteDao().all().map { it.kind }.sorted())
    }

    @Test
    fun `a chat with a date asks about a reminder and never sets it alone`() = runBlocking {
        auto = true
        repo.capture(WHATSAPP, "Rahul", "Team meeting on 10 Nov at 5 pm", clock)
        assertFalse(prompted.single().confirmed)
    }

    @Test
    fun `automatic reminders set a clear date from a non-chat app`() = runBlocking {
        repo.capture("gmail", "Rent", "Rent due on 10 Nov 2026", clock)
        assertFalse("off by default", prompted.single().confirmed)

        auto = true
        repo.capture("gmail", "Fees", "College fees due on 12 Nov 2026", clock)
        assertTrue(prompted.last().confirmed)
    }

    @Test
    fun `an exact alarm is set for the due time, and can be cancelled`() {
        val alarms = com.pulse.app.service.ExactAlarms(context)
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val due = System.currentTimeMillis() + day

        alarms.set(7, due)
        assertEquals(due, shadow.peekNextScheduledAlarm()?.triggerAtMs)

        alarms.cancel(7)
        assertEquals(null, shadow.peekNextScheduledAlarm())
    }

    @Test
    fun `a snooze alarm doesn't replace the normal one`() {
        val alarms = com.pulse.app.service.ExactAlarms(context)
        val manager = context.getSystemService(android.app.AlarmManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val due = System.currentTimeMillis() + day

        alarms.set(7, due)
        alarms.set(7, due + 60_000, once = true)
        assertEquals(2, shadow.scheduledAlarms.size)
    }

    private suspend fun reminder(repeat: String = "NONE"): Reminder {
        val r = Reminder(title = "Pay rent", dueAt = clock, confidence = 1.0, confirmed = false, source = "gmail", repeat = repeat)
        return r.copy(id = db.reminderDao().insert(r))
    }

    @Test
    fun `yes on a popup confirms and schedules`() = runBlocking {
        val r = reminder()
        actions.run(ReminderPrompt.ACTION_YES, r.id, clock)
        assertTrue(db.reminderDao().byId(r.id)!!.confirmed)
        assertEquals(r.id, scheduled.single().id)
    }

    @Test
    fun `no on a popup deletes the reminder`() = runBlocking {
        val r = reminder()
        actions.run(ReminderPrompt.ACTION_NO, r.id, clock)
        assertEquals(null, db.reminderDao().byId(r.id))
        assertEquals(listOf(r.id), cancelled)
    }

    @Test
    fun `a reminder can become a to-do from its popup`() = runBlocking {
        val r = reminder()
        actions.run(ReminderPrompt.ACTION_TO_TODO, r.id, clock)
        assertEquals(null, db.reminderDao().byId(r.id))
        val todo = db.todoDao().all().single()
        assertEquals("Pay rent", todo.text)
        assertTrue(todo.confirmed)
    }

    @Test
    fun `snooze and tomorrow move a one-off reminder`() = runBlocking {
        val r = reminder()
        actions.run(ReminderPrompt.ACTION_SNOOZE, r.id, clock)
        assertEquals(clock + 10 * 60_000, db.reminderDao().byId(r.id)!!.dueAt)

        actions.run(ReminderPrompt.ACTION_TOMORROW, r.id, clock)
        assertEquals(clock + day, db.reminderDao().byId(r.id)!!.dueAt)
        assertEquals(2, scheduled.size)
    }

    @Test
    fun `snooze leaves a repeating reminder's schedule alone`() = runBlocking {
        val r = reminder("WEEKLY")
        actions.run(ReminderPrompt.ACTION_SNOOZE, r.id, clock)
        assertEquals(clock, db.reminderDao().byId(r.id)!!.dueAt)
        assertEquals(clock + 10 * 60_000, snoozed.single().second)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `done finishes a one-off reminder but not a repeating one`() = runBlocking {
        val once = reminder()
        val weekly = reminder("WEEKLY")
        actions.run(ReminderPrompt.ACTION_DONE, once.id, clock)
        actions.run(ReminderPrompt.ACTION_DONE, weekly.id, clock)
        assertTrue(db.reminderDao().byId(once.id)!!.done)
        assertFalse(db.reminderDao().byId(weekly.id)!!.done)
    }

    @Test
    fun `to-do buttons add, remind or remove`() = runBlocking {
        suspend fun todo(text: String) = db.todoDao().insert(Todo(text = text, confirmed = false, createdAt = clock, source = WHATSAPP))
        val add = todo("add")
        val remind = todo("remind")
        val no = todo("no")

        actions.run(ReminderPrompt.ACTION_TODO_YES, add, clock)
        actions.run(ReminderPrompt.ACTION_TODO_REMIND, remind, clock)
        actions.run(ReminderPrompt.ACTION_TODO_NO, no, clock)

        assertTrue(db.todoDao().byId(add)!!.confirmed)
        assertEquals(null, db.todoDao().byId(no))
        assertEquals(null, db.todoDao().byId(remind))
        val set = scheduled.single()
        assertEquals("remind", set.title)
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0).toInstant(zone).toEpochMilli(), set.dueAt)
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val SMS = "com.google.android.apps.messaging"
    }
}
