package com.pulse.app.domain

import com.pulse.app.domain.RecordType.EXPENSE
import com.pulse.app.domain.RecordType.INCOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Samples are written in the style of Indian UPI / bank alerts. They are not copied from a real
 * phone yet, so review them and add real ones (with personal details changed).
 */
class ExpenseExtractorTest {
    private data class Case(val message: String, val paise: Long, val merchant: String?, val type: RecordType)

    private val cases = listOf(
        // UPI apps
        Case("Paid ₹250.00 to Swiggy. UPI Ref No. 412345678901", 25000, "Swiggy", EXPENSE),
        Case("₹1,200.00 sent to Uber India. UPI Ref 509812340011", 120000, "Uber India", EXPENSE),
        Case("Received ₹5,000.00 from Rahul Kumar", 500000, "Rahul Kumar", INCOME),
        Case("Rahul Kumar paid you ₹500", 50000, "Rahul Kumar", INCOME),
        Case("You paid ₹99 to Zomato using Google Pay", 9900, "Zomato", EXPENSE),
        Case("Payment of ₹349 to Netflix successful", 34900, "Netflix", EXPENSE),
        Case("Payment successful! ₹1,499 paid to Flipkart via PhonePe", 149900, "Flipkart", EXPENSE),
        Case("₹60 paid to Ramesh Tea Stall", 6000, "Ramesh Tea Stall", EXPENSE),
        Case("Priya sent you ₹2,000", 200000, "Priya", INCOME),
        Case("Money sent! Rs 750 to Anita Sharma", 75000, "Anita Sharma", EXPENSE),
        Case("Paytm: Rs.45 paid to Metro Rail via UPI", 4500, "Metro Rail", EXPENSE),
        // Bank alerts
        Case("Rs.500.00 debited from A/c XX1234 to VPA swiggy@ybl on 12-01-26. Ref 612345678901", 50000, "swiggy", EXPENSE),
        Case("INR 2,500.00 debited from a/c XX4521 at BigBasket. Avl Bal INR 10,000.50", 250000, "BigBasket", EXPENSE),
        Case("A/c X1234 debited by Rs 500 on 05Jan; Rahul credited. UPI Ref 512345678901", 50000, "Rahul", EXPENSE),
        Case("Rs.25,000.00 credited to A/c XX7788 by NEFT from Acme Technologies. Avl Bal Rs 41,250.00", 2500000, "Acme Technologies", INCOME),
        Case("Your A/c XX1122 is credited with INR 1,250.75 from Suresh", 125075, "Suresh", INCOME),
        Case("Rs 1,00,000 credited to your account. Total Bal Rs 2,50,000", 10000000, null, INCOME),
        Case("Rs.3,000 withdrawn at ATM Anna Nagar on 02-Jan. Bal: Rs 7,000", 300000, "ATM Anna Nagar", EXPENSE),
        // Cards
        Case("Rs 1,299 spent on HDFC Card XX9090 at AMAZON on 12-Jan", 129900, "AMAZON", EXPENSE),
        Case("INR 899.50 spent at Dominos Pizza using your card", 89950, "Dominos Pizza", EXPENSE),
        Case("Purchase of Rs. 2,150 at Reliance Fresh", 215000, "Reliance Fresh", EXPENSE),
        // Refunds and other credits
        Case("Refund of ₹450 from Myntra processed to your account", 45000, "Myntra", INCOME),
        Case("Cashback of ₹25 credited to your wallet", 2500, null, INCOME),
        // Amount after the word, and no decimal point
        Case("500 rupees sent to Kiran", 50000, "Kiran", EXPENSE),
    )

    @Test
    fun `extracts amount, merchant and type from payment messages`() {
        val failures = cases.mapNotNull { case ->
            val expected = Extraction(case.paise, case.merchant, case.type)
            val actual = ExpenseExtractor.extract(null, case.message)
            if (actual == expected) null else "${case.message}\n  expected $expected\n  actual   $actual"
        }
        assertEquals("${failures.size} of ${cases.size} failed:\n" + failures.joinToString("\n"), 0, failures.size)
    }

