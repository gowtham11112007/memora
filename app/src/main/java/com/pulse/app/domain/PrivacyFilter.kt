package com.pulse.app.domain

/**
 * Two jobs: decide if a message must be dropped, and mask long numbers in what we keep.
 * Word boundaries (\b) stop false hits such as "hotpot" matching "otp".
 */
object PrivacyFilter {
    private val sensitive = Regex(
        """\b(otp|m-?pin|pin|cvv|cvc|password|passcode|one[- ]time (password|passcode|code)|verification code|security code|login code)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val cardNumber = Regex("""\b(?:\d{4}[ -]){3}\d{4}\b""")
    private val mixedReference = Regex("""\b(?=(?:[A-Za-z]*\d){4})[A-Za-z0-9]{10,}\b""")
    private val longDigits = Regex("""\d{8,}""")

    fun isSensitive(text: String): Boolean = sensitive.containsMatchIn(text)

    /** Replaces reference, card and phone numbers with bullets, keeping the last 4 characters. */
    fun mask(text: String): String =
        listOf(cardNumber, mixedReference, longDigits).fold(text) { current, pattern ->
            current.replace(pattern) { "••••" + it.value.takeLast(4) }
        }
}
