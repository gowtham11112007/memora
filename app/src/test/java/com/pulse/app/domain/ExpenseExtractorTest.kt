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
