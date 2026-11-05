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

    private val sentToGemini = mutableListOf<Pair<String, String>>()
    private var geminiReply: AiReading? = null

    private fun smartRepo(on: Boolean = true) = CaptureRepository(
        db,
        zone,
        onReminderDetected = { prompted += it },
        autoConfirmSure = { auto },
        onTodoSuggested = { suggested += it },
        smartReadingOn = { on },
        smartReader = { sender, text, _ ->
            sentToGemini += sender to text
            geminiReply
        },
        now = { clock },
    )

    private val tomorrowMorning = LocalDateTime.of(2026, 11, 5, 9, 0)

    @Test
    fun `smart reading understands what the word rules miss, and still asks first`() = runBlocking {
        auto = true
        geminiReply = AiReading(com.pulse.app.domain.NoteKind.EVENT, "Dinner at Priya's place tomorrow", tomorrowMorning, null)
        val message = "Priya's place nalaiku dinner, vandhudu"
        assertEquals(CaptureDecision.STORE, smartRepo().capture("gmail", "Priya", message, clock))

        assertEquals("EVENT", db.noteDao().all().single().kind)
        assertEquals("Dinner at Priya's place tomorrow", db.noteDao().all().single().text)
        assertEquals("Dinner at Priya's place tomorrow", prompted.single().title)
        assertFalse("never set alone, even with Automatic reminders on", prompted.single().confirmed)
    }

    @Test
    fun `the AI can't lose a clear date or a useful message the rules found`() = runBlocking {
        geminiReply = AiReading(null, null, null, null)
        assertEquals(CaptureDecision.STORE, smartRepo().capture(WHATSAPP, "Rahul", "Exam tomorrow at 9 am", clock))
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0).toInstant(zone).toEpochMilli(), prompted.single().dueAt)
        assertEquals("EVENT", db.noteDao().all().single().kind)

        geminiReply = AiReading(com.pulse.app.domain.NoteKind.NOTE, "A short summary", null, null)
        smartRepo().capture(WHATSAPP, "Rahul", "Viva on 10 Nov at 10 am", clock)
        assertEquals("a reading with no date keeps the rule's date", 2, prompted.size)
    }

    @Test
    fun `smart reading can say a message is not useful, and can offer a to-do`() = runBlocking {
        geminiReply = AiReading(null, null, null, null)
        assertEquals(CaptureDecision.NOTHING_USEFUL, smartRepo().capture(WHATSAPP, "Rahul", "That joke was so funny machi", clock))
        assertTrue(db.noteDao().all().isEmpty())

        geminiReply = AiReading(com.pulse.app.domain.NoteKind.NOTE, "Rahul needs the charger", null, "Bring Rahul's charger")
        assertEquals(CaptureDecision.STORE, smartRepo().capture(WHATSAPP, "Rahul", "machi charger konduvaa", clock))
        assertEquals("Bring Rahul's charger", suggested.single().text)
        assertFalse(suggested.single().confirmed)
    }

    @Test
    fun `only masked text is sent`() = runBlocking {
        geminiReply = AiReading(null, null, null, null)
        smartRepo().capture(WHATSAPP, "Rahul", "Call me on 9876543210 about the plan", clock)
        assertEquals("Rahul" to "Call me on ••••3210 about the plan", sentToGemini.single())
    }

    @Test
    fun `some things are never sent to Gemini`() = runBlocking {
        mute("Muted Person")
        val repo = smartRepo()
        repo.capture("not.allowed", "Someone", "A secret plan for the weekend", clock)
        repo.capture(WHATSAPP, "Rahul", "482913 is your OTP for login", clock)
        repo.capture(WHATSAPP, "Muted Person", "Meet me at the usual place", clock)
        repo.capture("gpay", "Google Pay", "Paid ₹250.00 to Swiggy", clock)
        repo.capture(SMS, "AD-HDFCBK", "Your a/c XX1234 is debited for Rs.100.00", clock)
        repo.capture(WHATSAPP, "Rahul", "ok", clock)
        smartRepo(on = false).capture(WHATSAPP, "Rahul", "Meet me at the usual place", clock)
        assertEquals(emptyList<Pair<String, String>>(), sentToGemini)
        assertEquals("money still counts", 2, db.moneyRecordDao().all().size)
    }

    @Test
    fun `a busy group chat can't use up the AI quota`() = runBlocking {
        geminiReply = AiReading(null, null, null, null)
        val repo = smartRepo()
        repeat(15) { repo.capture(WHATSAPP, "Family group", "Message number $it from the group", clock) }
        assertEquals(10, sentToGemini.size)
    }

    @Test
    fun `very long messages are cut before sending`() = runBlocking {
        geminiReply = AiReading(null, null, null, null)
        smartRepo().capture(WHATSAPP, "Rahul", "word ".repeat(1000), clock)
        assertEquals(1000, sentToGemini.single().second.length)
    }

    @Test
    fun `suggestions remember who sent them, and the bell says what each became`() = runBlocking {
        repo.capture(WHATSAPP, "Rahul (2 messages)", "Your parcel is arriving tomorrow at 5 pm", clock)
        assertEquals("Rahul", db.reminderDao().all().single().origin)
        val made = log().single().detail
        assertTrue(made, made.contains("Reminder: Your parcel") && made.contains("Saved in Deliveries"))

        repo.capture(WHATSAPP, "Priya", "Please send the form to the office", clock)
        assertEquals("Priya", db.todoDao().all().single().origin)

        repo.capture("gpay", "Google Pay", "Paid ₹250.00 to Swiggy", clock + 1000)
        assertTrue(log().any { it.detail == "₹250 to Swiggy" })
        repo.capture(WHATSAPP, "Arun", "Good morning machi", clock + 2000)
        assertEquals("nothing useful leaves no text", "", log().first { it.title == "Arun" }.detail)
    }

    @Test
    fun `chats save, reopen by conversation, trim and delete`() = runBlocking {
        val chats = db.chatDao()
        repeat(6) { chats.insert(ChatEntry(conversation = (it % 2).toLong(), question = "q$it", answer = "a$it", source = "AI", at = it.toLong())) }
        assertEquals(listOf("q0", "q2", "q4"), chats.conversation(0).map { it.question })
        assertEquals("q5", chats.latest()?.question)
        chats.trim(4)
        assertEquals(listOf("q2", "q3", "q4", "q5"), chats.all().map { it.question })
        val removed = chats.conversation(1)
        chats.deleteConversation(1)
        assertEquals(listOf("q2", "q4"), chats.all().map { it.question })
        chats.insertAll(removed)
        assertEquals("undo brings it back", 4, chats.all().size)
        chats.deleteAll()
        assertEquals(null, chats.latest())
    }

    @Test
    fun `when Gemini fails, the word rules take over`() = runBlocking {
        geminiReply = null
        assertEquals(CaptureDecision.STORE, smartRepo().capture(WHATSAPP, "Rahul", "Your parcel is arriving tomorrow", clock))
        assertEquals("DELIVERY", db.noteDao().all().single().kind)
        assertEquals(1, prompted.size)
    }

    @Test
    fun `a reposted notification is only sent once`() = runBlocking {
        geminiReply = AiReading(com.pulse.app.domain.NoteKind.NOTE, "A plan", null, null)
        val repo = smartRepo()
        repo.capture(WHATSAPP, "Rahul", "Same message posted again", clock)
        repo.capture(WHATSAPP, "Rahul", "Same message posted again", clock)
        assertEquals(1, sentToGemini.size)
        assertEquals(1, db.noteDao().all().size)
    }

    @Test
    fun `Gemini's reply is checked before anything uses it`() {
        val now = LocalDateTime.of(2026, 11, 4, 14, 0)
        val parse = { reply: String -> SmartReader.parse(reply, now) }
        val good = parse("""```json
            {"useful":true,"kind":"DELIVERY","summary":"Parcel arrives tomorrow","remind_at":"2026-11-05T09:00","todo":null}
            ```""")
        assertEquals(AiReading(com.pulse.app.domain.NoteKind.DELIVERY, "Parcel arrives tomorrow", tomorrowMorning, null), good)
        assertEquals("not useful", AiReading(null, null, null, null), parse("""{"useful":false,"summary":"x"}"""))
        assertEquals("not JSON", null, parse("Sorry, I can't help"))
        assertEquals("no answer", null, parse("""{"summary":"x"}"""))
        assertEquals("unknown kinds become notes", com.pulse.app.domain.NoteKind.NOTE, parse("""{"useful":true,"kind":"HACK"}""")?.kind)
        assertEquals("People is only for starred contacts", com.pulse.app.domain.NoteKind.NOTE, parse("""{"useful":true,"kind":"PEOPLE"}""")?.kind)
        assertEquals("past dates are dropped", null, parse("""{"useful":true,"remind_at":"2020-01-01T09:00"}""")?.remindAt)
        assertEquals("far-off dates are dropped", null, parse("""{"useful":true,"remind_at":"2099-01-01T09:00"}""")?.remindAt)
        assertEquals("bad dates are dropped", null, parse("""{"useful":true,"remind_at":"soon"}""")?.remindAt)
        assertEquals("the word null isn't text", null, parse("""{"useful":true,"todo":"null"}""")?.todo)
        assertEquals("codes are dropped", null, parse("""{"useful":true,"summary":"Your OTP is 482913"}""")?.summary)
        assertEquals("long lines are cut", 120, parse("""{"useful":true,"summary":"${"a".repeat(300)}"}""")?.summary?.length)
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
