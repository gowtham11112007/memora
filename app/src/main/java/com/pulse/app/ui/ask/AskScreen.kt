package com.pulse.app.ui.ask

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.data.GeminiClient
import com.pulse.app.data.LlmMessage
import com.pulse.app.data.LlmResult
import com.pulse.app.data.MoneyRecord
import com.pulse.app.data.Note
import com.pulse.app.data.Reminder
import com.pulse.app.domain.AskAnswer
import com.pulse.app.domain.AskEngine
import com.pulse.app.domain.AskMoney
import com.pulse.app.domain.Balance
import com.pulse.app.domain.Lookups
import com.pulse.app.domain.MoneyItem
import com.pulse.app.domain.AskRecord
import com.pulse.app.domain.AskReminder
import com.pulse.app.domain.Category
import com.pulse.app.data.Todo
import com.pulse.app.domain.ChatCommand
import com.pulse.app.domain.ChatCommands
import com.pulse.app.domain.DeadlineExtractor
import com.pulse.app.domain.NoteKind
import com.pulse.app.domain.OfflineAnswers
import com.pulse.app.domain.PrivacyFilter
import com.pulse.app.domain.RecordType
import com.pulse.app.domain.formatRupees
import com.pulse.app.ui.components.ArrowChip
import com.pulse.app.ui.components.CircleIconButton
import com.pulse.app.ui.components.bounceClick
import com.pulse.app.ui.components.formatCaptureTime
import com.pulse.app.ui.components.riseIn
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

enum class AnswerFrom { RECORDS, OFFLINE, AI, NOTICE }

/** A null answer means it is still being worked out, so the typing dots show. */
data class ChatTurn(
    val id: Int,
    val question: String,
    val answer: AskAnswer? = null,
    val from: AnswerFrom = AnswerFrom.RECORDS,
)

private const val SYSTEM_PROMPT =
    "You are Memora, a helpful assistant inside a personal Android app used in India. " +
        "Answer any question directly in one or two short sentences, in plain text without markdown. Give more only if asked. " +
        "Don't bring up which company or model powers you. If the user asks, say you are Memora's assistant, running on Google's AI. " +
        "You cannot see the user's records unless they are given in this message, so never invent their amounts or dates."

/** The conversation lives in memory only and disappears when the app closes. */
class AskViewModel(private val graph: PulseGraph) : ViewModel() {
    val turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    val aiConnected = MutableStateFlow(graph.aiSettings.load().isSet)

