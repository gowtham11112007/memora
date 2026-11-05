package com.pulse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
                        Reminder(3, "Renew insurance", at(19), 0.7, false, false, "sms", origin = "LIC India"),
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

    @Test fun smallButtonsAreBigEnoughToTap() {
        val note = Note(1, "A note", "NOTE", now, false, "manual")
        rule.setContent {
            PulseTheme { BrainContent(BrainUiState(notes = listOf(note)), {}, {}, {}, {}, {}) }
        }
        rule.onNodeWithContentDescription("Delete note").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithContentDescription("Pin").assertWidthIsAtLeast(48.dp)
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

    @Test fun moneyFormOpensWithSavedValues() {
        rule.setContent {
            PulseTheme {
                androidx.compose.material3.Surface {
                    com.pulse.app.ui.home.EditMoneyForm(4235000, 1500000, "From your latest bank SMS", {}, { _, _ -> })
                }
            }
        }
        rule.onNodeWithText("42,350").assertExists()
        rule.onNodeWithText("15,000").assertExists()
        rule.onNodeWithText("Save changes").assertIsNotEnabled()
        rule.onNodeWithText("15,000").performTextReplacement("20,000")
        rule.onNodeWithText("Save changes").assertIsEnabled()
        rule.onRoot().captureRoboImage("build/shots/edit_money.png")
    }

    @Test fun lockScreen() = shot("lock") { com.pulse.app.LockContent {} }

    @Test fun homeWithAnswersWaiting() = shot("home_waiting") {
        Home(accessOn = true, state = home.copy(needsAnswer = 2, newReads = 3))
    }

    @Test fun bellSaysHowManyAreNew() {
        rule.setContent { PulseTheme { HomeContent(home.copy(newReads = 3), true, {}, {}, {}, {}, {}, {}, {}) } }
        rule.onNodeWithContentDescription("See notifications read, 3 new").assertExists()
    }

    @Test fun searchAndFiltersFindApps() {
        val sources = listOf(AppRow(AppItem("c", "PhonePe"), true))
        val others = listOf(AppRow(AppItem("d", "Gmail"), false))
        val chats = listOf(AppRow(AppItem("com.whatsapp", "WhatsApp"), false))
        rule.setContent {
            PulseTheme { ReadingContent(ReadingUiState(false, sources, others, chats), true, {}, {}, {}, { _, _ -> }, {}, {}) }
        }
        rule.onNodeWithText("Gmail").assertExists()
        rule.onNodeWithText("1 of 3 apps allowed").assertExists()

        rule.onNodeWithText("Allowed").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("PhonePe").assertExists()
        rule.onNodeWithText("Gmail").assertDoesNotExist()
        rule.onNodeWithText("All").performClick()
        rule.waitForIdle()

        rule.onNode(hasSetTextAction()).performTextInput("gma")
        rule.waitForIdle()
        rule.onNodeWithText("Gmail").assertExists()
        rule.onNodeWithText("PhonePe").assertDoesNotExist()
        rule.onNodeWithText("WhatsApp").assertDoesNotExist()

        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("nothing like this")
        rule.waitForIdle()
        rule.onNodeWithText("No app called \"nothing like this\" is installed.").assertExists()
    }

    private val activityState = com.pulse.app.ui.activity.ActivityUiState(
        entries = listOf(
            com.pulse.app.data.ActivityEntry(1, "com.googlepay", "Google Pay", "STORE", now, "₹250 to Swiggy"),
            com.pulse.app.data.ActivityEntry(2, "com.whatsapp", "Priya", "STORE", now, "Reminder: Parcel arriving tomorrow at 9 AM · Saved in Deliveries"),
            com.pulse.app.data.ActivityEntry(3, "com.whatsapp", "College Offers", "MUTED", now),
            com.pulse.app.data.ActivityEntry(4, "com.google.android.apps.messaging", "", "DROPPED_SENSITIVE", now),
            com.pulse.app.data.ActivityEntry(5, "org.telegram", "Hackathon Team", "NOTHING_USEFUL", now),
            com.pulse.app.data.ActivityEntry(6, "com.phonepe", "PhonePe", "DUPLICATE", now),
        ),
        rules = listOf(
            com.pulse.app.data.ContactRule("priya", "Priya", true),
            com.pulse.app.data.ContactRule("college offers", "College Offers", false),
        ),
    )

    @Config(qualifiers = "w412dp-h1180dp-xxhdpi")
    @Test fun activity() = shot("activity") {
        PulseShell(Route.ACTIVITY, {}) { com.pulse.app.ui.activity.ActivityContent(activityState, {}, {}, {}, {}) }
    }

    @Test fun activityDark() = shot("activity_dark", dark = true) {
        PulseShell(Route.ACTIVITY, {}) { com.pulse.app.ui.activity.ActivityContent(activityState, {}, {}, {}, {}) }
    }

    @Test fun activityEmpty() = shot("activity_empty") {
        PulseShell(Route.ACTIVITY, {}) { com.pulse.app.ui.activity.ActivityContent(com.pulse.app.ui.activity.ActivityUiState(), {}, {}, {}, {}) }
    }

    @Config(qualifiers = "w412dp-h1100dp-xxhdpi")
    @Test fun people() = shot("people") {
        PulseShell(Route.PEOPLE, {}) { com.pulse.app.ui.activity.PeopleContent(activityState, {}, { _, _ -> }, {}) }
    }

    @Test fun todo() = shot("todo") {
        val todos = listOf(
            com.pulse.app.data.Todo(1, "Please submit the signed form to the office", false, false, now, "com.whatsapp", "Priya"),
            com.pulse.app.data.Todo(2, "Buy a birthday gift for Amma", false, true, now, "manual"),
            com.pulse.app.data.Todo(3, "Finish the hackathon slides", false, true, now, "manual"),
            com.pulse.app.data.Todo(4, "Back up my phone", true, true, now, "manual"),
        )
        PulseShell(Route.TODO, {}) { com.pulse.app.ui.todo.TodoContent(todos, {}, {}, {}, {}, {}) }
    }

    @Test fun recordsLight() = shot("records_light") {
        PulseShell(Route.RECORDS, {}) { RecordsContent(RecordsUiState(records = records), {}, {}, {}) }
    }

    @Test fun recordsRaw() = shot("records_raw") {
        val raw = RawEvent(1, "gpay", "Google Pay", "Paid ₹250.00 to Swiggy. UPI Ref No. ••••8901", now, now)
        PulseShell(Route.RECORDS, {}) { RecordsContent(RecordsUiState(RecordFilter.RAW, raw = listOf(raw)), {}, {}, {}) }
    }
}