    @Test
    fun `ignores messages where no money moved`() {
        listOf(
            "Get ₹100 cashback on your next payment!",
            "Flat ₹500 offer on flights. Hurry!",
            "Your payment of ₹500 to Swiggy failed",
            "Transaction of Rs 200 declined by bank",
            "Rahul requested ₹300 from you",
            "Electricity bill of Rs 1,240 is due on 5 Feb",
            "Rs 999 will be debited on 3 Feb for your subscription",
            "You are eligible for a pre-approved loan of Rs 2,00,000",
            "Your order has shipped",
            "Lunch at 1 pm tomorrow?",
            "Your balance is Rs 5,000",
        ).forEach { assertNull("should be ignored: $it", ExpenseExtractor.extract(null, it)) }
    }

    @Test
    fun `reads Paytm style alerts that split the message between title and body`() {
        assertEquals(
            Extraction(100, "Kiran K B", INCOME),
            ExpenseExtractor.extract(
                "Received ₹1 from Kiran K B",
                "Deposited in your Indian Overseas Bank - 4131 on 04 November at 2:39 PM",
            ),
        )
    }

    @Test
    fun `reads bank SMS for money you sent`() {
        assertEquals(
            Extraction(100, null, EXPENSE),
            ExpenseExtractor.extract(
                "AD-IOBCHN",
                "Your a/c no. XXXX4131 is debited for Rs.1.00 on 04-11-26 and credited to a/c no. XXXX9921 (UPI Ref no 612345678901)",
            ),
        )
        assertEquals(
            Extraction(2000, "Kiran", EXPENSE),
            ExpenseExtractor.extract("AD-SBIUPI", "A/C X1234 debited by 20.0 on 01Jan26 trf to Kiran Refno 512345678901"),
        )
    }

    @Test
    fun `reads money sent even when the bank adds a safety note or an advert`() {
        fun read(text: String) = ExpenseExtractor.extract("AD-BANK", text, titleMayBePerson = false)
        listOf(
            "Rs.100.00 debited from A/c XX1234 to Rahul on 05-11-26. Ref 612345678901. If not requested by you, call 18001234",
            "Your a/c XX1234 is debited for Rs.100.00 on 05-11-26. Not you? SMS BLOCK to 9000000000",
            "Rs 100 debited from a/c XX1234 for UPI txn. Get a pre-approved loan today, apply now!",
            "UPI Debit: Rs:100.00 from A/c XX1234 to Rahul on 05-11-26. Ref 612345678901",
            "INR : 100 debited from your account XX1234. T&C apply",
            "Debit Alert! Rupees 100 sent from A/c XX1234 to Rahul. To report fraud, call 1800",
        ).forEach { message ->
            assertEquals(message, 10000L, read(message)?.amountPaise)
            assertEquals(message, EXPENSE, read(message)?.type)
        }
        assertEquals(10000L, ExpenseExtractor.extract("Google Pay", "You sent 100 to Rahul")?.amountPaise)
    }

    @Test
    fun `adverts and future debits still aren't money, even from a bank`() {
        listOf(
            "Rs 999 will be debited from your A/c XX1 on 3 Feb",
            "Get Rs 100 credited to your account on your first UPI payment!",
            "Your payment of Rs 100 from A/c XX1 failed. Amount will not be debited",
            "Pre-approved loan of Rs 2,00,000 for your account. Apply now",
        ).forEach { assertNull(it, ExpenseExtractor.extract("AD-BANK", it, titleMayBePerson = false)) }
    }

    @Test
    fun `a credit or debit card isn't a direction`() {
        assertEquals(EXPENSE, ExpenseExtractor.extract(null, "Your Credit Card XX12 was used for Rs 500 at Amazon")?.type)
        assertEquals(INCOME, ExpenseExtractor.extract(null, "Credit Alert: Rs 500 to A/c XX12 from Rahul")?.type)
    }