    fun refresh() {
        aiConnected.value = graph.aiSettings.load().isSet
    }

    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty()) return
        val id = turns.value.size
        turns.value = turns.value + ChatTurn(id, text)
        viewModelScope.launch {
            val db = graph.db
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val records = db.moneyRecordDao().all().map { AskRecord(it.id, it.amountPaise, it.merchant, it.type, it.occurredAt) }
            val reminders = db.reminderDao().all().filter { it.confirmed }.map { AskReminder(it.id, it.title, it.dueAt, it.done) }
            val rules = db.merchantRuleDao().all().associate { it.merchant to Category.valueOf(it.category) }

            // 1. Things to do: remember, remind, find. These never leave the phone.
            ChatCommands.parse(text)?.let { command ->
                finish(id, runCommand(command), AnswerFrom.RECORDS)
                return@launch
            }
            // 2. Questions about the user's own records.
            val settings = graph.moneyPrefs.state.value
            val items = records.map { MoneyItem(it.amountPaise, it.merchant, it.type, it.at) }
            val money = AskMoney(Balance.compute(settings, items), settings.budgetPaise)
            val local = AskEngine.answer(text, records, reminders, rules, today, zone, System.currentTimeMillis(), money)
            if (local.understood) {
                finish(id, local, AnswerFrom.RECORDS)
                return@launch
            }
            // "Where is my passport?" is about the user's own life, so it searches notes, not the internet.
            Lookups.query(text)?.let { query ->
                finish(id, runCommand(ChatCommand.Find(query)), AnswerFrom.RECORDS)
                return@launch
            }
            // 3. Built-in answers: maths, dates, units, greetings, help.
            OfflineAnswers.answer(text, LocalDateTime.now())?.let {
                finish(id, AskAnswer(it), AnswerFrom.OFFLINE)
                return@launch
            }
            // 4. Everything else goes to Gemini, if the user added a key.
            val config = graph.aiSettings.load()
            if (!config.isSet) {
                finish(
                    id,
                    AskAnswer("I can't answer that one yet. Ask me about your money, reminders or notes."),
                    AnswerFrom.NOTICE,
                )
                return@launch
            }
            val totals = if (config.shareTotals) AskEngine.monthTotalsLine(records, rules, today, zone) else null
            val system = if (totals != null) "$SYSTEM_PROMPT $totals" else SYSTEM_PROMPT
            val result = withContext(Dispatchers.IO) { GeminiClient(config.apiKey).chat(system, buildMessages(text)) }
            when (result) {
                is LlmResult.Reply -> finish(id, AskAnswer(result.text.replace("**", "")), AnswerFrom.AI)
                is LlmResult.Failed -> finish(id, AskAnswer(result.reason), AnswerFrom.NOTICE)
            }
        }
    }

    private suspend fun runCommand(command: ChatCommand): AskAnswer = when (command) {
        is ChatCommand.Remember -> {
            if (PrivacyFilter.isSensitive(command.text)) {
                AskAnswer("That looks like a password or PIN, so I won't store it.")
            } else {
                graph.db.noteDao().insert(
                    Note(
                        text = command.text.replaceFirstChar { it.uppercase() },
                        kind = NoteKind.NOTE.name,
                        createdAt = System.currentTimeMillis(),
                        source = MoneyRecord.MANUAL,
                    ),
                )
                AskAnswer("Saved to your Second Brain.")
            }
        }
        is ChatCommand.Remind -> {
            val deadline = DeadlineExtractor.extract(null, command.text, LocalDateTime.now())
            if (deadline == null) {
                AskAnswer("When should I remind you? Try \"remind me to pay rent tomorrow at 9 am\".")
            } else {
                val title = ChatCommands.reminderTitle(command.text)
                val dueAt = deadline.dueAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val reminder = Reminder(title = title, dueAt = dueAt, confidence = 1.0, confirmed = true, source = MoneyRecord.MANUAL)
                graph.reminders.schedule(reminder.copy(id = graph.db.reminderDao().insert(reminder)))
                AskAnswer(
                    "Done. I'll remind you ${formatCaptureTime(dueAt)}.",
                    reminders = listOf(AskReminder(0, title, dueAt, false)),
                )
            }
        }
        is ChatCommand.Find -> {
            val words = command.query.lowercase().split(" ").filter { it.length > 2 }.ifEmpty { listOf(command.query.lowercase()) }
            val found = graph.db.noteDao().all().filter { note -> words.any { note.text.lowercase().contains(it) } }
            if (found.isEmpty()) {
                AskAnswer("I found nothing about \"${command.query}\" in your Second Brain.")
            } else {
                AskAnswer("Here's what I found.", notes = found.take(5).map { it.text })
            }
        }
        is ChatCommand.AddTodo -> {
            if (PrivacyFilter.isSensitive(command.text)) {
                AskAnswer("That looks like a password or PIN, so I won't store it.")
            } else {
                val text = command.text.replaceFirstChar { it.uppercase() }
                graph.db.todoDao().insert(Todo(text = text, createdAt = System.currentTimeMillis(), source = MoneyRecord.MANUAL))
                AskAnswer("Added to your to-do list: $text.")
            }
        }
        ChatCommand.ListTodos -> {
            val open = graph.db.todoDao().all().filter { it.confirmed && !it.done }
            if (open.isEmpty()) {
                AskAnswer("Your to-do list is empty.")
            } else {
                AskAnswer(if (open.size == 1) "You have 1 thing to do." else "You have ${open.size} things to do.", notes = open.take(10).map { it.text })
            }
        }
    }

    /** Only what the user typed and earlier Gemini replies. Never notification text or record details. */
    private fun buildMessages(question: String): List<LlmMessage> {
        val history = turns.value.filter { it.from == AnswerFrom.AI && it.answer != null }.takeLast(HISTORY_TURNS)
        return buildList {
            history.forEach {
                add(LlmMessage("user", it.question))
                add(LlmMessage("model", it.answer!!.text))
            }
            add(LlmMessage("user", question))
        }
    }

    private fun finish(id: Int, answer: AskAnswer, from: AnswerFrom) {
        turns.value = turns.value.map { if (it.id == id) it.copy(answer = answer, from = from) else it }
    }

    private companion object {
        const val HISTORY_TURNS = 4
    }
}

