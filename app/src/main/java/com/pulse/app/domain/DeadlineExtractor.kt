package com.pulse.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

data class Deadline(val title: String, val dueAt: LocalDateTime, val confidence: Double) {
    /** Below this, Memora asks the user to confirm before it schedules anything. */
    val needsConfirmation: Boolean get() = confidence < CONFIDENT
    companion object { const val CONFIDENT = 0.8 }
}

/** Finds a date in messages that clearly describe something due. Unsure means null or a low confidence. */
object DeadlineExtractor {
    private val ic = setOf(RegexOption.IGNORE_CASE)
    private const val MONTHS = "jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec"
    private const val WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"

    private val trigger = Regex(
        """\b(due|deadline|last date|expires?|expiry|expiring|renew|renewal|pay by|submit|appointment|meeting|exam|interview|remind me|reminder|will be debited)\b""",
        ic,
    )
    private val promotion = Regex("""\b(offers?|sale|deals?|discount|cashback|win|coupon|voucher|shop now|limited time)\b|%\s*off""", ic)
    private val billWords = Regex("""\b(bill|payment|premium|emi|recharge|renew|renewal|due|debited|subscription)\b""", ic)

    private val numericDate = Regex("""\b(\d{1,2})[/-](\d{1,2})[/-](\d{2}|\d{4})\b""")
    private val dayMonth = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?[\s-]+($MONTHS)[a-z]*\.?(?:,?[\s-]+(\d{4}))?\b""", ic)
    private val monthDay = Regex("""\b($MONTHS)[a-z]*\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\b""", ic)
    private val inDays = Regex("""\bin\s+(\d{1,3})\s+days?\b""", ic)
    private val nextWeekday = Regex("""\bnext\s+($WEEKDAYS)\b""", ic)
    private val onWeekday = Regex("""\b(?:on|by|this|before)\s+($WEEKDAYS)\b""", ic)
    private val time12 = Regex("""\b(\d{1,2})(?::(\d{2}))?\s*(am|pm)\b""", ic)
    private val time24 = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")

    private val defaultTime = LocalTime.of(9, 0)

    /** For SMS apps: personal chats mentioning a meeting must not become stored reminders. */
    fun looksLikeBill(text: String): Boolean = billWords.containsMatchIn(text)

    fun extract(title: String?, text: String, now: LocalDateTime): Deadline? {
        val message = listOfNotNull(title?.takeIf { it.isNotBlank() }, text).joinToString(". ")
        if (!trigger.containsMatchIn(message) || promotion.containsMatchIn(message)) return null
        val (date, confidence) = findDate(message, now.toLocalDate()) ?: return null
        val time = findTime(message)
        var due = date.atTime(time ?: defaultTime)
        if (due <= now) {
            if (date != now.toLocalDate()) return null
            due = now.plusHours(1)
        }
        return Deadline(titleFrom(text), due, confidence)
    }

    private fun titleFrom(text: String): String {
        val sentence = text.trim().substringBefore(". ").trim()
        return if (sentence.length <= 70) sentence else sentence.take(67).trimEnd() + "..."
    }

    private fun findDate(message: String, today: LocalDate): Pair<LocalDate, Double>? {
        val lower = message.lowercase()
        numericDate.find(message)?.let { m ->
            val (d, mo, y) = m.destructured
            val year = if (y.length == 2) 2000 + y.toInt() else y.toInt()
            return safeDate(year, mo.toInt(), d.toInt())?.let { it to 0.9 }
        }
        dayMonth.find(message)?.let { m ->
            val (d, mo, y) = m.destructured
            return absolute(d.toInt(), mo, y, today)
        }
        monthDay.find(message)?.let { m ->
            val (mo, d, y) = m.destructured
            return absolute(d.toInt(), mo, y, today)
        }
        if ("day after tomorrow" in lower) return today.plusDays(2) to 0.85
        if (Regex("""\btomorrow\b""").containsMatchIn(lower)) return today.plusDays(1) to 0.85
        if (Regex("""\b(today|tonight)\b""").containsMatchIn(lower)) return today to 0.85
        inDays.find(message)?.let { return today.plusDays(it.groupValues[1].toLong()) to 0.85 }
        nextWeekday.find(message)?.let { m ->
            return today.with(TemporalAdjusters.next(weekday(m.groupValues[1]))) to 0.7
        }
        onWeekday.find(message)?.let { m ->
            return today.with(TemporalAdjusters.nextOrSame(weekday(m.groupValues[1]))) to 0.7
        }
        return null
    }

    /** A date with no year that has already passed means next year, which is a guess, so confidence drops. */
    private fun absolute(day: Int, monthText: String, yearText: String, today: LocalDate): Pair<LocalDate, Double>? {
        val month = MONTHS.split("|").indexOf(monthText.take(3).lowercase()) + 1
        if (yearText.isNotEmpty()) return safeDate(yearText.toInt(), month, day)?.let { it to 0.9 }
        val thisYear = safeDate(today.year, month, day) ?: return null
        return if (thisYear >= today) thisYear to 0.9 else safeDate(today.year + 1, month, day)?.let { it to 0.7 }
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    private fun weekday(name: String): DayOfWeek = DayOfWeek.valueOf(name.uppercase())

    private fun findTime(message: String): LocalTime? {
        time12.find(message)?.let { m ->
            val hour = m.groupValues[1].toInt()
            if (hour !in 1..12) return null
            val minute = m.groupValues[2].toIntOrNull() ?: 0
            val isPm = m.groupValues[3].equals("pm", ignoreCase = true)
            return LocalTime.of(hour % 12 + if (isPm) 12 else 0, minute)
        }
        return time24.find(message)?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    }
}
