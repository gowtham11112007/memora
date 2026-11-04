package com.pulse.app.domain

import java.time.LocalDate
import java.time.ZoneId

data class AskRecord(val id: Long, val amountPaise: Long, val merchant: String?, val type: RecordType, val at: Long)
data class AskReminder(val id: Long, val title: String, val dueAt: Long, val done: Boolean)

data class AskAnswer(
    val text: String,
    val sources: List<AskRecord> = emptyList(),
    val reminders: List<AskReminder> = emptyList(),
    val notes: List<String> = emptyList(),
    /** False means this isn't a question about the user's records, so the AI may take it. */
    val understood: Boolean = true,
)

/**
 * Answers questions about the user's own records, on the phone, from fixed queries. Every total
 * comes from records shown under the answer. Anything else comes back with understood = false.
 */
object AskEngine {
    private data class Period(val label: String, val from: LocalDate?, val until: LocalDate?)

    private val categoryWords = mapOf(
        Category.FOOD to listOf("food", "eat", "eating", "restaurant", "lunch", "dinner", "breakfast"),
        Category.TRANSPORT to listOf("transport", "travel", "cab", "taxi", "uber", "ola", "metro", "fuel", "petrol"),
        Category.SHOPPING to listOf("shopping", "shop", "clothes", "groceries"),
        Category.BILLS to listOf("bill", "bills", "recharge", "subscription", "subscriptions"),
    )

    private val moneyWords = arrayOf(
        "spend", "spent", "spending", "expense", "expenses", "paid", "pay", "cost", "total", "much", "biggest",
        "largest", "highest", "payments", "payment", "transactions", "received", "receive", "income", "earned", "got",
        "credited", "many",
    )
    private val ownMoneyWords = arrayOf("spent", "expense", "expenses", "received", "income", "credited", "my money")
    private val periodWords = arrayOf("today", "yesterday", "week", "month", "year")
    private val incomeWords = arrayOf("received", "receive", "income", "earned", "got", "credited")
    private val firstPerson = arrayOf("i", "my", "me", "mine", "we", "our")

    // "How can I spend less?" needs advice, not a total, so it goes to the AI.
    private val adviceWords = Regex("""\b(how can|how do|how to|should|why|tips?|advice|suggest|help me|save more|reduce|budget|plan)\b""")

    fun answer(
        question: String,
        records: List<AskRecord>,
        reminders: List<AskReminder>,
        rules: Map<String, Category>,
        today: LocalDate,
        zone: ZoneId,
        nowMillis: Long,
    ): AskAnswer {
        val q = question.lowercase().trim()
        if (q.isEmpty() || adviceWords.containsMatchIn(q)) return notMine()
        if (asksAboutReminders(q)) return upcoming(reminders, nowMillis)

        val mine = has(q, *firstPerson)
        val category = categoryWords.entries.firstOrNull { (_, words) -> words.any { has(q, it) } }?.key
        val merchant = records.mapNotNull { it.merchant }.distinct().firstOrNull { name ->
            val lower = name.lowercase()
            lower.length >= 3 && (q.contains(lower) || has(q, lower.substringBefore(' ')))
        }
        val aboutMoney = has(q, *moneyWords)
        val hasPeriod = has(q, *periodWords) || q.contains("this year") || q.contains("all time")
        val isMine = when {
            mine && aboutMoney -> true
            has(q, *ownMoneyWords) -> true
            (category != null || merchant != null) && aboutMoney && hasPeriod -> true
            aboutMoney && hasPeriod && !q.startsWith("what is") -> true
            else -> false
        }
        if (!isMine) return notMine()

        val wantsIncome = has(q, *incomeWords)
        val period = periodOf(q, today)
        val type = if (wantsIncome) RecordType.INCOME else RecordType.EXPENSE
        val matching = records.filter { record ->
            val date = record.at.toLocalDate(zone)
            record.type == type &&
                (period.from == null || date >= period.from) &&
                (period.until == null || date < period.until) &&
                (category == null || Categoriser.of(record.merchant, rules) == category) &&
                (merchant == null || record.merchant == merchant)
        }.sortedByDescending { it.at }

        val verb = if (wantsIncome) "received" else "spent"
        val what = buildString {
            if (category != null) append(" on ${category.label}")
            if (merchant != null && category == null) append(if (wantsIncome) " from $merchant" else " on $merchant")
        }
        if (matching.isEmpty()) return AskAnswer("I found no money $verb$what ${period.label} in your records.")

        if (has(q, "biggest", "largest", "highest", "expensive", "most")) {
            val top = matching.maxBy { it.amountPaise }
            val name = top.merchant ?: "an unknown payee"
            return AskAnswer("Your biggest payment ${period.label} was ${formatRupees(top.amountPaise)} to $name.", listOf(top))
        }
        val total = formatRupees(matching.sumOf { it.amountPaise })
        val count = if (matching.size == 1) "1 payment" else "${matching.size} payments"
        return AskAnswer("You $verb $total$what ${period.label}, in $count.", matching.take(MAX_SOURCES))
    }

    /** A short line of month totals, with no merchants, for the AI, if the user turned that on. */
    fun monthTotalsLine(records: List<AskRecord>, rules: Map<String, Category>, today: LocalDate, zone: ZoneId): String {
        val items = records.map { MoneyItem(it.amountPaise, it.merchant, it.type, it.at) }
        val summary = Summaries.since(items, TimeRange.MONTH.startDate(today).startMillis(zone), rules)
        val categories = summary.spentByCategory.entries.filter { it.value > 0 }
            .joinToString { "${it.key.label} ${formatRupees(it.value)}" }
        return "This month the user spent ${formatRupees(summary.spentPaise)} (${categories.ifEmpty { "no categories" }}) " +
            "and received ${formatRupees(summary.receivedPaise)}."
    }

    private fun asksAboutReminders(q: String): Boolean =
        has(q, "reminder", "reminders", "deadlines", "upcoming") ||
            q.contains("due soon") || q.contains("is due") || q.contains("my tasks") || q.contains("my deadline")

    private fun upcoming(reminders: List<AskReminder>, nowMillis: Long): AskAnswer {
        val next = reminders.filter { !it.done && it.dueAt >= nowMillis }.sortedBy { it.dueAt }
        if (next.isEmpty()) return AskAnswer("You have no upcoming reminders.")
        val count = if (next.size == 1) "1 upcoming reminder" else "${next.size} upcoming reminders"
        return AskAnswer("You have $count.", reminders = next.take(MAX_SOURCES))
    }

    private fun periodOf(q: String, today: LocalDate): Period {
        val week = TimeRange.WEEK.startDate(today)
        val month = TimeRange.MONTH.startDate(today)
        return when {
            has(q, "yesterday") -> Period("yesterday", today.minusDays(1), today)
            has(q, "today") -> Period("today", today, null)
            q.contains("last week") -> Period("last week", week.minusWeeks(1), week)
            q.contains("last month") -> Period("last month", month.minusMonths(1), month)
            q.contains("week") -> Period("this week", week, null)
            q.contains("this year") -> Period("this year", today.withDayOfYear(1), null)
            q.contains("month") -> Period("this month", month, null)
            q.contains("all time") || has(q, "ever", "overall") -> Period("so far", null, null)
            else -> Period("this month", month, null)
        }
    }

    private fun has(q: String, vararg words: String): Boolean =
        words.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(q) }

    private fun notMine() = AskAnswer("", understood = false)

    private const val MAX_SOURCES = 5
}
