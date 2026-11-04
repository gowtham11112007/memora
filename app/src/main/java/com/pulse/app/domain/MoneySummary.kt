package com.pulse.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class Category(val label: String) {
    FOOD("Food"),
    TRANSPORT("Transport"),
    SHOPPING("Shopping"),
    BILLS("Bills"),
    OTHER("Other"),
}

/** Merchant dictionary. A rule the user saved by correcting a category always wins. */
object Categoriser {
    private val dictionary = mapOf(
        Category.FOOD to listOf(
            "swiggy", "zomato", "domino", "pizza", "kfc", "mcdonald", "cafe", "tea", "coffee", "restaurant", "bakery", "biryani",
        ),
        Category.TRANSPORT to listOf(
            "uber", "ola", "rapido", "metro", "irctc", "railway", "petrol", "fuel", "redbus", "fastag", "indian oil",
        ),
        Category.SHOPPING to listOf(
            "amazon", "flipkart", "myntra", "ajio", "meesho", "reliance", "bigbasket", "blinkit", "zepto", "dmart",
        ),
        Category.BILLS to listOf(
            "electricity", "airtel", "jio", "bsnl", "recharge", "netflix", "spotify", "broadband", "gas", "insurance", "rent",
        ),
    )

    fun of(merchant: String?, rules: Map<String, Category> = emptyMap()): Category {
        val name = merchant?.lowercase()?.trim() ?: return Category.OTHER
        rules[name]?.let { return it }
        return dictionary.entries.firstOrNull { (_, words) -> words.any { name.contains(it) } }?.key ?: Category.OTHER
    }
}

enum class TimeRange { TODAY, WEEK, MONTH }

/** Calendar periods: a week starts on Monday, a month on the 1st. */
fun TimeRange.startDate(today: LocalDate): LocalDate = when (this) {
    TimeRange.TODAY -> today
    TimeRange.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    TimeRange.MONTH -> today.withDayOfMonth(1)
}

fun LocalDate.startMillis(zone: ZoneId): Long = atStartOfDay(zone).toInstant().toEpochMilli()

fun Long.toLocalDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

/** A small view of a record, so totals can be tested without the database. */
data class MoneyItem(val amountPaise: Long, val merchant: String?, val type: RecordType, val at: Long)

data class MoneySummary(
    val spentPaise: Long,
    val receivedPaise: Long,
    val spentByCategory: Map<Category, Long>,
)

data class DayTotal(val date: LocalDate, val spentPaise: Long)

/** Plain-language observations. Each comes straight from the user's records and nothing is guessed. */
object Insights {
    /** Compares this week so far with the same days last week. Null if there's too little to compare. */
    fun weekChange(items: List<MoneyItem>, today: LocalDate, zone: ZoneId): String? {
        val monday = TimeRange.WEEK.startDate(today)
        val spending = items.filter { it.type == RecordType.EXPENSE }
        val thisWeek = spending.filter { it.at.toLocalDate(zone) in monday..today }.sumOf { it.amountPaise }
        val lastMonday = monday.minusWeeks(1)
        val lastWeek = spending.filter { it.at.toLocalDate(zone) in lastMonday..today.minusWeeks(1) }.sumOf { it.amountPaise }
        if (thisWeek == 0L || lastWeek == 0L) return null
        val percent = ((thisWeek - lastWeek) * 100 / lastWeek).toInt()
        return when {
            percent >= 5 -> "You've spent $percent% more than this time last week."
            percent <= -5 -> "You've spent ${-percent}% less than this time last week."
            else -> "Your spending is about the same as this time last week."
        }
    }

    /** The category taking the most this month. */
    fun topCategory(summary: MoneySummary): String? {
        val top = summary.spentByCategory.filterKeys { it != Category.OTHER }.maxByOrNull { it.value } ?: return null
        return if (top.value > 0) "${top.key.label} is your biggest spend this month, at ${formatRupees(top.value)}." else null
    }
}

object Summaries {
    fun since(items: List<MoneyItem>, from: Long, rules: Map<String, Category>): MoneySummary {
        val inRange = items.filter { it.at >= from }
        val spending = inRange.filter { it.type == RecordType.EXPENSE }
        val byCategory = spending.groupBy { Categoriser.of(it.merchant, rules) }.mapValues { (_, list) -> list.sumOf { it.amountPaise } }
        return MoneySummary(
            spentPaise = spending.sumOf { it.amountPaise },
            receivedPaise = inRange.filter { it.type == RecordType.INCOME }.sumOf { it.amountPaise },
            spentByCategory = Category.entries.associateWith { byCategory[it] ?: 0L },
        )
    }

    /** Monday to Sunday of the week containing [today]. */
    fun week(items: List<MoneyItem>, today: LocalDate, zone: ZoneId): List<DayTotal> {
        val monday = TimeRange.WEEK.startDate(today)
        val spentByDay = items.filter { it.type == RecordType.EXPENSE }
            .groupBy { it.at.toLocalDate(zone) }
            .mapValues { (_, list) -> list.sumOf { it.amountPaise } }
        return (0L..6L).map { offset ->
            val date = monday.plusDays(offset)
            DayTotal(date, spentByDay[date] ?: 0L)
        }
    }
}
