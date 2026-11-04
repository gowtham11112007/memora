package com.pulse.app.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyFilterTest {

    @Test
    fun `drops OTP and credential messages`() {
        val sensitive = listOf(
            "482913 is your OTP for login. Do not share it with anyone.",
            "Your one time password is 7781",
            "Use verification code 4821 to verify your account",
            "Never share your CVV or UPI PIN with anyone",
            "Your MPIN has been changed",
            "Reset your password using this link",
            "otp 556677 valid for 10 minutes",
        )
        sensitive.forEach { assertTrue("should be sensitive: $it", PrivacyFilter.isSensitive(it)) }
    }

    @Test
    fun `keeps normal payment messages`() {
        val normal = listOf(
            "Paid ₹250.00 to Swiggy. UPI Ref No. 412345678901",
            "₹1,200.00 sent to Uber India",
            "Received ₹5,000.00 from Rahul Kumar",
            "Spinning class booked for 6 pm", // contains 'pin' inside a word
            "Hotpot Rs 300 paid", // contains 'otp' inside a word
        )
        normal.forEach { assertFalse("should be kept: $it", PrivacyFilter.isSensitive(it)) }
    }
}
