package com.pulse.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pulse.app.domain.CaptureDecision
import com.pulse.app.domain.RecordType
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

/** Real Room and real SQLite, running on the JVM through Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneOffset.UTC
    private lateinit var db: PulseDatabase
    private lateinit var repo: CaptureRepository
    private val scheduled = mutableListOf<Reminder>()
    private var clock = LocalDateTime.of(2026, 11, 4, 14, 0).toInstant(zone).toEpochMilli()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).allowMainThreadQueries().build()
        repo = CaptureRepository(db, zone, { scheduled += it }) { clock }
        runBlocking {
            db.appPermissionDao().upsert(AppPermission("gpay", "Google Pay", true))
            db.appPermissionDao().upsert(AppPermission(SMS, "Messages", true))
        }
    }

    @After
    fun tearDown() = db.close()

    private fun records() = runBlocking { db.moneyRecordDao().observeAll().first() }
    private fun raw() = runBlocking { db.rawEventDao().observeRecent(10).first() }
    private fun reminders() = runBlocking { db.reminderDao().all() }

    @Test
    fun `payment is stored masked and becomes a record`() = runBlocking {
        val decision = repo.capture("gpay", "Google Pay", "Paid ₹250.00 to Swiggy. UPI Ref No. 412345678901", clock)

        assertEquals(CaptureDecision.STORE, decision)
        assertEquals("Paid ₹250.00 to Swiggy. UPI Ref No. ••••8901", raw().single().text)
        val record = records().single()
        assertEquals(25000L, record.amountPaise)
        assertEquals("Swiggy", record.merchant)
        assertEquals(RecordType.EXPENSE, record.type)
    }

    @Test
    fun `text with nothing useful in it is not kept`() = runBlocking {
        val decision = repo.capture("gpay", "Google Pay", "Rate your experience with us", clock)
        assertEquals(CaptureDecision.NOTHING_USEFUL, decision)
        assertTrue(raw().isEmpty())
        assertTrue(records().isEmpty())
    }

    @Test
    fun `every message from an allowed app updates its last read time`() = runBlocking {
        repo.capture("gpay", "Google Pay", "Your order has shipped", clock)
        clock += 5
        repo.capture("gpay", "Google Pay", "Another note", clock)
        val seen = db.appSeenDao().observeAll().first().single()
        assertEquals(clock, seen.lastSeenAt)
        assertEquals(2L, seen.seenCount)
    }

    @Test
    fun `bank SMS counts as money but a personal chat from the same app does not`() = runBlocking {
        val bank = repo.capture(SMS, "AD-IOBCHN", "Your a/c no. XXXX4131 is debited for Rs.1.00 on 04-11-26", clock)
        assertEquals(CaptureDecision.STORE, bank)
        assertEquals(100L, records().single().amountPaise)

        repo.capture(SMS, "Kiran", "I paid 500 rupees for lunch, meeting tomorrow", clock)
        assertEquals(1, records().size)
        assertFalse("a date from a chat only asks first", reminders().single().confirmed)

        assertEquals(CaptureDecision.NOTHING_USEFUL, repo.capture(SMS, "Kiran", "Good morning, how are you", clock))
    }

    @Test
    fun `one payment reported by SMS, Google Pay and Paytm counts once, with the best name`() = runBlocking {
        db.appPermissionDao().upsert(AppPermission("paytm", "Paytm", true))
        val sms = "A/c XX1234 credited with Rs.500.00 on 04-11-26. UPI Ref 612345678901"
        assertEquals(CaptureDecision.STORE, repo.capture(SMS, "AD-HDFCBK", sms, clock))
        assertEquals(CaptureDecision.DUPLICATE, repo.capture("gpay", "Rahul Kumar", "Paid you ₹500", clock + 20_000))
        assertEquals(CaptureDecision.DUPLICATE, repo.capture("paytm", "Paytm", "Received ₹500 from Rahul", clock + 40_000))

        val record = records().single()
        assertEquals("Rahul Kumar", record.merchant)
        assertEquals("8901", record.reference)
        assertEquals(RecordType.INCOME, record.type)
    }

    @Test
    fun `the same amount to two different people from two apps counts twice`() = runBlocking {
        db.appPermissionDao().upsert(AppPermission("paytm", "Paytm", true))
        repo.capture("gpay", "Google Pay", "Paid ₹100 to Rahul Kumar", clock)
        repo.capture("paytm", "Paytm", "Paid ₹100 to Priya Sharma", clock + 60_000)
        assertEquals(2, records().size)
    }

    @Test
    fun `different bank references are different payments, even in the same minute`() = runBlocking {
        repo.capture(SMS, "AD-HDFCBK", "Rs.50 debited from A/c XX1 to Kiran. Ref 111111111111", clock)
        repo.capture(SMS, "AD-HDFCBK", "Rs.50 debited from A/c XX1 to Kiran. Ref 222222222222", clock + 30_000)
        assertEquals(2, records().size)
    }

    @Test
    fun `matching bank references are one payment, even with different names and a long gap`() = runBlocking {
        repo.capture("gpay", "Google Pay", "Paid ₹75 to Metro Rail. UPI Ref 412345678901", clock)
        val decision = repo.capture(SMS, "AD-SBIUPI", "A/C X1 debited by 75.0 trf to DMRC Refno 412345678901", clock + 20 * 60_000)
        assertEquals(CaptureDecision.DUPLICATE, decision)
        assertEquals(1, records().size)
    }

    @Test
    fun `an SMS title is never taken for the person who paid`() = runBlocking {
        repo.capture(SMS, "HDFC Chennai", "A/c XX1234 credited with Rs.900.00", clock)
        assertEquals(null, records().single().merchant)
    }

    @Test
    fun `the same payment within 2 minutes is ignored, later it counts`() = runBlocking {
        val message = "Paid ₹250 to Swiggy"
        assertEquals(CaptureDecision.STORE, repo.capture("gpay", "Google Pay", message, clock))
        assertEquals(CaptureDecision.DUPLICATE, repo.capture("gpay", "Google Pay", message, clock + 60_000))
        assertEquals(1, records().size)
        assertEquals(CaptureDecision.STORE, repo.capture("gpay", "Google Pay", message, clock + 180_000))
        assertEquals(2, records().size)
    }

    @Test
    fun `a date in a message asks first and is saved unconfirmed`() = runBlocking {
        val decision = repo.capture("gpay", "Airtel", "Your bill is due on 15 Nov", clock)

        assertEquals(CaptureDecision.STORE, decision)
        val reminder = reminders().single()
        assertFalse(reminder.confirmed)
        assertEquals("Your bill is due on 15 Nov", reminder.title)
        assertEquals(listOf(reminder), scheduled)
        assertEquals("BILL", db.noteDao().all().single().kind)
    }

    @Test
    fun `repeats of the same reminder and note are ignored`() = runBlocking {
        repo.capture("gpay", "College", "Assignment due on Friday", clock)
        repo.capture("gpay", "College", "Assignment due on Friday", clock + 1000)
        assertEquals(1, reminders().size)
        assertEquals(1, scheduled.size)
        assertEquals(1, db.noteDao().all().size)
    }

    @Test
    fun `a delivery message becomes a short masked note`() = runBlocking {
        val decision = repo.capture("gpay", "Amazon", "Your order has shipped. Tracking 412345678901 and much more", clock)
        assertEquals(CaptureDecision.STORE, decision)
        val note = db.noteDao().all().single()
        assertEquals("DELIVERY", note.kind)
        assertEquals("Your order has shipped", note.text)
    }

    @Test
    fun `useful SMS become notes from anyone, and adverts never do`() = runBlocking {
        assertEquals(CaptureDecision.STORE, repo.capture(SMS, "Kiran", "My order has shipped", clock))
        assertEquals(CaptureDecision.STORE, repo.capture(SMS, "AD-AMAZON", "Your parcel is arriving", clock))
        assertEquals(2, db.noteDao().all().size)
        assertEquals(CaptureDecision.NOTHING_USEFUL, repo.capture(SMS, "AD-SHOP", "Sale on all delivery items, 50% off", clock))
        assertEquals(2, db.noteDao().all().size)
    }

    @Test
    fun `a payment is a record, not also a note`() = runBlocking {
        repo.capture("gpay", "Airtel", "Paid ₹299 for your recharge to Airtel", clock)
        assertEquals(1, records().size)
        assertTrue(db.noteDao().all().isEmpty())
    }

    @Test
    fun `a balance in a bank alert is reported`() = runBlocking {
        val seen = mutableListOf<Long>()
        val withBalance = CaptureRepository(db, zone, {}, { paise, _ -> seen += paise }) { clock }
        withBalance.capture("gpay", "Bank", "Rs.500 debited from A/c XX12. Avl Bal Rs 7,000", clock)
        assertEquals(listOf(700000L), seen)
    }

    @Test
    fun `OTP stores no text and only adds to the blocked count`() = runBlocking {
        val decision = repo.capture("gpay", "Bank", "482913 is your OTP", clock)

        assertEquals(CaptureDecision.DROPPED_SENSITIVE, decision)
        assertTrue(raw().isEmpty())
        assertTrue(records().isEmpty())
        assertEquals(1, db.dropLogDao().observeCountSince(0).first())
    }

    @Test
    fun `app that is not allowed leaves no trace`() = runBlocking {
        val decision = repo.capture("other", "Bank", "482913 is your OTP", clock)

        assertEquals(CaptureDecision.IGNORED_NOT_ALLOWED, decision)
        assertTrue(raw().isEmpty())
        assertEquals(0, db.dropLogDao().observeCountSince(0).first())
        assertTrue(db.appSeenDao().observeAll().first().isEmpty())
    }

    @Test
    fun `raw text expires after 24 hours but the record stays`() = runBlocking {
        repo.capture("gpay", "Google Pay", "Paid ₹250 to Swiggy", clock)
        clock += 25L * 60 * 60 * 1000
        repo.capture("gpay", "Google Pay", "Paid ₹99 to Zomato", clock)

        assertEquals(listOf("Paid ₹99 to Zomato"), raw().map { it.text })
        assertEquals(2, records().size)
    }

    /** Room refuses to open if a migration's SQL differs from the entities, so this proves they match. */
    @Test
    fun `updating from version 1 keeps data and adds the new tables`() = runBlocking {
        val file = context.getDatabasePath("migration-test.db")
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE `raw_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `capturedAt` INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE `app_permission` (`packageName` TEXT NOT NULL, `label` TEXT NOT NULL, `allowed` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
            old.execSQL("INSERT INTO app_permission VALUES ('gpay', 'Google Pay', 1)")
            old.version = 1
        }
        openAndCheck(file.absolutePath)
    }

    /** This is the update your phone will run, so it must keep the records you already have. */
    @Test
    fun `updating from version 2 keeps records`() = runBlocking {
        val file = context.getDatabasePath("migration-test-2.db")
        file.parentFile?.mkdirs()
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            old.execSQL("CREATE TABLE `raw_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `title` TEXT NOT NULL, `text` TEXT NOT NULL, `postedAt` INTEGER NOT NULL, `capturedAt` INTEGER NOT NULL)")
            old.execSQL("CREATE TABLE `app_permission` (`packageName` TEXT NOT NULL, `label` TEXT NOT NULL, `allowed` INTEGER NOT NULL, PRIMARY KEY(`packageName`))")
            old.execSQL("CREATE TABLE `record` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `packageName` TEXT NOT NULL, `amountPaise` INTEGER NOT NULL, `merchant` TEXT, `type` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL)")
            old.execSQL("CREATE INDEX `index_record_occurredAt` ON `record` (`occurredAt`)")
            old.execSQL("CREATE TABLE `drop_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `droppedAt` INTEGER NOT NULL)")
            old.execSQL("INSERT INTO app_permission VALUES ('gpay', 'Google Pay', 1)")
            old.execSQL("INSERT INTO record (packageName, amountPaise, merchant, type, occurredAt) VALUES ('gpay', 100, 'Kiran', 'INCOME', 5)")
            old.version = 2
        }
        val migrated = open(file.absolutePath)
        try {
            assertEquals("Kiran", migrated.moneyRecordDao().observeAll().first().single().merchant)
            migrated.reminderDao().insert(Reminder(title = "x", dueAt = 1, confidence = 1.0, confirmed = true, source = "manual"))
            assertEquals(1, migrated.reminderDao().all().size)
            migrated.noteDao().insert(Note(text = "n", kind = "NOTE", createdAt = 1, source = "manual"))
            assertEquals(1, migrated.noteDao().all().size)
            assertEquals("NONE", migrated.reminderDao().all().single().repeat)
            migrated.todoDao().insert(Todo(text = "t", createdAt = 1, source = "manual"))
            migrated.contactRuleDao().upsert(ContactRule("priya", "Priya", true))
            migrated.activityDao().insert(ActivityEntry(packageName = "gpay", title = "x", outcome = "STORE", at = 1))
            assertEquals(1, migrated.activityDao().observeRecent().first().size)
        } finally {
            migrated.close()
        }
    }

    private fun open(path: String) = Room.databaseBuilder(context, PulseDatabase::class.java, path)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
        .allowMainThreadQueries()
        .build()

    private suspend fun openAndCheck(path: String) {
        val migrated = open(path)
        try {
            assertEquals(true, migrated.appPermissionDao().isAllowed("gpay"))
            migrated.moneyRecordDao().insert(MoneyRecord(0, "gpay", 100, "X", RecordType.EXPENSE, 1))
            assertEquals(1, migrated.moneyRecordDao().observeAll().first().size)
        } finally {
            migrated.close()
        }
    }

    private companion object {
        const val SMS = "com.google.android.apps.messaging"
    }
}
