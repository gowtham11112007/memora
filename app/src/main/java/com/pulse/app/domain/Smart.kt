package com.pulse.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Finds whether a notification's title names a starred or muted person. */
object ContactMatcher {
    /** Returns the longest matching name, so "Rahul Kumar" wins over "Rahul". Names match whole words. */
    fun <T> match(title: String?, rules: List<T>, nameOf: (T) -> String): T? {
        val text = title?.lowercase()?.trim().orEmpty()
        if (text.isEmpty()) return null
        return rules.filter { rule ->
            val name = nameOf(rule).lowercase().trim()
            name.isNotEmpty() && Regex("""(^|[^\p{L}\p{N}])${Regex.escape(name)}($|[^\p{L}\p{N}])""").containsMatchIn(text)
        }.maxByOrNull { nameOf(it).length }
    }
}

enum class Repeat(val label: String) {
    NONE("Once"),
    DAILY("Daily"),
    WEEKLY("Weekly"),
    MONTHLY("Monthly");

    companion object {
        fun of(name: String): Repeat = entries.firstOrNull { it.name == name } ?: NONE
    }

    /** The next time after [dueAt] that's still in the future, in case the phone was off for a while. */
    fun next(dueAt: Long, now: Long, zone: ZoneId): Long? {
        if (this == NONE) return null
        var at = Instant.ofEpochMilli(dueAt).atZone(zone)
        do {
            at = when (this) {
                DAILY -> at.plusDays(1)
                WEEKLY -> at.plusWeeks(1)
                else -> at.plusMonths(1)
            }
        } while (at.toInstant().toEpochMilli() <= now)
        return at.toInstant().toEpochMilli()
    }
}

/** Spots messages asking you to do something, so Memora can offer a to-do. A word rule, not mind-reading. */
object TaskPredictor {
    private val ask = Regex(
        """\b(don'?t forget|remember to|action required|need you to|please (?:submit|send|pay|call|bring|complete|review|sign|reply|confirm|share|update)|kindly (?:submit|send|pay|call|complete|review|sign|reply|confirm|share|update)|can you (?:please )?(?:call|send|bring|pay|submit|review|share))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val promotion = Regex("""\b(offers?|sale|deals?|discount|cashback|win|coupon|voucher|shop now|limited time)\b|%\s*off""", RegexOption.IGNORE_CASE)

    fun suggestion(text: String): String? {
        if (!ask.containsMatchIn(text) || promotion.containsMatchIn(text)) return null
        return BrainClassifier.shorten(text)
    }
}

/**
 * One payment can be reported by a bank SMS, Google Pay and Paytm, each worded differently.
 * Amount and direction are checked by the caller. This decides whether two reports are one payment.
 */
object PaymentMatch {
    const val SAME_APP_MS = 2L * 60 * 1000

    // An SMS can arrive a few minutes after the app's notification.
    const val OTHER_APP_MS = 10L * 60 * 1000

    // A matching bank reference is strong proof, so it can look further back.
    const val SEARCH_MS = 60L * 60 * 1000

    data class Report(val pkg: String, val name: String?, val reference: String?, val at: Long)

    fun same(a: Report, b: Report): Boolean {
        if (a.reference != null && b.reference != null) return a.reference == b.reference
        val gap = kotlin.math.abs(a.at - b.at)
        return if (a.pkg == b.pkg) {
            gap <= SAME_APP_MS && a.name.equals(b.name, ignoreCase = true)
        } else {
            gap <= OTHER_APP_MS && !clearlyDifferent(a.name, b.name)
        }
    }

    /** Keeps the most useful name: a full name beats a single word, which beats a number or nothing. */
    fun betterName(current: String?, other: String?): String? = if (rank(other) > rank(current)) other else current

    private val clearName = Regex("""^[A-Za-z][A-Za-z .&'-]+$""")

    private fun rank(name: String?): Int = when {
        name == null -> 0
        !clearName.matches(name) -> 1
        ' ' !in name.trim() -> 2
        else -> 3
    }

    /** Only two readable names with nothing in common count as different people. */
    private fun clearlyDifferent(a: String?, b: String?): Boolean {
        if (a == null || b == null || !clearName.matches(a) || !clearName.matches(b)) return false
        val wordsA = words(a)
        val wordsB = words(b)
        val joinedA = wordsA.joinToString("")
        val joinedB = wordsB.joinToString("")
        return wordsA.none { it.length >= 3 && it in joinedB } && wordsB.none { it.length >= 3 && it in joinedA }
    }

    private fun words(name: String) = name.lowercase().split(Regex("""[^a-z]+""")).filter { it.isNotEmpty() }
}

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