    @Test
    fun `reads who sent or received the money in every common wording`() {
        fun name(title: String?, text: String, person: Boolean = true) = ExpenseExtractor.extract(title, text, person)?.merchant
        assertEquals("Rahul Kumar", name("Rahul Kumar", "Paid you ₹500"))
        assertEquals("Rahul Kumar", name("Rahul Kumar", "₹500 received"))
        assertEquals("Rahul Kumar", name("Payment received", "Rahul Kumar sent you ₹500"))
        assertEquals("Rahul Kumar", name("₹500 received", "From Rahul Kumar"))
        assertEquals("Rahul Kumar", name(null, "Rahul Kumar sent ₹500 to you"))
        assertEquals("RAHUL KUMAR", name(null, "₹500 received from RAHUL KUMAR in your bank account"))
        assertEquals("RAHUL KUMAR", name(null, "INR 500.00 credited to A/c no. XX123. Info- UPI/P2A/612345678901/RAHUL KUMAR/HDFC"))
        assertEquals("ZOMATO", name(null, "INR 99.00 debited from A/c XX1. Info: UPI/DR/612345678901/ZOMATO/YESB"))
        assertEquals("RAHUL KUMAR", name(null, "Sent Rs.100.00\nFrom HDFC Bank A/C *1234\nTo RAHUL KUMAR\nOn 03/11/26\nRef 612345678901"))
        assertEquals("rahul", name(null, "Rs.500 credited to HDFC Bank A/c XX1 on 03-11-26 from VPA rahul@okaxis (UPI 612345678901)"))
        assertEquals("Rahul", name(null, "You paid Rahul ₹100"))
    }

    @Test
    fun `never mistakes an amount, time, app or bank title for a person`() {
        fun name(title: String?, text: String, person: Boolean = true) = ExpenseExtractor.extract(title, text, person)?.merchant
        assertNull(name(null, "ur A/cX1234 credited by Rs500 on 01Jan26 by (Ref no 512345678901)"))
        assertNull(name(null, "Paid ₹1 on 04 Nov at 2:39 PM"))
        assertNull(name("Google Pay", "Paid you ₹500"))
        assertNull(name("Payment received", "₹500 received"))
        assertNull(name("HDFC Chennai", "A/c XX1 credited with Rs.900.00", person = false))
        assertNull(name(null, "Rs.500 to 9876543210@ybl paid"))
    }

    @Test
    fun `finds the last 4 digits of the bank's reference`() {
        assertEquals("8901", ExpenseExtractor.findReference("Paid ₹250. UPI Ref No. 412345678901"))
        assertEquals("8901", ExpenseExtractor.findReference("debited trf to Kiran Refno 412345678901"))
        assertEquals("8901", ExpenseExtractor.findReference("credited (UPI 412345678901)"))
        assertEquals("8901", ExpenseExtractor.findReference("Info- UPI/P2A/412345678901/RAHUL/HDFC"))
        assertEquals("8901", ExpenseExtractor.findReference("RRN: 412345678901"))
        assertNull(ExpenseExtractor.findReference("Paid ₹250 to Swiggy via UPI"))
    }

    @Test
    fun `tells bank alerts apart from personal chats`() {
        assertTrue(ExpenseExtractor.looksLikeBankAlert("Your a/c is debited for Rs.1.00"))
        assertFalse(ExpenseExtractor.looksLikeBankAlert("I paid 500 rupees for lunch"))
    }

    @Test
    fun `uses the title only when the body alone is not enough`() {
        assertEquals(
            Extraction(25000, null, EXPENSE),
            ExpenseExtractor.extract("₹250 paid", "Transaction complete"),
        )
        assertEquals(
            Extraction(5000, "Swiggy", EXPENSE),
            ExpenseExtractor.extract("Google Pay", "Paid ₹50 to Swiggy"),
        )
    }
}
