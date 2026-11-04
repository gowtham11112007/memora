package com.pulse.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class Subscription(val merchant: String, val amountPaise: Long)

/** Spots repeating monthly charges: the same merchant and amount, about a month apart. */
object Subscriptions {
    private const val MIN_GAP_DAYS = 26L
    private const val MAX_GAP_DAYS = 35L
    private const val STILL_ACTIVE_DAYS = 40L

    fun detect(items: List<MoneyItem>, today: LocalDate, zone: ZoneId): List<Subscription> =
        items.filter { it.type == RecordType.EXPENSE && it.merchant != null }
            .groupBy { it.merchant!!.lowercase().trim() to it.amountPaise }
            .values
            .mapNotNull { charges ->
                val dates = charges.map { it.at.toLocalDate(zone) }.sorted()
                val monthly = dates.zipWithNext().any { (a, b) -> ChronoUnit.DAYS.between(a, b) in MIN_GAP_DAYS..MAX_GAP_DAYS }
                val active = ChronoUnit.DAYS.between(dates.last(), today) <= STILL_ACTIVE_DAYS
                if (monthly && active) Subscription(charges.last().merchant!!, charges.last().amountPaise) else null
            }
            .sortedByDescending { it.amountPaise }

    fun summary(items: List<MoneyItem>, today: LocalDate, zone: ZoneId): String? {
        val found = detect(items, today, zone)
        if (found.isEmpty()) return null
        val total = formatRupees(found.sumOf { it.amountPaise })
        return if (found.size == 1) {
            "${found.single().merchant} looks like a monthly subscription, at $total."
        } else {
            "You have ${found.size} monthly subscriptions, about $total a month."
        }
    }
}

/** "Where is my passport?" is a question about the user's own notes, so it stays on the phone. */
object Lookups {
    private val personal = Regex(
        """^(?:where(?:'s| is| are| did i (?:put|keep|leave))|what(?:'s| is| was)|when(?:'s| is)|which is)\s+my\s+(.+?)\??$""",
        RegexOption.IGNORE_CASE,
    )

    fun query(text: String): String? = personal.find(text.trim())?.groupValues?.get(1)?.trim()
}

/** Turns records into CSV text. A leading ' stops a spreadsheet running a name as a formula. */
object Exporter {
    fun toCsv(items: List<MoneyItem>, rules: Map<String, Category>, zone: ZoneId): String {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val rows = items.sortedByDescending { it.at }.map { item ->
            val whenAt = Instant.ofEpochMilli(item.at).atZone(zone).format(format)
            val amount = "${item.amountPaise / 100}.${(item.amountPaise % 100).toString().padStart(2, '0')}"
            val category = if (item.type == RecordType.EXPENSE) Categoriser.of(item.merchant, rules).label else ""
            listOf(whenAt, item.type.name, amount, cell(item.merchant.orEmpty()), category).joinToString(",")
        }
        return (listOf("date,type,amount_inr,merchant,category") + rows).joinToString("\n")
    }

    private fun cell(value: String): String {
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
        return if (safe.any { it in ",\"\n" }) "\"${safe.replace("\"", "\"\"")}\"" else safe
    }
}
