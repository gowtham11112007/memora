package com.pulse.app.ui.calendar

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.data.MoneyRecord
import com.pulse.app.data.Reminder
import com.pulse.app.domain.RecordType
import com.pulse.app.domain.Repeat
import com.pulse.app.domain.TimeRange
import com.pulse.app.domain.formatRupees
import com.pulse.app.domain.startDate
import com.pulse.app.domain.toLocalDate
import com.pulse.app.ui.components.ArrowChip
import com.pulse.app.ui.components.CircleIconButton
import com.pulse.app.ui.components.FilterPills
import com.pulse.app.ui.components.NoteCard
import com.pulse.app.ui.components.SectionLabel
import com.pulse.app.ui.components.bounceClick
import com.pulse.app.ui.components.hatched
import com.pulse.app.ui.components.rememberOriginLine
import com.pulse.app.ui.components.rememberUndo
import com.pulse.app.ui.components.riseIn
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val selected: LocalDate = LocalDate.now(),
    val today: LocalDate = LocalDate.now(),
    val reminders: List<Reminder> = emptyList(),
    val spentByDay: Map<LocalDate, Long> = emptyMap(),
    val openTodos: Int = 0,
    val suggestedTodos: Int = 0,
)

class CalendarViewModel(private val graph: PulseGraph) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val month = MutableStateFlow(YearMonth.now())
    private val selected = MutableStateFlow(LocalDate.now())
    private val dao = graph.db.reminderDao()

    val state = combine(
        month,
        selected,
        dao.observeAll(),
        graph.db.moneyRecordDao().observeAll(),
        graph.db.todoDao().observeAll(),
    ) { m, day, reminders, records, todos ->
        CalendarUiState(m, day, LocalDate.now(), reminders, spentByDay(records), todos.count { it.confirmed && !it.done }, todos.count { !it.confirmed })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarUiState())

    private fun spentByDay(records: List<MoneyRecord>): Map<LocalDate, Long> =
        records.filter { it.type == RecordType.EXPENSE }
            .groupBy { it.occurredAt.toLocalDate(zone) }
            .mapValues { (_, list) -> list.sumOf { it.amountPaise } }

    fun changeMonth(by: Long) {
        month.value = month.value.plusMonths(by)
    }

    fun select(day: LocalDate) {
        selected.value = day
    }

    fun add(title: String, day: LocalDate, time: LocalTime, repeat: Repeat) {
        viewModelScope.launch {
            val reminder = Reminder(
                title = title.trim(),
                dueAt = day.atTime(time).atZone(zone).toInstant().toEpochMilli(),
                confidence = 1.0,
                confirmed = true,
                source = MoneyRecord.MANUAL,
                repeat = repeat.name,
            )
            graph.reminders.schedule(reminder.copy(id = dao.insert(reminder)))
        }
    }

    fun confirm(reminder: Reminder) {
        viewModelScope.launch {
            val updated = reminder.copy(confirmed = true)
            dao.update(updated)
            graph.reminders.schedule(updated)
            com.pulse.app.service.ReminderPrompt.dismiss(graph.appContext, reminder.id)
        }
    }

    /** Choosing a time is also a yes, so it's confirmed and scheduled. */
    fun changeTime(reminder: Reminder, at: LocalDateTime) {
        viewModelScope.launch {
            val updated = reminder.copy(dueAt = at.atZone(zone).toInstant().toEpochMilli(), confirmed = true, done = false)
            dao.update(updated)
            graph.reminders.schedule(updated)
            com.pulse.app.service.ReminderPrompt.dismiss(graph.appContext, reminder.id)
        }
    }

    fun toTodo(reminder: Reminder) {
        viewModelScope.launch {
            dao.delete(reminder)
            graph.reminders.cancel(reminder.id)
            graph.db.todoDao().insert(
                com.pulse.app.data.Todo(text = reminder.title, createdAt = System.currentTimeMillis(), source = reminder.source, origin = reminder.origin),
            )
            com.pulse.app.service.ReminderPrompt.dismiss(graph.appContext, reminder.id)
        }
    }

    fun toggleDone(reminder: Reminder) {
        viewModelScope.launch {
            // Ticking a repeating reminder finishes this one and moves it to its next date.
            val next = if (reminder.done) null else Repeat.of(reminder.repeat).next(reminder.dueAt, System.currentTimeMillis(), zone)
            val updated = if (next != null) reminder.copy(dueAt = next) else reminder.copy(done = !reminder.done)
            dao.update(updated)
            if (updated.done) graph.reminders.cancel(updated.id) else graph.reminders.schedule(updated)
        }
    }

    fun restore(reminder: Reminder) {
        viewModelScope.launch {
            dao.insert(reminder)
            graph.reminders.schedule(reminder)
        }
    }

    fun delete(reminder: Reminder) {
        viewModelScope.launch {
            dao.delete(reminder)
            graph.reminders.cancel(reminder.id)
        }
    }
}

