package com.pulse.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BrainTest {
    private val now = LocalDateTime.of(2026, 11, 4, 14, 5)

    @Test
    fun `sorts messages into kinds and skips promotions`() {
        assertEquals(NoteKind.DELIVERY, BrainClassifier.classify("Your order has shipped. Tracking ID 123"))
        assertEquals(NoteKind.TICKET, BrainClassifier.classify("PNR 1234567890 for train 12601"))
        assertEquals(NoteKind.BILL, BrainClassifier.classify("Your electricity bill is ready"))
        assertEquals(NoteKind.EVENT, BrainClassifier.classify("Interview on Friday"))
        assertNull(BrainClassifier.classify("Flat 50% off on flights. Shop now"))
        assertNull(BrainClassifier.classify("Lunch tomorrow?"))
    }

    @Test
    fun `recognises business SMS senders, not people`() {
        assertTrue(BrainClassifier.looksLikeBusiness("AD-HDFCBK"))
        assertTrue(BrainClassifier.looksLikeBusiness("JX-AMAZON-S"))
        assertFalse(BrainClassifier.looksLikeBusiness("Kiran"))
        assertFalse(BrainClassifier.looksLikeBusiness("Amma"))
        assertFalse(BrainClassifier.looksLikeBusiness(null))
    }

    @Test
    fun `balance prefers the newest source`() {
        val spend = MoneyItem(25000, "Swiggy", RecordType.EXPENSE, 200)
        val income = MoneyItem(100000, "Rahul", RecordType.INCOME, 300)
        val before = MoneyItem(999900, "Old", RecordType.EXPENSE, 50)
        assertNull(Balance.compute(MoneySettings(), listOf(spend)))
        // ₹5,000 set at t=100, minus ₹250, plus ₹1,000, ignoring what happened before.
        assertEquals(
            575000L,
            Balance.compute(MoneySettings(openingPaise = 500000, openingAt = 100), listOf(before, spend, income))?.paise,
        )
        assertEquals(
            700000L,
            Balance.compute(MoneySettings(openingPaise = 500000, openingAt = 100, bankPaise = 700000, bankAt = 400), listOf(spend))?.paise,
        )
    }

    @Test
    fun `reads the balance in a bank message`() {
        assertEquals(1000050L, ExpenseExtractor.findBalance("Rs.500 debited. Avl Bal INR 10,000.50"))
        assertEquals(700000L, ExpenseExtractor.findBalance("Bal: Rs 7,000"))
        assertNull(ExpenseExtractor.findBalance("Paid Rs 500 to Swiggy"))
    }

    @Test
    fun `understands chat commands`() {
        assertEquals(ChatCommand.Remember("my locker is 12"), ChatCommands.parse("Remember that my locker is 12"))
        assertEquals(ChatCommand.Remember("buy milk"), ChatCommands.parse("note: buy milk"))
        assertEquals(ChatCommand.Find("passport"), ChatCommands.parse("What did I save about passport?"))
        assertEquals(ChatCommand.Find("wifi"), ChatCommands.parse("find wifi"))
        assertEquals(ChatCommand.Remind("Remind me to pay rent tomorrow"), ChatCommands.parse("Remind me to pay rent tomorrow"))
        assertEquals("Pay rent tomorrow", ChatCommands.reminderTitle("remind me to pay rent tomorrow"))
        assertNull(ChatCommands.parse("What is the capital of France?"))
    }

    @Test
    fun `answers maths and units offline`() {
        assertEquals("12 * 5 + 3 = 63", OfflineAnswers.answer("what is 12 * 5 + 3?", now))
        assertEquals("(2 + 3) x 4 = 20", OfflineAnswers.answer("(2 + 3) x 4", now))
        assertEquals("30", OfflineAnswers.answer("15% of 200", now))
        assertEquals("10 km = 6.21 miles", OfflineAnswers.answer("10 km to miles", now))
        assertEquals("100 c = 212 f", OfflineAnswers.answer("100 c to f", now))
        assertEquals("5 kg = 11.02 lb", OfflineAnswers.answer("convert 5 kg to pounds", now))
    }

    @Test
    fun `answers dates, greetings and help offline`() {
        assertEquals("It's 2:05 PM.", OfflineAnswers.answer("What time is it?", now))
        assertEquals("Today is Wednesday, 4 November 2026.", OfflineAnswers.answer("what day is it", now))
        assertTrue(OfflineAnswers.answer("hello", now)!!.startsWith("Hi!"))
        assertTrue(OfflineAnswers.answer("what can you do?", now)!!.contains("second brain"))
    }

    @Test
    fun `admits what it cannot answer offline`() {
        assertNull(OfflineAnswers.answer("Explain how UPI works", now))
        assertNull(OfflineAnswers.answer("10 km to kg", now))
        assertNull(OfflineAnswers.answer("what is 12 *", now))
    }

    @Test
    fun `calculator handles order, brackets and bad input`() {
        assertEquals(14.0, Calculator.evaluate("2 + 3 * 4")!!, 0.001)
        assertEquals(1024.0, Calculator.evaluate("2^10")!!, 0.001)
        assertEquals(-2.0, Calculator.evaluate("-(1 + 1)")!!, 0.001)
        assertNull(Calculator.evaluate("1 / 0"))
        assertNull(Calculator.evaluate("(1 + 2"))
    }
}
