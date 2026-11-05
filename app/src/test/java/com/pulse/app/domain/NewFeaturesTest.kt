package com.pulse.app.domain

import com.pulse.app.domain.RecordType.EXPENSE
import com.pulse.app.domain.RecordType.INCOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class DeadlineExtractorTest {
    // A Wednesday.
    private val now = LocalDateTime.of(2026, 11, 4, 14, 0)

    private fun due(message: String, title: String? = null) = DeadlineExtractor.extract(title, message, now)

    @Test
    fun `reads absolute dates`() {
        assertEquals(LocalDateTime.of(2026, 11, 15, 9, 0), due("Electricity bill of Rs 1,240 is due on 15 Nov")?.dueAt)
        assertEquals(LocalDateTime.of(2026, 12, 5, 9, 0), due("Fee payment deadline: 5th December 2026")?.dueAt)
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 0), due("Your premium is due on Nov 20")?.dueAt)
        assertEquals(LocalDateTime.of(2026, 11, 12, 9, 0), due("Last date to submit is 12/11/2026")?.dueAt)
        assertEquals(LocalDateTime.of(2026, 11, 12, 9, 0), due("Last date to submit is 12-11-26")?.dueAt)
    }

    @Test
    fun `reads relative dates and times`() {
        assertEquals(LocalDateTime.of(2026, 11, 5, 17, 30), due("Project submit deadline tomorrow at 5:30 PM")?.dueAt)
        assertEquals(LocalDateTime.of(2026, 11, 6, 9, 0), due("Lab exam day after tomorrow")?.dueAt)
        assertEquals(LocalDateTime.of(2026, 11, 7, 9, 0), due("Your plan expires in 3 days")?.dueAt)
        assertEquals(LocalDateTime.of(2026, 11, 4, 18, 0), due("Your meeting is today at 18:00")?.dueAt)
    }

    @Test
    fun `sure dates need no confirmation, guesses do`() {
        assertFalse(due("Bill is due on 15 Nov")!!.needsConfirmation)
        assertFalse(due("Exam tomorrow")!!.needsConfirmation)
        assertTrue("weekdays are a guess", due("Assignment due on Friday")!!.needsConfirmation)
        assertTrue("a passed date with no year is a guess", due("Bill is due on 5 Feb")!!.needsConfirmation)
        assertEquals(LocalDateTime.of(2027, 2, 5, 9, 0), due("Bill is due on 5 Feb")!!.dueAt)
    }

    @Test
    fun `weekday means the next one`() {
        assertEquals(LocalDate.of(2026, 11, 6), due("Assignment due on Friday")?.dueAt?.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 9), due("Interview next Monday")?.dueAt?.toLocalDate())
    }

    @Test
    fun `ignores promotions, plain dates and dates that have passed`() {
        listOf(
            "Offer expires tomorrow, shop now!",
            "Sale ends. Last date 12 Nov for 50% off",
            "See you tomorrow",
            "Bill was due on 12/01/2020",
            "Bill is due on 31/02/2027",
        ).forEach { assertNull("should be ignored: $it", due(it)) }
    }

    @Test
    fun `a delivery date offers a reminder`() {
        assertEquals(15, due("Your order will arrive on 15 Nov")?.dueAt?.dayOfMonth)
    }

    @Test
    fun `a time already passed today moves one hour ahead`() {
        assertEquals(now.plusHours(1), due("Exam today")?.dueAt)
    }

    @Test
    fun `tells bills apart from personal chats`() {
        assertTrue(DeadlineExtractor.looksLikeBill("Your electricity bill is due tomorrow"))
        assertFalse(DeadlineExtractor.looksLikeBill("Team meeting tomorrow at 5 pm"))
    }
}

class MoneySummaryTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 4) // A Wednesday.
    private fun at(date: LocalDate) = date.atTime(10, 0).toInstant(zone).toEpochMilli()

    private val items = listOf(
        MoneyItem(25000, "Swiggy", EXPENSE, at(today)),
        MoneyItem(120000, "Uber India", EXPENSE, at(today.minusDays(1))),
        MoneyItem(89950, "Amazon", EXPENSE, at(today.minusDays(2))),
        MoneyItem(500000, "Rahul", INCOME, at(today)),
        MoneyItem(30000, "Zomato", EXPENSE, at(today.minusDays(3))), // Sunday of last week.
        MoneyItem(10000, "Local Shop", EXPENSE, at(today.minusDays(40))),
    )

    private fun total(range: TimeRange, rules: Map<String, Category> = emptyMap()) =
        Summaries.since(items, range.startDate(today).startMillis(zone), rules)

    @Test
    fun `totals match a hand calculation`() {
        assertEquals(25000L, total(TimeRange.TODAY).spentPaise)
        assertEquals(500000L, total(TimeRange.TODAY).receivedPaise)
        // 250 + 1200 + 899.50 = ₹2,349.50 since Monday.
        assertEquals(234950L, total(TimeRange.WEEK).spentPaise)
        // Those + 300 on Sunday 1 Nov = ₹2,649.50 since the 1st.
        assertEquals(264950L, total(TimeRange.MONTH).spentPaise)
    }

    @Test
    fun `spending is split by category`() {
        val month = total(TimeRange.MONTH).spentByCategory
        assertEquals(55000L, month[Category.FOOD])
        assertEquals(120000L, month[Category.TRANSPORT])
        assertEquals(89950L, month[Category.SHOPPING])
        assertEquals(0L, month[Category.BILLS])
    }

    @Test
    fun `a saved rule beats the dictionary`() {
        assertEquals(Category.SHOPPING, Categoriser.of("Amazon"))
        assertEquals(Category.BILLS, Categoriser.of("AMAZON", mapOf("amazon" to Category.BILLS)))
        assertEquals(Category.OTHER, Categoriser.of(null))
        assertEquals(Category.OTHER, Categoriser.of("Kiran"))
    }

    @Test
    fun `week runs Monday to Sunday`() {
        val week = Summaries.week(items, today, zone)
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 11, 2), week.first().date)
        assertEquals(LocalDate.of(2026, 11, 8), week.last().date)
        assertEquals(listOf(89950L, 120000L, 25000L, 0L, 0L, 0L, 0L), week.map { it.spentPaise })
    }
}

class AskEngineTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 4)
    private val nowMillis = today.atTime(12, 0).toInstant(zone).toEpochMilli()
    private fun at(date: LocalDate) = date.atTime(10, 0).toInstant(zone).toEpochMilli()

    private val records = listOf(
        AskRecord(1, 25000, "Swiggy", EXPENSE, at(today)),
        AskRecord(2, 120000, "Uber India", EXPENSE, at(today.minusDays(1))),
        AskRecord(3, 34000, "Zomato", EXPENSE, at(today.minusDays(2))),
        AskRecord(4, 500000, "Rahul Kumar", INCOME, at(today)),
        AskRecord(5, 99900, "Flipkart", EXPENSE, at(today.minusMonths(1))),
    )
    private val reminders = listOf(
        AskReminder(1, "Electricity bill", at(today.plusDays(2)), false),
        AskReminder(2, "Paid rent", at(today.plusDays(1)), true),
    )

    private fun ask(q: String) = AskEngine.answer(q, records, reminders, emptyMap(), today, zone, nowMillis)

    @Test
    fun `answers spending by category and period, with the sources`() {
        val answer = ask("How much did I spend on food this week?")
        assertEquals("You spent ₹590 on Food this week, in 2 payments.", answer.text)
        assertEquals(listOf(1L, 3L), answer.sources.map { it.id })
    }

    @Test
    fun `answers by merchant`() {
        assertEquals("You spent ₹1,200 on Transport this month, in 1 payment.", ask("how much on cab this month").text)
        assertEquals("You spent ₹250 on Swiggy this month, in 1 payment.", ask("how much on swiggy this month").text)
    }

    @Test
    fun `answers income`() {
        val answer = ask("Money received today?")
        assertEquals("You received ₹5,000 today, in 1 payment.", answer.text)
        assertEquals(listOf(4L), answer.sources.map { it.id })
    }

    @Test
    fun `answers biggest expense`() {
        assertEquals("Your biggest payment this month was ₹1,200 to Uber India.", ask("Biggest expense this month?").text)
    }

    @Test
    fun `looks at past periods`() {
        assertEquals("You spent ₹999 last month, in 1 payment.", ask("total spent last month").text)
    }

    @Test
    fun `lists upcoming reminders and skips done ones`() {
        val answer = ask("What is due soon?")
        assertEquals("You have 1 upcoming reminder.", answer.text)
        assertEquals(listOf(1L), answer.reminders.map { it.id })
    }

    @Test
    fun `says so when nothing is found, and never invents a number`() {
        val answer = ask("How much did I spend on bills this month?")
        assertEquals("I found no money spent on Bills this month in your records.", answer.text)
        assertTrue(answer.sources.isEmpty())
    }

    @Test
    fun `general questions are left for the AI, even with money words`() {
        listOf(
            "what is the capital of France",
            "How much does a Tesla cost?",
            "Explain how UPI works",
            "what food is healthy",
            "what is a task in project management",
            "How can I reduce my spending?",
            "Should I save more this month?",
            "",
        ).forEach { assertFalse("should go to the AI: $it", ask(it).understood) }
    }

    @Test
    fun `month totals for the AI name no merchants`() {
        val line = AskEngine.monthTotalsLine(records, emptyMap(), today, zone)
        assertEquals("This month the user spent ₹1,790 (Food ₹590, Transport ₹1,200) and received ₹5,000.", line)
    }
}
