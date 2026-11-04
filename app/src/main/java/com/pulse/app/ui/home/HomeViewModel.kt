package com.pulse.app.ui.home

import androidx.lifecycle.viewModelScope
import com.pulse.app.PulseGraph
import com.pulse.app.data.MoneyRecord
import com.pulse.app.domain.Balance
import com.pulse.app.domain.BalanceView
import com.pulse.app.domain.Category
import com.pulse.app.data.Reminder
import com.pulse.app.domain.DayTotal
import com.pulse.app.domain.Insights
import com.pulse.app.domain.MoneyItem
import com.pulse.app.domain.Subscriptions
import com.pulse.app.domain.Summaries
import com.pulse.app.domain.TimeRange
import com.pulse.app.domain.startDate
import com.pulse.app.domain.startMillis
import com.pulse.app.ui.components.RecordActionsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class HomeUiState(
    val range: TimeRange = TimeRange.TODAY,
    val spentPaise: Long = 0,
    val receivedPaise: Long = 0,
    val byCategory: Map<Category, Long> = emptyMap(),
    val week: List<DayTotal> = emptyList(),
    val lastWeek: List<DayTotal> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val recent: List<MoneyRecord> = emptyList(),
    val rules: Map<String, Category> = emptyMap(),
    val balance: BalanceView? = null,
    val deductedTodayPaise: Long = 0,
    val receivedTodayPaise: Long = 0,
    val spentMonthPaise: Long = 0,
    val budgetPaise: Long = 0,
    val greeting: String = "Hello",
    val isDemo: Boolean = false,
    val nextReminder: Reminder? = null,
    val insights: List<String> = emptyList(),
)

class HomeViewModel(private val app: PulseGraph) : RecordActionsViewModel(app) {
    private val range = MutableStateFlow(TimeRange.TODAY)
    private val clockHour = MutableStateFlow(currentHour())

    private fun currentHour() = "${LocalDate.now()}-${LocalTime.now().hour}"

    /** Recalculates totals and the greeting when the hour or day has changed. */
    fun refreshClock() {
        clockHour.value = currentHour()
    }
    private val zone = ZoneId.systemDefault()

    val state = combine(
        combine(range, clockHour) { selected, _ -> selected },
        graph.db.moneyRecordDao().observeAll(),
        graph.db.merchantRuleDao().observeAll(),
        app.moneyPrefs.state,
        graph.db.reminderDao().observeAll(),
    ) { selected, records, ruleRows, money, reminders ->
        val rules = ruleRows.associate { it.merchant to Category.valueOf(it.category) }
        val items = records.map { MoneyItem(it.amountPaise, it.merchant, it.type, it.occurredAt) }
        val today = LocalDate.now()
        val summary = Summaries.since(items, selected.startDate(today).startMillis(zone), rules)
        val todaySummary = Summaries.since(items, today.startMillis(zone), rules)
        val monthSummary = Summaries.since(items, TimeRange.MONTH.startDate(today).startMillis(zone), rules)
        val nowMillis = System.currentTimeMillis()
        HomeUiState(
            greeting = when (LocalTime.now().hour) {
                in 5..11 -> "Good morning"
                in 12..16 -> "Good afternoon"
                else -> "Good evening"
            },
            isDemo = app.demo,
            nextReminder = reminders.filter { it.confirmed && !it.done && it.dueAt >= nowMillis }.minByOrNull { it.dueAt },
            insights = listOfNotNull(
                Insights.weekChange(items, today, zone),
                Insights.topCategory(monthSummary),
                Subscriptions.summary(items, today, zone),
            ),
            balance = Balance.compute(money, items),
            deductedTodayPaise = todaySummary.spentPaise,
            receivedTodayPaise = todaySummary.receivedPaise,
            spentMonthPaise = monthSummary.spentPaise,
            budgetPaise = money.budgetPaise,
            range = selected,
            spentPaise = summary.spentPaise,
            receivedPaise = summary.receivedPaise,
            byCategory = summary.spentByCategory,
            week = Summaries.week(items, today, zone),
            lastWeek = Summaries.week(items, today.minusWeeks(1), zone),
            today = today,
            recent = records.take(4),
            rules = rules,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun saveMoney(balancePaise: Long?, budgetPaise: Long?) {
        balancePaise?.let { app.moneyPrefs.setOpening(it, System.currentTimeMillis()) }
        budgetPaise?.let(app.moneyPrefs::setBudget)
    }

    fun selectRange(value: TimeRange) {
        range.value = value
    }
}
