package com.pulse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.pulse.app.PulseShell
import com.pulse.app.Route
import com.pulse.app.data.AiConfig
import com.pulse.app.data.EmailConfig
import com.pulse.app.data.MoneyRecord
import com.pulse.app.data.Note
import com.pulse.app.data.RawEvent
import com.pulse.app.data.Reminder
import com.pulse.app.domain.AskAnswer
import com.pulse.app.domain.AskRecord
import com.pulse.app.domain.BalanceView
import com.pulse.app.domain.Category
import com.pulse.app.domain.DayTotal
import com.pulse.app.domain.RecordType
import com.pulse.app.domain.TimeRange
import com.pulse.app.domain.startDate
import com.pulse.app.ui.ask.AnswerFrom
import com.pulse.app.ui.ask.AskContent
import com.pulse.app.ui.ask.ChatTurn
import com.pulse.app.ui.brain.BrainContent
import com.pulse.app.ui.brain.BrainUiState
import com.pulse.app.ui.calendar.CalendarContent
import com.pulse.app.ui.calendar.CalendarUiState
import com.pulse.app.ui.home.HomeContent
import com.pulse.app.ui.home.HomeUiState
import com.pulse.app.ui.records.RecordFilter
import com.pulse.app.ui.records.RecordsContent
import com.pulse.app.ui.records.RecordsUiState
import com.pulse.app.ui.settings.AiContent
import com.pulse.app.ui.settings.AppItem
import com.pulse.app.ui.settings.AppRow
import com.pulse.app.ui.settings.ReadingContent
import com.pulse.app.ui.settings.ReadingUiState
import com.pulse.app.ui.settings.SettingsContent
import com.pulse.app.ui.settings.SettingsUiState
import com.pulse.app.ui.theme.PulseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/** Renders screens with sample data. PNGs are only written with: ./gradlew testDebugUnitTest -Pshots */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class ScreensScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val today = LocalDate.now()
    private val records = listOf(
        MoneyRecord(1, "gpay", 25000, "Swiggy", RecordType.EXPENSE, now),
        MoneyRecord(2, "gpay", 500000, "Rahul Kumar", RecordType.INCOME, now),
        MoneyRecord(3, "gpay", 120000, "Uber India", RecordType.EXPENSE, now),
        MoneyRecord(4, "phonepe", 89950, "Amazon", RecordType.EXPENSE, now),
    )
    private val monday = TimeRange.WEEK.startDate(today)
    private fun week(start: LocalDate, values: List<Long>) = values.mapIndexed { i, v -> DayTotal(start.plusDays(i.toLong()), v) }
    private val home = HomeUiState(
        spentPaise = 145000,
        receivedPaise = 500000,
        byCategory = mapOf(Category.FOOD to 84000, Category.TRANSPORT to 31000, Category.SHOPPING to 30000, Category.BILLS to 0, Category.OTHER to 0),
        week = week(monday, listOf(60000, 30000, 52000, 98000, 70000, 41000, 100000)),
        lastWeek = week(monday.minusWeeks(1), listOf(80000, 52000, 40000, 60000, 90000, 75000, 55000)),
        today = today,
        recent = records,
        balance = BalanceView(4235000, "From your latest bank SMS"),
        deductedTodayPaise = 145000,
        receivedTodayPaise = 500000,
        spentMonthPaise = 1240000,
        budgetPaise = 2000000,
        greeting = "Good evening",
        isDemo = true,
        nextReminder = Reminder(9, "Pay electricity bill", now + 86_400_000, 0.9, true, false, "demo"),
        insights = listOf("You've spent 12% less than this time last week.", "Food is your biggest spend this month, at ₹840."),
    )

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent { PulseTheme(darkTheme = dark) { content() } }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/shots/$name.png")
    }

    @Composable
    private fun Home(accessOn: Boolean, state: HomeUiState = home) = PulseShell(Route.HOME, {}) {
        HomeContent(state, accessOn, {}, {}, {}, {}, {}, {}, {})
    }

    @Composable
    private fun Calendar() {
        val at = { hours: Long -> today.plusDays(2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + hours * 3_600_000 }
        PulseShell(Route.CALENDAR, {}) {
            CalendarContent(
                CalendarUiState(
                    selected = today.plusDays(2),
                    today = today,
                    reminders = listOf(
                        Reminder(1, "Electricity bill due", at(10), 0.9, true, false, "sms"),
                        Reminder(2, "Submit lab record", at(17), 1.0, true, true, "manual"),
                        Reminder(3, "Renew insurance", at(19), 0.7, false, false, "sms"),
                    ),
                    spentByDay = mapOf(today to 145000L, today.minusDays(1) to 50000L),
                ),
                {}, {}, {}, {}, {}, {},
            )
        }
    }

    @Config(qualifiers = "w412dp-h2480dp-xxhdpi")
    @Test fun homeFullLight() = shot("home_full_light") { Home(accessOn = false) }

    @Config(qualifiers = "w412dp-h2480dp-xxhdpi")
    @Test fun homeFullDark() = shot("home_full_dark", dark = true) { Home(accessOn = true) }

    @Config(qualifiers = "w240dp-h240dp-xxhdpi")
    @Test fun appIcon() = shot("app_icon") {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier
                .size(240.dp)
                .background(androidx.compose.ui.res.colorResource(com.pulse.app.R.color.ic_launcher_background)),
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.pulse.app.R.drawable.ic_launcher_foreground),
                null,
                androidx.compose.ui.Modifier.size(240.dp),
            )
        }
    }

    @Test fun welcome() = shot("welcome") { com.pulse.app.ui.onboarding.OnboardingContent({}, {}, {}) }

    @Test fun welcomeSetup() = shot("welcome_setup") { com.pulse.app.ui.onboarding.OnboardingContent({}, {}, {}, startPage = 2) }

    @Test fun homeFirstScreen() = shot("home_first_screen") { Home(accessOn = true) }

    @Test fun homeEmpty() = shot("home_empty") { Home(accessOn = false, state = HomeUiState()) }

    @Config(qualifiers = "w412dp-h1240dp-xxhdpi")
    @Test fun calendarLight() = shot("calendar_light") { Calendar() }

    @Test fun askEmpty() = shot("ask_empty") { PulseShell(Route.ASK, {}) { AskContent(emptyList(), false, {}, {}) } }

    @Test fun askAnswer() = shot("ask_answer") {
        val records = AskAnswer(
            "You spent ₹590 on Food this week, in 2 payments.",
            listOf(
                AskRecord(1, 25000, "Swiggy", RecordType.EXPENSE, now),
                AskRecord(2, 34000, "Zomato", RecordType.EXPENSE, now),
            ),
        )
        PulseShell(Route.ASK, {}) {
            AskContent(
                listOf(
                    ChatTurn(0, "How much did I spend on food this week?", records, AnswerFrom.RECORDS),
                    ChatTurn(1, "15% of 200", AskAnswer("30"), AnswerFrom.OFFLINE),
                    ChatTurn(2, "Explain how UPI works"),
                ),
                true, {}, {},
            )
        }
    }

    @Test fun brain() = shot("brain") {
        val notes = listOf(
            Note(1, "My Wi-Fi router is in the hall", "NOTE", now, true, "manual"),
            Note(2, "Your electricity bill is due on 15 Nov", "BILL", now, false, "sms"),
            Note(3, "Your order has shipped", "DELIVERY", now, false, "amazon"),
            Note(4, "PNR 12•••• for train 12601", "TICKET", now, false, "sms"),
            Note(5, "Interview on Friday", "EVENT", now, false, "gmail"),
        )
        PulseShell(Route.BRAIN, {}) { BrainContent(BrainUiState(notes = notes), {}, {}, {}, {}, {}) }
    }

    @Test fun settingsLight() = shot("settings_light") {
        PulseShell(Route.SETTINGS, {}) { SettingsContent(SettingsUiState(42, 11, 9, 3), true, false, {}, {}, {}) }
    }

    @Config(qualifiers = "w412dp-h1320dp-xxhdpi")
    @Test fun aiSettings() = shot("ai_settings") {
        PulseShell(Route.AI, {}) {
            AiContent(AiConfig("secret"), EmailConfig("re_secret", "me@example.com", true), "Connected. Gemini answered.", null, {}, {}, {}, {}, {})
        }
    }

    @Config(qualifiers = "w412dp-h1180dp-xxhdpi")
    @Test fun readingOn() = shot("reading_on") {
        val sources = listOf(
            AppRow(AppItem("com.google.android.apps.messaging", "Messages"), false),
            AppRow(AppItem("a", "Google Pay"), true, now),
            AppRow(AppItem("b", "Paytm"), true, now),
            AppRow(AppItem("c", "PhonePe"), false),
        )
        PulseShell(Route.READING, {}) {
            ReadingContent(ReadingUiState(false, sources, listOf(AppRow(AppItem("d", "Gmail"), false))), true, {}, {}, {}, { _, _ -> }, {}, {})
        }
    }

    @Test fun recordsLight() = shot("records_light") {
        PulseShell(Route.RECORDS, {}) { RecordsContent(RecordsUiState(records = records), {}, {}, {}) }
    }

    @Test fun recordsRaw() = shot("records_raw") {
        val raw = RawEvent(1, "gpay", "Google Pay", "Paid ₹250.00 to Swiggy. UPI Ref No. ••••8901", now, now)
        PulseShell(Route.RECORDS, {}) { RecordsContent(RecordsUiState(RecordFilter.RAW, raw = listOf(raw)), {}, {}, {}) }
    }
}
