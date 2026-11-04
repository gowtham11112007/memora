package com.pulse.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pulse.app.domain.CaptureDecision
import com.pulse.app.domain.Category
import com.pulse.app.domain.ExpenseExtractor
import com.pulse.app.domain.Insights
import com.pulse.app.domain.MoneyItem
import com.pulse.app.domain.MoneySummary
import com.pulse.app.domain.RecordType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DemoAndAccuracyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun newDb() = Room.inMemoryDatabaseBuilder(context, PulseDatabase::class.java).allowMainThreadQueries().build()

    @Test
    fun `demo data fills every screen once, with nothing in the future`() = runBlocking {
        val db = newDb()
        val prefs = MoneyPrefs(context, demo = true)
        val now = System.currentTimeMillis()
        DemoData.seedIfEmpty(db, prefs, now)
        val first = db.moneyRecordDao().all().size
        assertTrue(first >= 10)
        assertTrue(db.moneyRecordDao().all().all { it.occurredAt <= now })
        assertTrue(db.noteDao().all().isNotEmpty())
        assertTrue(db.reminderDao().all().any { !it.confirmed })
        assertTrue(prefs.state.value.budgetPaise > 0)
        DemoData.seedIfEmpty(db, prefs, now)
        assertEquals(first, db.moneyRecordDao().all().size)
        db.close()
    }

    @Test
    fun `demo money settings never touch the real ones`() {
        val real = MoneyPrefs(context)
        val demo = MoneyPrefs(context, demo = true)
        real.setBudget(100)
        demo.setBudget(999)
        assertEquals(100L, MoneyPrefs(context).state.value.budgetPaise)
    }

    @Test
    fun `pausing stops all reading`() = runBlocking {
        val db = newDb()
        db.appPermissionDao().upsert(AppPermission("gpay", "Google Pay", true))
        val repo = CaptureRepository(db, isPaused = { true })
        assertEquals(CaptureDecision.PAUSED, repo.capture("gpay", "Google Pay", "Paid ₹250 to Swiggy", 1))
        assertTrue(db.moneyRecordDao().all().isEmpty())
        db.close()
    }

    @Test
    fun `insights compare like with like and stay quiet without data`() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 4) // A Wednesday.
        fun at(d: LocalDate) = d.atTime(10, 0).toInstant(zone).toEpochMilli()
        fun spent(paise: Long, d: LocalDate) = MoneyItem(paise, "x", RecordType.EXPENSE, at(d))
        val items = listOf(
            spent(50000, today),
            spent(100000, today.minusWeeks(1)),
            spent(900000, today.minusWeeks(1).plusDays(2)), // Later last week, so it doesn't count.
        )
        assertEquals("You've spent 50% less than this time last week.", Insights.weekChange(items, today, zone))
        assertNull(Insights.weekChange(listOf(spent(100, today)), today, zone))
        assertEquals(
            "Food is your biggest spend this month, at ₹300.",
            Insights.topCategory(MoneySummary(30000, 0, mapOf(Category.FOOD to 30000L, Category.BILLS to 100L))),
        )
    }

    /**
     * Prints extractor accuracy for labelled_messages.tsv. Add real messages there, with personal
     * details changed, and read the score in the test report. Only that score is a real-world number.
     */
    @Test
    fun `report accuracy on labelled messages`() {
        val lines = javaClass.classLoader!!.getResourceAsStream("labelled_messages.tsv")!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        var correct = 0
        lines.forEach { line ->
            val parts = line.split("\t")
            val expected = parts.getOrElse(3) { "" }.trim().ifEmpty { null }
            val result = ExpenseExtractor.extract(parts[0].ifEmpty { null }, parts[1])
            val ok = if (parts[2] == "NONE") {
                result == null
            } else {
                result != null && result.type.name == parts[2].substringBefore(':') &&
                    result.amountPaise == parts[2].substringAfter(':').toLong() && result.merchant == expected
            }
            if (ok) correct++ else println("WRONG: ${parts[1]} -> $result")
        }
        println("EXTRACTOR ACCURACY: $correct of ${lines.size}")
        assertTrue(lines.isNotEmpty())
    }
}
