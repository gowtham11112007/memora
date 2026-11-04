package com.pulse.app.domain

import com.pulse.app.domain.RecordType.EXPENSE
import com.pulse.app.domain.RecordType.INCOME
import com.pulse.app.service.BudgetAlert
import com.pulse.app.service.EveningBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class SmartTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 20)
    private fun at(date: LocalDate) = date.atTime(10, 0).toInstant(zone).toEpochMilli()
    private fun spent(paise: Long, name: String?, daysAgo: Long) = MoneyItem(paise, name, EXPENSE, at(today.minusDays(daysAgo)))

    @Test
    fun `finds charges repeating monthly, and ignores the rest`() {
        val items = listOf(
            spent(19900, "Netflix", 5), spent(19900, "NETFLIX", 35),
            spent(25000, "Swiggy", 2), spent(25000, "Swiggy", 3),
            spent(50000, "Gym", 30), spent(50000, "Gym", 60),
            spent(99900, "Old Magazine", 90), spent(99900, "Old Magazine", 120), // Stopped.
            spent(29900, "Airtel", 10), spent(31000, "Airtel", 40), // Amounts differ.
        )
        val found = Subscriptions.detect(items, today, zone).map { it.merchant.lowercase() }
        assertEquals(listOf("gym", "netflix"), found)
        assertEquals("You have 2 monthly subscriptions, about ₹699 a month.", Subscriptions.summary(items, today, zone))
        assertNull(Subscriptions.summary(listOf(spent(100, "x", 1)), today, zone))
    }

    @Test
    fun `personal questions stay on the phone, general ones don't match`() {
        assertEquals("passport", Lookups.query("Where is my passport?"))
        assertEquals("wifi password hint", Lookups.query("what's my wifi password hint"))
        assertEquals("keys", Lookups.query("where did I put my keys"))
        assertNull(Lookups.query("Where is Paris?"))
        assertNull(Lookups.query("What is the capital of France?"))
    }

    private val records = listOf(
        AskRecord(1, 25000, "Swiggy", EXPENSE, at(today)),
        AskRecord(2, 120000, "Uber", EXPENSE, at(today.minusDays(1))),
        AskRecord(3, 75000, "Swiggy", EXPENSE, at(today.minusDays(2))),
        AskRecord(4, 500000, "Rahul", INCOME, at(today)),
    )

    private fun ask(q: String, money: AskMoney = AskMoney()) =
        AskEngine.answer(q, records, emptyList(), emptyMap(), today, zone, at(today), money)

    @Test
    fun `answers balance and budget from the phone`() {
        val money = AskMoney(BalanceView(4235000, "From your latest bank SMS"), 500000)
        assertEquals("Your balance is ₹42,350. From your latest bank SMS.", ask("What's my balance?", money).text)
        assertTrue(ask("what is my balance").text.startsWith("I don't know your balance yet"))
        assertEquals("You have ₹2,800 left of your ₹5,000 budget this month.", ask("How much is left in my budget?", money).text)
        assertEquals("You're ₹200 over your ₹2,000 budget this month.", ask("my budget", AskMoney(budgetPaise = 200000)).text)
    }

    @Test
    fun `answers top payees and the daily average`() {
        assertEquals(listOf("Swiggy: ₹1,000", "Uber: ₹1,200").sorted(), ask("Where do I spend the most?").notes.sorted())
        assertEquals("You've spent an average of ₹110 a day this month.", ask("What is my average daily spending?").text)
    }

    @Test
    fun `general questions about money still go to the AI`() {
        assertFalse(ask("What is a balance sheet?").understood)
        assertFalse(ask("What is a budget?").understood)
        assertFalse(ask("How can I stick to my budget?").understood)
    }

    @Test
    fun `budget alerts fire once at each level`() {
        assertNull(BudgetAlert.due(70, 100, 0))
        assertEquals(80, BudgetAlert.due(80, 100, 0))
        assertNull(BudgetAlert.due(90, 100, 80))
        assertEquals(100, BudgetAlert.due(100, 100, 80))
        assertEquals(100, BudgetAlert.due(150, 100, 0))
        assertNull(BudgetAlert.due(150, 100, 100))
        assertNull(BudgetAlert.due(150, 0, 0))
    }

    @Test
    fun `evening summary says only what happened, or nothing`() {
        assertEquals("You spent ₹250 today. 2 reminders tomorrow.", EveningBrief.message(25000, 0, 2))
        assertEquals("You received ₹100. 1 reminder tomorrow.", EveningBrief.message(0, 10000, 1))
        assertNull(EveningBrief.message(0, 0, 0))
    }

    @Test
    fun `CSV is correct and safe to open in a spreadsheet`() {
        val items = listOf(
            MoneyItem(25050, "Swiggy, Anna Nagar", EXPENSE, at(today)),
            MoneyItem(500000, "=HACK()", INCOME, at(today.minusDays(1))),
        )
        assertEquals(
            "date,type,amount_inr,merchant,category\n" +
                "2026-11-20 10:00,EXPENSE,250.50,\"Swiggy, Anna Nagar\",Food\n" +
                "2026-11-19 10:00,INCOME,5000.00,'=HACK(),",
            Exporter.toCsv(items, emptyMap(), zone),
        )
    }
}
