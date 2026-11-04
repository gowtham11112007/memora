package com.pulse.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureGateTest {

    @Test
    fun `app that is not allowed is ignored even for normal text`() {
        assertEquals(
            CaptureDecision.IGNORED_NOT_ALLOWED,
            CaptureGate.decide(false, "Google Pay", "Paid ₹250 to Swiggy"),
        )
    }

    @Test
    fun `not allowed wins over sensitive so content is never inspected`() {
        assertEquals(
            CaptureDecision.IGNORED_NOT_ALLOWED,
            CaptureGate.decide(false, "Bank", "123456 is your OTP"),
        )
    }

    @Test
    fun `allowed app with normal text is stored`() {
        assertEquals(
            CaptureDecision.STORE,
            CaptureGate.decide(true, "Google Pay", "Paid ₹250 to Swiggy"),
        )
    }

    @Test
    fun `allowed app with OTP is dropped`() {
        assertEquals(
            CaptureDecision.DROPPED_SENSITIVE,
            CaptureGate.decide(true, "Bank", "123456 is your OTP. Do not share."),
        )
    }

    @Test
    fun `sensitive word only in the title is still dropped`() {
        assertEquals(
            CaptureDecision.DROPPED_SENSITIVE,
            CaptureGate.decide(true, "Your OTP", "482913"),
        )
    }

    @Test
    fun `empty or missing text is dropped`() {
        assertEquals(CaptureDecision.DROPPED_EMPTY, CaptureGate.decide(true, "Title", "   "))
        assertEquals(CaptureDecision.DROPPED_EMPTY, CaptureGate.decide(true, "Title", null))
    }
}
