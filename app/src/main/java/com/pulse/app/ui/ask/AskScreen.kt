package com.pulse.app.ui.ask

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.style.TextOverflow
import com.pulse.app.data.ChatEntry
import com.pulse.app.domain.ChatMemory
import com.pulse.app.domain.ChatSummary
import com.pulse.app.domain.PastTurn
import com.pulse.app.ui.components.NoteCard
import com.pulse.app.ui.components.ScreenHeader
import com.pulse.app.ui.components.SectionLabel
import com.pulse.app.ui.components.rememberUndo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
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
import com.pulse.app.data.AiReading
import com.pulse.app.data.SmartReader
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
import com.pulse.app.domain.BrainContext
import com.pulse.app.domain.BrainLine
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
        "You can't set reminders, save notes or change anything in the app, so never say you did. If asked, tell the user to say: remind me to call mom tomorrow at 5 pm. " +
        "Don't bring up which company or model powers you. If the user asks, say you are Memora's assistant, running on cloud AI services from Google and Groq. " +
        "You cannot see the user's records unless they are given in this message, so never invent their amounts or dates."

/** Chats are saved on the phone. Coming back within a few hours continues the same one. */
class AskViewModel(private val graph: PulseGraph) : ViewModel() {
    val turns = MutableStateFlow<List<ChatTurn>>(emptyList())
    private val chats = graph.db.chatDao()
    private var conversation = System.currentTimeMillis()
    private var nextId = 0
    private val waiting = mutableMapOf<Int, Pair<Long, String>>()

    val history = chats.observeAll()
        .map { rows -> ChatMemory.conversations(rows.map(::pastTurn)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val latest = chats.latest()
            if (latest != null && System.currentTimeMillis() - latest.at < CONTINUE_MS && turns.value.isEmpty()) show(latest.conversation)
        }
        // "Delete my data" empties the table, so the open chat must go too.
        viewModelScope.launch {
            var hadChats = false
            chats.observeAll().collect { rows ->
                if (hadChats && rows.isEmpty()) newChat()
                hadChats = rows.isNotEmpty()
            }
        }
    }

    private fun pastTurn(row: ChatEntry) = PastTurn(row.conversation, row.question, row.answer, row.at)

    private suspend fun show(id: Long) {
        conversation = id
        turns.value = chats.conversation(id).map { row ->
            val from = AnswerFrom.entries.firstOrNull { it.name == row.source } ?: AnswerFrom.RECORDS
            ChatTurn(nextId++, row.question, AskAnswer(row.answer, notes = row.notes.split("\n").filter { it.isNotBlank() }), from)
        }
    }

    fun newChat() {
        conversation = System.currentTimeMillis()
        turns.value = emptyList()
    }

    fun open(id: Long) {
        viewModelScope.launch { show(id) }
    }

    fun delete(id: Long, onDeleted: (List<ChatEntry>) -> Unit) {
        viewModelScope.launch {
            val rows = chats.conversation(id)
            chats.deleteConversation(id)
            if (id == conversation) newChat()
            onDeleted(rows)
        }
    }

    fun restore(rows: List<ChatEntry>) {
        viewModelScope.launch { chats.insertAll(rows) }
    }

    fun clearAll() {
        viewModelScope.launch {
            chats.deleteAll()
            newChat()
        }
    }
    val aiConnected = MutableStateFlow(graph.ai.hasAi())

    fun refresh() {
        aiConnected.value = graph.ai.hasAi()
    }

    /** A slow network call can't leave the chat loading forever: the answer gives up, even if the call hasn't. */
    private suspend fun <T> withinLimit(fallback: T, call: () -> T): T {
        val running = viewModelScope.async(Dispatchers.IO) { runCatching(call).getOrDefault(fallback) }
        return withTimeoutOrNull(WAIT_LIMIT_MS) { running.await() } ?: fallback
    }

