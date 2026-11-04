package com.pulse.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class MaskingAndMoneyTest {
    @Test
    fun `masks long reference numbers and keeps the last 4`() {
        assertEquals("UPI Ref No. ••••8901", PrivacyFilter.mask("UPI Ref No. 412345678901"))
        assertEquals("Txn ••••90CD", PrivacyFilter.mask("Txn AB12345690CD"))
        assertEquals("Card ••••3456", PrivacyFilter.mask("Card 1234 5678 9012 3456"))
        assertEquals("Call ••••3210", PrivacyFilter.mask("Call 9876543210"))
    }

    @Test
    fun `leaves amounts, short numbers and names alone`() {
        listOf("Paid ₹1,200.00 to Uber India", "A/c XX1234 on 12-01-26", "Rs.250 to Swiggy", "Rs 2,50,000").forEach {
            assertEquals(it, PrivacyFilter.mask(it))
        }
    }

    @Test
    fun `formats rupees with Indian grouping`() {
        assertEquals("₹0.05", formatRupees(5))
        assertEquals("₹250", formatRupees(25000))
        assertEquals("₹999.50", formatRupees(99950))
        assertEquals("₹1,200", formatRupees(120000))
        assertEquals("₹12,34,567.80", formatRupees(123456780))
    }
}