@Composable
fun CalendarScreen(onOpenTodo: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as PulseApp).graph
    val vm: CalendarViewModel = viewModel(factory = viewModelFactory { initializer { CalendarViewModel(graph) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val undo = rememberUndo()

    // Android 13+ asks before an app can show notifications, and reminders need that.
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    CalendarContent(
        state = state,
        onMonth = vm::changeMonth,
        onSelect = vm::select,
        onAdd = { adding = true },
        onToggle = vm::toggleDone,
        onConfirm = vm::confirm,
        onDelete = {
            vm.delete(it)
            undo("Reminder deleted") { vm.restore(it) }
        },
        onOpenTodo = onOpenTodo,
        onChangeTime = vm::changeTime,
        onToTodo = vm::toTodo,
    )
    if (adding) AddReminderSheet(state.selected, state.today, { adding = false }, vm::add)
}

/** Plain data in, callbacks out. No database, so tests can render it directly. */
@Composable
fun CalendarContent(
    state: CalendarUiState,
    onMonth: (Long) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onAdd: () -> Unit,
    onToggle: (Reminder) -> Unit,
    onConfirm: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
    onOpenTodo: () -> Unit = {},
    onChangeTime: (Reminder, LocalDateTime) -> Unit = { _, _ -> },
    onToTodo: (Reminder) -> Unit = {},
) {
    val zone = ZoneId.systemDefault()
    val byDay = state.reminders.groupBy { it.dueAt.toLocalDate(zone) }
    val now = System.currentTimeMillis()
    // Suggestions are listed together whatever their date, so one on a far-off day still gets seen.
    val waiting = state.reminders.filter { !it.confirmed && !it.done && it.dueAt >= now }
    val dayReminders = byDay[state.selected].orEmpty() - waiting.toSet()
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().riseIn(0), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Calendar",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                CircleIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous month", { onMonth(-1) })
                Spacer(Modifier.size(8.dp))
                CircleIconButton(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next month", { onMonth(1) })
            }
        }
        if (waiting.isNotEmpty()) {
            item { SectionLabel("Needs your answer", Modifier.riseIn(1)) }
            items(waiting, key = { "w-${it.id}" }) { reminder ->
                ReminderRow(reminder, onToggle, onConfirm, onDelete, Modifier.animateItem(), showDate = true, onChangeTime = onChangeTime, onToTodo = onToTodo)
            }
        }
        item { MonthCard(state, byDay.keys, onSelect, Modifier.riseIn(1)) }
        item { ProgressCard(state, Modifier.riseIn(2)) }
        item { TodoCard(state.openTodos, state.suggestedTodos, onOpenTodo, Modifier.riseIn(2)) }
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp).riseIn(2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(state.selected.format(DateTimeFormatter.ofPattern("EEE, d MMMM", Locale.ENGLISH)))
                    val spent = state.spentByDay[state.selected] ?: 0L
                    if (spent > 0) {
                        Text(
                            "${formatRupees(spent)} spent",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                CircleIconButton(
                    Icons.Rounded.Add,
                    "Add reminder",
                    onAdd,
                    container = MaterialTheme.colorScheme.primary,
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        if (dayReminders.isEmpty()) {
            item { NoteCard(null, "No reminders on this day. Tap + to add one.") }
        } else {
            items(dayReminders, key = { it.id }) { reminder ->
                ReminderRow(reminder, onToggle, onConfirm, onDelete, Modifier.animateItem(), onChangeTime = onChangeTime, onToTodo = onToTodo)
            }
        }
    }
}

@Composable
private fun MonthCard(state: CalendarUiState, reminderDays: Set<LocalDate>, onSelect: (LocalDate) -> Unit, modifier: Modifier) {
    // An ink card, so the lime selected day stands out like the dark panels in the references.
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.inverseSurface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AnimatedContent(
                targetState = state.month,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(tween(280)) { it / 3 * direction } + fadeIn(tween(220))) togetherWith
                        (slideOutHorizontally(tween(220)) { -it / 3 * direction } + fadeOut(tween(160)))
                },
                label = "month",
            ) { month ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    WeekdayLabels()
                    MonthGrid(month, state, reminderDays, onSelect)
                }
            }
        }
    }
}