    fun ask(question: String) {
        val text = question.trim()
        if (text.isEmpty()) return
        val id = nextId++
        waiting[id] = conversation to text
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
            // If the AI may read notes, it answers in a sentence below. Otherwise the phone searches them.
            val readsBrain = graph.ai.hasAi() && graph.aiSettings.isBrainInChat()
            val notes = db.noteDao().all().map { BrainLine(it.text, it.kind, it.createdAt, it.pinned) }
            val lookup = Lookups.query(text)
            if (lookup != null && (!readsBrain || notes.isEmpty())) {
                finish(id, runCommand(ChatCommand.Find(lookup)), AnswerFrom.RECORDS)
                return@launch
            }
            // 3. Built-in answers: maths, dates, units, greetings, help.
            OfflineAnswers.answer(text, LocalDateTime.now())?.let {
                finish(id, AskAnswer(it), AnswerFrom.OFFLINE)
                return@launch
            }
            // 4. Everything else goes to the AI services.
            val config = graph.aiSettings.load()
            if (!graph.ai.hasAi()) {
                finish(
                    id,
                    AskAnswer("I can't answer that one yet. Ask me about your money, reminders or notes."),
                    AnswerFrom.NOTICE,
                )
                return@launch
            }
            val totals = if (config.shareTotals) AskEngine.monthTotalsLine(records, rules, today, zone) else null
            val brain = if (readsBrain) BrainContext.section(BrainContext.pick(notes, text), zone) else ""
            val earlier = if (readsBrain) chats.all().filter { it.conversation != conversation }.map(::pastTurn) else emptyList()
            val memory = ChatMemory.section(ChatMemory.pick(earlier, text), zone)
            val system = listOfNotNull(SYSTEM_PROMPT, "Today is ${today.dayOfWeek}, $today.", totals).joinToString(" ") + brain + memory
            val messages = buildMessages(text)
            val tooSlow: LlmResult = LlmResult.Failed("That's taking too long. Check your internet and try again.")
            when (val result = withinLimit(tooSlow) { graph.ai.answer(graph.ai.chatModels(), system, messages) }) {
                is LlmResult.Reply -> finish(id, AskAnswer(result.text.replace("**", "")), AnswerFrom.AI)
                is LlmResult.Failed -> {
                    // No AI right now, but the phone can still search the notes.
                    val found = if (readsBrain) BrainContext.matching(notes, text) else emptyList()
                    if (found.isNotEmpty()) {
                        finish(id, AskAnswer("I can't reach the AI right now, but this is in your Second Brain.", notes = found.take(5).map { it.text }), AnswerFrom.RECORDS)
                    } else {
                        finish(id, AskAnswer(result.reason), AnswerFrom.NOTICE)
                    }
                }
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
            val now = LocalDateTime.now()
            val byRule = DeadlineExtractor.extract(null, command.text, now)?.dueAt
            // Phrases like "in 2 hours" or "at 5 pm" have no date the rules can read, so the AI works it out.
            val byAi = if (byRule == null && graph.ai.hasAi()) {
                withinLimit<AiReading?>(null) { SmartReader.reminderTime(graph.ai.readingModels(), command.text, now) }
            } else {
                null
            }
            val due = byRule ?: byAi?.remindAt
            if (due == null) {
                AskAnswer("When should I remind you? Try \"remind me to pay rent tomorrow at 9 am\".")
            } else {
                val title = byAi?.summary ?: ChatCommands.reminderTitle(command.text)
                val dueAt = due.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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

    /** Only what the user typed and earlier AI replies. Notes go separately, chosen by BrainContext. */
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
        val (chat, question) = waiting.remove(id) ?: return
        // Errors aren't worth keeping, and a message with a code or password never goes in the history.
        if (from == AnswerFrom.NOTICE || PrivacyFilter.isSensitive(question)) return
        viewModelScope.launch {
            chats.insert(
                ChatEntry(
                    conversation = chat,
                    question = PrivacyFilter.mask(question),
                    answer = PrivacyFilter.mask(answer.text),
                    source = from.name,
                    notes = answer.notes.joinToString("\n"),
                    at = System.currentTimeMillis(),
                ),
            )
            chats.trim(MAX_SAVED_TURNS)
        }
    }

    private companion object {
        const val HISTORY_TURNS = 4
        const val WAIT_LIMIT_MS = 25_000L
        const val CONTINUE_MS = 6L * 60 * 60 * 1000
        const val MAX_SAVED_TURNS = 500
    }
}

@Composable
fun AskScreen(onOpenAi: () -> Unit, onOpenHistory: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    val vm: AskViewModel = viewModel(factory = viewModelFactory { initializer { AskViewModel(graph) } })
    val turns by vm.turns.collectAsStateWithLifecycle()
    val connected by vm.aiConnected.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    AskContent(turns, connected, vm::ask, onOpenAi, onOpenHistory, vm::newChat)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskContent(
    turns: List<ChatTurn>,
    aiConnected: Boolean,
    onAsk: (String) -> Unit,
    onOpenAi: () -> Unit,
    onHistory: () -> Unit = {},
    onNewChat: () -> Unit = {},
) {
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
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Memora AI", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            if (turns.isNotEmpty()) CircleIconButton(Icons.Rounded.Add, "New chat", onNewChat, size = 48)
            CircleIconButton(Icons.AutoMirrored.Rounded.List, "Chat history", onHistory, size = 48)
        }
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
        "What's in my Second Brain?" to scheme.surface,
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

@Composable
fun ChatHistoryScreen(onBack: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    val vm: AskViewModel = viewModel(factory = viewModelFactory { initializer { AskViewModel(graph) } })
    val history by vm.history.collectAsStateWithLifecycle()
    val undo = rememberUndo()
    ChatHistoryContent(
        history,
        onBack,
        onOpen = {
            vm.open(it)
            onBack()
        },
        onDelete = { id -> vm.delete(id) { rows -> undo("Chat deleted") { vm.restore(rows) } } },
        onClearAll = vm::clearAll,
    )
}

/** Plain data in, callbacks out, so tests can render it directly. */
@Composable
fun ChatHistoryContent(
    chats: List<ChatSummary>,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val shown = chats.filter { query.isBlank() || it.searchable.contains(query.trim(), ignoreCase = true) }
    val byDay = shown.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("Chat history", onBack) }
        if (chats.isNotEmpty()) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search your chats") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        when {
            chats.isEmpty() -> item { NoteCard(null, "No chats yet. Your chats with Memora AI will show here.") }
            shown.isEmpty() -> item { NoteCard(null, "No chats mention \"${query.trim()}\".") }
            else -> byDay.forEach { (day, rows) ->
                item(key = "d-$day") {
                    SectionLabel(
                        when (day) {
                            today -> "Today"
                            today.minusDays(1) -> "Yesterday"
                            else -> day.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH))
                        },
                        Modifier.padding(top = 6.dp),
                    )
                }
                items(rows, key = { it.conversation }) { chat -> ChatHistoryRow(chat, onOpen, onDelete, Modifier.animateItem()) }
            }
        }
        if (chats.isNotEmpty()) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(
                        "Saved on this phone until you delete them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { confirmClear = true }) { Text("Delete all chats") }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            confirmButton = { TextButton(onClick = { onClearAll(); confirmClear = false }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
            title = { Text("Delete all chats?") },
            text = { Text("This removes your whole chat history from this phone. It can't be undone.") },
        )
    }
}

@Composable
private fun ChatHistoryRow(chat: ChatSummary, onOpen: (Long) -> Unit, onDelete: (Long) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surface,
        modifier = modifier.fillMaxWidth().clickable { onOpen(chat.conversation) },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    chat.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    (if (chat.turns == 1) "1 question" else "${chat.turns} questions") + " · " + formatCaptureTime(chat.at),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            CircleIconButton(
                Icons.Rounded.Close,
                "Delete this chat",
                { onDelete(chat.conversation) },
                size = 48,
                container = Color.Transparent,
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}