@Composable
fun AskScreen(onOpenAi: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    val vm: AskViewModel = viewModel(factory = viewModelFactory { initializer { AskViewModel(graph) } })
    val turns by vm.turns.collectAsStateWithLifecycle()
    val connected by vm.aiConnected.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    AskContent(turns, connected, vm::ask, onOpenAi)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskContent(turns: List<ChatTurn>, aiConnected: Boolean, onAsk: (String) -> Unit, onOpenAi: () -> Unit) {
    val listState = rememberLazyListState()
    val lastAnswered = turns.count { it.answer != null }
    LaunchedEffect(turns.size, lastAnswered) {
        // The greeting is item 0, so the newest turn is at index turns.size.
        if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size)
    }
    val keyboardOpen = WindowInsets.isImeVisible
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(bottom = if (keyboardOpen) 8.dp else 104.dp),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Greeting(aiConnected, onOpenAi, Modifier.riseIn(0)) }
            if (turns.isEmpty()) item { Suggestions(onAsk, Modifier.riseIn(1)) }
            items(turns, key = { it.id }) { turn ->
                Column(Modifier.animateItem().riseIn(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuestionBubble(turn.question)
                    if (turn.answer == null) TypingDots() else AnswerCard(turn.answer, turn.from)
                }
            }
        }
        InputRow(onAsk, Modifier.padding(horizontal = 20.dp))
    }
}

@Composable
private fun Greeting(aiConnected: Boolean, onOpenAi: () -> Unit, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = Lime, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!aiConnected) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Ink.copy(alpha = 0.28f)))
                    Text("Offline answers only", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = Ink)
                    Row(
                        Modifier
                            .bounceClick(onClick = onOpenAi)
                            .clip(RoundedCornerShape(50))
                            .background(Ink)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text("Connect", style = MaterialTheme.typography.labelMedium, color = Lime)
                    }
                }
            }
            Text("Hi there!\nHow can I help?", style = MaterialTheme.typography.headlineLarge, color = Ink)
        }
    }
}

@Composable
private fun Suggestions(onAsk: (String) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tiles = listOf(
        "How much did I spend this week?" to scheme.surface,
        "Biggest expense this month?" to scheme.surface,
        "What is due soon?" to scheme.surface,
        "Remember that my Wi-Fi router is in the hall" to scheme.secondaryContainer,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (text, fill) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .height(126.dp)
                            .bounceClick { onAsk(text) }
                            .clip(MaterialTheme.shapes.large)
                            .background(fill)
                            .padding(14.dp),
                    ) {
                        ArrowChip(scheme.inverseSurface, scheme.inverseOnSurface, size = 34.dp)
                        Spacer(Modifier.weight(1f))
                        Text(text, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionBubble(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.weight(1f))
        Surface(
            shape = RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.padding(start = 48.dp),
        ) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Surface(
        shape = RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        tween(500, delayMillis = i * 150, easing = LinearEasing),
                        RepeatMode.Reverse,
                    ),
                    label = "dot$i",
                )
                Box(Modifier.size(8.dp).graphicsLayer { this.alpha = alpha }.clip(CircleShape).background(Lime))
            }
        }
    }
}

@Composable
private fun AnswerCard(answer: AskAnswer, from: AnswerFrom) {
    val ink = MaterialTheme.colorScheme.inverseOnSurface
    Surface(
        shape = RoundedCornerShape(26.dp, 26.dp, 26.dp, 6.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (from != AnswerFrom.NOTICE) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (from == AnswerFrom.AI) Lime else ink.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        when (from) {
                            AnswerFrom.AI -> "Memora AI"
                            AnswerFrom.OFFLINE -> "Built in"
                            else -> "On your phone"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (from == AnswerFrom.AI) Ink else ink,
                    )
                }
            }
            Text(answer.text, style = MaterialTheme.typography.bodyLarge, color = ink)
            answer.sources.forEach { source ->
                val sign = if (source.type == RecordType.INCOME) "+" else ""
                SourceRow(source.merchant ?: "Unknown", formatCaptureTime(source.at), sign + formatRupees(source.amountPaise), ink)
            }
            answer.reminders.forEach { SourceRow(it.title, formatCaptureTime(it.dueAt), null, ink) }
            answer.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, color = ink) }
        }
    }
}

@Composable
private fun SourceRow(title: String, subtitle: String, trailing: String?, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.65f))
        }
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium, color = ink)
    }
}

@Composable
private fun InputRow(onAsk: (String) -> Unit, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    val send = {
        onAsk(text)
        text = ""
    }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(scheme.surface)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { field ->
                if (text.isEmpty()) {
                    Text("Ask me anything", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1)
                }
                field()
            },
        )
        CircleIconButton(Icons.AutoMirrored.Rounded.Send, "Send", send, size = 46, container = scheme.inverseSurface, tint = Lime)
    }
}