@Composable
private fun WeekdayLabels() {
    Row(Modifier.fillMaxWidth()) {
        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach {
            Text(
                it,
                Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun MonthGrid(month: YearMonth, state: CalendarUiState, reminderDays: Set<LocalDate>, onSelect: (LocalDate) -> Unit) {
    val first = month.atDay(1)
    val leadingBlanks = first.dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells: List<LocalDate?> = List(leadingBlanks) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                (week + List(7 - week.size) { null }).forEach { day ->
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (day != null) DayCell(day, state, day in reminderDays, onSelect)
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: LocalDate, state: CalendarUiState, hasReminder: Boolean, onSelect: (LocalDate) -> Unit) {
    val isSelected = day == state.selected
    val spent = (state.spentByDay[day] ?: 0L) > 0
    val scheme = MaterialTheme.colorScheme
    val onInk = scheme.inverseOnSurface
    val fill by animateColorAsState(if (isSelected) Lime else onInk.copy(alpha = 0.07f), label = "dayFill")
    val ink by animateColorAsState(if (isSelected) Ink else onInk, label = "dayInk")
    Box(
        modifier = Modifier
            .bounceClick { onSelect(day) }
            .fillMaxSize()
            .clip(CircleShape)
            .then(if (spent && !isSelected) Modifier.hatched(onInk.copy(alpha = 0.22f), onInk.copy(alpha = 0.05f)) else Modifier.background(fill))
            .then(if (hasReminder && !isSelected) Modifier.border(1.5.dp, Lime, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelLarge, color = ink)
        if (day == state.today) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(ink),
            )
        }
    }
}

/** The share of this week's reminders that are done. */
@Composable
private fun ProgressCard(state: CalendarUiState, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    val monday = TimeRange.WEEK.startDate(state.today)
    val week = state.reminders.filter {
        val day = it.dueAt.toLocalDate(zone)
        day >= monday && day < monday.plusDays(7)
    }
    val done = week.count { it.done }
    val left = week.size - done
    val target = if (week.isEmpty()) 0f else done.toFloat() / week.size
    val progress by animateFloatAsState(target, tween(800, easing = FastOutSlowInEasing), label = "ring")
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reminders this week", style = MaterialTheme.typography.titleMedium, color = ink)
            Text(
                when {
                    week.isEmpty() -> "None this week"
                    left == 0 -> "All done"
                    else -> "$left left"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.72f),
            )
        }
        Box(Modifier.size(66.dp), contentAlignment = Alignment.Center) {
            val track = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            val arc = MaterialTheme.colorScheme.surface
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 6.dp.toPx()
                val box = Size(size.width - stroke, size.height - stroke)
                val corner = Offset(stroke / 2, stroke / 2)
                drawArc(track, 0f, 360f, false, corner, box, style = Stroke(stroke))
                drawArc(arc, -90f, 360f * progress, false, corner, box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text("${(target * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = ink)
        }
    }
}

@Composable
private fun TodoCard(open: Int, suggested: Int, onOpen: () -> Unit, modifier: Modifier) {
    val fill = MaterialTheme.colorScheme.primaryContainer
    val ink = MaterialTheme.colorScheme.onPrimaryContainer
    Row(
        modifier
            .bounceClick(onClick = onOpen)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(fill)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("To-do list", style = MaterialTheme.typography.titleMedium, color = ink)
            Text(
                when (open) {
                    0 -> "Nothing to do"
                    1 -> "1 thing to do"
                    else -> "$open things to do"
                } + when (suggested) {
                    0 -> ""
                    1 -> " · 1 suggestion"
                    else -> " · $suggested suggestions"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.72f),
            )
        }
        ArrowChip(ink, fill, size = 38.dp)
    }
}

@Composable
private fun ReminderRow(
    reminder: Reminder,
    onToggle: (Reminder) -> Unit,
    onConfirm: (Reminder) -> Unit,
    onDelete: (Reminder) -> Unit,
    modifier: Modifier,
    showDate: Boolean = false,
    onChangeTime: (Reminder, LocalDateTime) -> Unit = { _, _ -> },
    onToTodo: (Reminder) -> Unit = {},
) {
    val context = LocalContext.current
    val from = rememberOriginLine(reminder.source, reminder.origin)
    val scheme = MaterialTheme.colorScheme
    val unconfirmed = !reminder.confirmed
    val fill = when {
        unconfirmed -> scheme.errorContainer
        reminder.done -> scheme.tertiaryContainer
        else -> scheme.primaryContainer
    }
    val ink = when {
        unconfirmed -> scheme.onErrorContainer
        reminder.done -> scheme.onTertiaryContainer
        else -> scheme.onPrimaryContainer
    }
    val time = Instant.ofEpochMilli(reminder.dueAt).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (showDate) "EEE d MMM, h:mm a" else "h:mm a", Locale.ENGLISH))
    Surface(shape = MaterialTheme.shapes.medium, color = fill, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // An empty circle that fills with a tick when done, like a checkbox.
                CircleIconButton(
                    Icons.Rounded.Check,
                    if (reminder.done) "Mark as not done" else "Mark as done",
                    { onToggle(reminder) },
                    size = 48,
                    container = scheme.surface.copy(alpha = 0.65f),
                    tint = if (reminder.done) scheme.onSurface else Color.Transparent,
                )
                Column(Modifier.weight(1f)) {
                    Text(reminder.title, style = MaterialTheme.typography.titleMedium, color = ink)
                    val repeat = Repeat.of(reminder.repeat)
                    Text(
                        if (repeat == Repeat.NONE) time else "$time · Repeats ${repeat.label.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = 0.72f),
                    )
                }
                CircleIconButton(
                    Icons.Rounded.Close,
                    "Delete reminder",
                    { onDelete(reminder) },
                    size = 48,
                    container = Color.Transparent,
                    tint = ink.copy(alpha = 0.7f),
                )
            }
            if (from != null) {
                Text(from, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = ink)
            }
            if (unconfirmed) {
                Text(
                    "Is this right? Memora won't remind you until you say yes.",
                    Modifier.padding(start = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.8f),
                )
            }
            if (!reminder.done) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (unconfirmed) TextButton(onClick = { onConfirm(reminder) }) { Text("Yes, remind me", color = ink) }
                    TextButton(
                        onClick = {
                            val at = Instant.ofEpochMilli(reminder.dueAt).atZone(ZoneId.systemDefault()).toLocalDateTime()
                            DatePickerDialog(
                                context,
                                { _, year, month, day ->
                                    TimePickerDialog(
                                        context,
                                        { _, hour, minute -> onChangeTime(reminder, LocalDateTime.of(year, month + 1, day, hour, minute)) },
                                        at.hour,
                                        at.minute,
                                        false,
                                    ).show()
                                },
                                at.year,
                                at.monthValue - 1,
                                at.dayOfMonth,
                            ).show()
                        },
                    ) { Text("Change time", color = ink) }
                    TextButton(onClick = { onToTodo(reminder) }) { Text("To-do", color = ink) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddReminderSheet(
    day: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, LocalDate, LocalTime, Repeat) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var repeat by remember { mutableStateOf(Repeat.NONE) }
    val inPast = day.atTime(time) <= LocalDateTime.now()
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Reminder for " + day.format(DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)),
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("What should I remind you about?") },
                modifier = Modifier.fillMaxWidth(),
            )
            FilterPills(
                options = listOf(
                    LocalTime.of(9, 0) to "9 AM",
                    LocalTime.of(12, 0) to "12 PM",
                    LocalTime.of(18, 0) to "6 PM",
                    LocalTime.of(21, 0) to "9 PM",
                ),
                selected = time,
                onSelect = { time = it },
            )
            TextButton(
                onClick = {
                    TimePickerDialog(context, { _, hour, minute -> time = LocalTime.of(hour, minute) }, time.hour, time.minute, false).show()
                },
            ) {
                Text("Other time · " + time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)))
            }
            FilterPills(
                options = Repeat.entries.map { it to it.label },
                selected = repeat,
                onSelect = { repeat = it },
            )
            if (inPast) {
                Text(
                    if (day < today) "This day has passed. Pick a later day." else "This time has passed. Pick a later time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { onSave(title, day, time, repeat); onDismiss() },
                enabled = title.isNotBlank() && !inPast,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save reminder")
            }
        }
    }
}
