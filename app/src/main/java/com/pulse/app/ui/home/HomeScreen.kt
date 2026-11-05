package com.pulse.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pulse.app.PulseApp
import com.pulse.app.data.MoneyRecord
import com.pulse.app.domain.Categoriser
import com.pulse.app.domain.Category
import com.pulse.app.domain.DayTotal
import com.pulse.app.domain.RecordType
import com.pulse.app.domain.TimeRange
import com.pulse.app.domain.formatRupees
import com.pulse.app.ui.components.AddRecordSheet
import com.pulse.app.ui.components.AnimatedRupees
import com.pulse.app.ui.components.ArrowChip
import com.pulse.app.ui.components.CircleIconButton
import com.pulse.app.ui.components.FolderTabShape
import com.pulse.app.ui.components.RecordDetailSheet
import com.pulse.app.ui.components.RecordRow
import com.pulse.app.ui.components.SparkIcon
import com.pulse.app.ui.components.bounceClick
import com.pulse.app.ui.components.formatCaptureTime
import com.pulse.app.ui.components.hatched
import com.pulse.app.ui.components.labelled
import com.pulse.app.ui.components.parseRupees
import com.pulse.app.ui.components.rememberUndo
import com.pulse.app.ui.components.withRupee
import kotlinx.coroutines.delay
import com.pulse.app.ui.components.riseIn
import com.pulse.app.ui.settings.rememberNotificationAccess
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import java.time.format.TextStyle
import java.util.Locale

private val SIDE = 20.dp

@Composable
fun HomeScreen(
    onViewRecords: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenCalendar: () -> Unit,
    onAsk: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(graph) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val accessOn by rememberNotificationAccess()
    var adding by remember { mutableStateOf(false) }
    var openRecord by remember { mutableStateOf<MoneyRecord?>(null) }
    var editingMoney by remember { mutableStateOf(false) }
    val undo = rememberUndo()
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshClock()
            delay(60_000)
        }
    }

    HomeContent(
        onEditMoney = { editingMoney = true },
        state = state,
        accessOn = accessOn,
        onRange = vm::selectRange,
        onAdd = { adding = true },
        onAsk = onAsk,
        onOpenReading = onOpenReading,
        onOpenCalendar = onOpenCalendar,
        onViewRecords = onViewRecords,
        onRecord = { openRecord = it },
        onOpenActivity = {
            vm.markBellSeen()
            onOpenActivity()
        },
    )
    if (adding) AddRecordSheet(onDismiss = { adding = false }, onSave = vm::addManual)
    if (editingMoney) EditMoneySheet(state, { editingMoney = false }, vm::saveMoney)
    openRecord?.let { record ->
        RecordDetailSheet(record, state.rules, { openRecord = null }, vm::setCategory) {
            vm.delete(it)
            undo("Record deleted") { vm.restore(it) }
        }
    }
}

/** Plain data in, callbacks out. No database, so tests can render it directly. */
@Composable
fun HomeContent(
    state: HomeUiState,
    accessOn: Boolean,
    onRange: (TimeRange) -> Unit,
    onAdd: () -> Unit,
    onAsk: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenCalendar: () -> Unit,
    onViewRecords: () -> Unit,
    onRecord: (MoneyRecord) -> Unit,
    onEditMoney: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
) {
    val side = Modifier.padding(horizontal = SIDE)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { TopSheet(state, onRange, onOpenActivity, onAdd, onAsk, onEditMoney) }
        if (!accessOn) item { ReadingOffBanner(onOpenReading, side) }
        if (state.needsAnswer > 0) item { NeedsAnswerCard(state.needsAnswer, onOpenCalendar, side.riseIn(2)) }
        if (state.nextReminder != null || state.insights.isNotEmpty()) {
            item { TodayBrief(state, onOpenCalendar, side.riseIn(2)) }
        }
        item { BudgetCard(state, onEditMoney, side.riseIn(2)) }
        item { CategoryGrid(state.byCategory, onViewRecords, side.riseIn(2)) }
        item { WeekPanel(state, side.riseIn(3)) }
        item { RecentPanel(state, onViewRecords, onRecord, side.riseIn(4)) }
    }
}

/** A coloured sheet behind the header. The money card overlaps its lower edge. */
@Composable
private fun TopSheet(
    state: HomeUiState,
    onRange: (TimeRange) -> Unit,
    onOpenActivity: () -> Unit,
    onAdd: () -> Unit,
    onAsk: () -> Unit,
    onEditMoney: () -> Unit,
) {
    val sheet = MaterialTheme.colorScheme.secondaryContainer
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val keep = size.height - 118.dp.toPx()
                val r = 40.dp.toPx()
                drawRoundRect(sheet, size = Size(size.width, keep), cornerRadius = CornerRadius(r, r))
                drawRect(sheet, size = Size(size.width, r))
            }
            .statusBarsPadding()
            .padding(start = SIDE, end = SIDE, top = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().riseIn(0), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.greeting, style = MaterialTheme.typography.titleMedium, color = ink.copy(alpha = 0.75f))
                    if (state.isDemo) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Lime)
                                .padding(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            Text("Sample data", style = MaterialTheme.typography.labelSmall, color = Ink)
                        }
                    }
                }
                Text("Track Your\nMoney", style = MaterialTheme.typography.headlineLarge, color = ink)
            }
            Box {
                val fresh = state.newReads
                CircleIconButton(
                    Icons.Rounded.Notifications,
                    if (fresh > 0) "See notifications read, $fresh new" else "See notifications read",
                    onOpenActivity,
                    size = 56,
                )
                if (fresh > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (fresh > 9) "9+" else "$fresh",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        BalanceCard(state, onEditMoney, Modifier.riseIn(1))
        Spacer(Modifier.height(14.dp))
        PeriodSwitch(state.range, onRange, Modifier.riseIn(1))
        Spacer(Modifier.height(18.dp))
        MoneyCard(state, onAdd, onAsk, Modifier.riseIn(1))
    }
}

/** Total balance, with what went out and came in today. */
@Composable
private fun BalanceCard(state: HomeUiState, onEdit: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.inverseOnSurface
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.inverseSurface,
        modifier = modifier.fillMaxWidth().bounceClick(onClick = onEdit),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Total balance", style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.7f))
                    val balance = state.balance
                    if (balance != null) {
                        AnimatedRupees(balance.paise, MaterialTheme.typography.displaySmall, Lime)
                        Text(balance.source, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.6f))
                    } else {
                        Text("Tap to set", style = MaterialTheme.typography.headlineMedium, color = Lime)
                    }
                }
                ArrowChip(Lime, Ink)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowChip("Deducted today", "−" + formatRupees(state.deductedTodayPaise), Modifier.weight(1f))
                FlowChip("Received today", "+" + formatRupees(state.receivedTodayPaise), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FlowChip(label: String, value: String, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.inverseOnSurface
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ink.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.7f), maxLines = 1)
        Text(withRupee(value), style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1)
    }
}

/** Keeps a missed popup from being lost. Its answers wait on the Calendar page. */
@Composable
private fun NeedsAnswerCard(count: Int, onOpen: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .bounceClick(onClick = onOpen)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Lime)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (count == 1) "1 thing needs your answer" else "$count things need your answer",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
            )
            Text(
                "Reminders and to-dos Memora found in your messages",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.copy(alpha = 0.72f),
            )
        }
        ArrowChip(Ink, Lime, size = 38.dp)
    }
}

/** What's coming up, and what your records show. Each line is calculated, never invented. */
@Composable
private fun TodayBrief(state: HomeUiState, onOpenCalendar: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onPrimaryContainer
    Surface(shape = MaterialTheme.shapes.large, color = scheme.primaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                    Icon(SparkIcon, null, Modifier.size(18.dp), tint = scheme.onPrimary)
                }
                Text("Today's brief", style = MaterialTheme.typography.titleLarge, color = ink)
            }
            state.nextReminder?.let { reminder ->
                Row(
                    Modifier
                        .bounceClick(onClick = onOpenCalendar)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(scheme.surface)
                        .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Up next", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                        Text(reminder.title, style = MaterialTheme.typography.titleMedium, color = scheme.onSurface, maxLines = 1)
                        Text(formatCaptureTime(reminder.dueAt), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    ArrowChip(scheme.inverseSurface, scheme.inverseOnSurface, size = 34.dp)
                }
            }
            state.insights.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.85f)) }
        }
    }
}

/** How much of the monthly budget is left. */
@Composable
private fun BudgetCard(state: HomeUiState, onEdit: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val budget = state.budgetPaise
    val used = if (budget > 0) (state.spentMonthPaise.toFloat() / budget).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(used, tween(800, easing = FastOutSlowInEasing), label = "budget")
    val left = budget - state.spentMonthPaise
    Surface(
        shape = MaterialTheme.shapes.large,
        color = scheme.surface,
        modifier = modifier.fillMaxWidth().bounceClick(onClick = onEdit),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Monthly budget", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        budget <= 0 -> "Tap to set"
                        left >= 0 -> "${formatRupees(left)} left"
                        else -> "${formatRupees(-left)} over"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (budget > 0 && left < 0) scheme.error else scheme.primary,
                )
            }
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(scheme.surfaceVariant)) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(if (budget > 0 && left < 0) scheme.error else scheme.primary),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditMoneySheet(state: HomeUiState, onDismiss: () -> Unit, onSave: (Long?, Long?) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        EditMoneyForm(state.balance?.paise, state.budgetPaise, state.balance?.source, onDismiss) { balance, budget ->
            onSave(balance, budget)
            onDismiss()
        }
    }
}

/** What the form would change. Null values are left alone, and a budget of 0 means no budget. */
data class MoneyChange(val valid: Boolean, val balancePaise: Long? = null, val budgetPaise: Long? = null) {
    val hasChanges get() = valid && (balancePaise != null || budgetPaise != null)
}

private fun amountFrom(text: String): Long? =
    if (Regex("""0+(\.0+)?""").matches(text.replace(",", "").trim())) 0L else parseRupees(text)

/** Compares the boxes with the saved values, so saving the budget never overwrites a balance from the bank. */
fun moneyChange(currentBalance: Long?, currentBudget: Long, balanceText: String, budgetText: String): MoneyChange {
    val balance = if (balanceText.isBlank()) null else amountFrom(balanceText)
    val budget = if (budgetText.isBlank()) 0L else amountFrom(budgetText)
    val balanceOk = balance != null || (balanceText.isBlank() && currentBalance == null)
    if (!balanceOk || budget == null) return MoneyChange(valid = false)
    return MoneyChange(
        valid = true,
        balancePaise = balance?.takeIf { it != currentBalance },
        budgetPaise = budget.takeIf { it != currentBudget },
    )
}

private fun plainAmount(paise: Long) = formatRupees(paise).removePrefix("₹")

@Composable
private fun MoneyField(
    label: String,
    value: TextFieldValue,
    placeholder: String,
    help: String,
    error: String?,
    onChange: (TextFieldValue) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> if (next.text.all { it.isDigit() || it == ',' || it == '.' }) onChange(next) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        prefix = { Text("₹") },
        trailingIcon = {
            if (value.text.isNotEmpty()) {
                IconButton(onClick = { onChange(TextFieldValue("")) }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Clear $label")
                }
            }
        },
        supportingText = { Text(error ?: help) },
        isError = error != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Opens with your saved values, and Save stays off until something actually changes. */
@Composable
fun EditMoneyForm(
    balancePaise: Long?,
    budgetPaise: Long,
    balanceSource: String?,
    onCancel: () -> Unit,
    onSave: (Long?, Long?) -> Unit,
) {
    // A tracked balance can dip below zero, and the box can't show that, so it starts empty instead.
    val startBalance = balancePaise?.takeIf { it >= 0 }
    val filled = { text: String -> TextFieldValue(text, TextRange(text.length)) }
    var balance by remember { mutableStateOf(filled(startBalance?.let(::plainAmount).orEmpty())) }
    var budget by remember { mutableStateOf(filled(budgetPaise.takeIf { it > 0 }?.let(::plainAmount).orEmpty())) }
    val change = moneyChange(startBalance, budgetPaise, balance.text, budget.text)
    val balanceBad = balance.text.isBlank() && startBalance != null ||
        balance.text.isNotBlank() && amountFrom(balance.text) == null
    val budgetBad = budget.text.isNotBlank() && amountFrom(budget.text) == null
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Your money", style = MaterialTheme.typography.titleLarge)
        Text(
            "Change either amount, or leave them as they are.",
            Modifier.padding(bottom = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MoneyField(
            "Bank balance",
            balance,
            "Enter your balance",
            (balanceSource?.let { "$it. " } ?: "") + "A new bank SMS updates this automatically.",
            when {
                !balanceBad -> null
                balance.text.isBlank() -> "Enter your balance. Use 0 if it's empty."
                else -> "Enter an amount, like 12,500"
            },
        ) { balance = it }
        MoneyField(
            "Monthly budget",
            budget,
            "No budget set",
            "Clear this if you don't want a budget.",
            if (budgetBad) "Enter an amount, like 15,000" else null,
        ) { budget = it }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = { onSave(change.balancePaise, change.budgetPaise) },
                enabled = change.hasChanges,
                modifier = Modifier.weight(1f),
            ) { Text("Save changes") }
        }
    }
}

/** A segmented control whose ink thumb slides between the periods. */
@Composable
private fun PeriodSwitch(selected: TimeRange, onSelect: (TimeRange) -> Unit, modifier: Modifier) {
    val options = listOf(TimeRange.TODAY to "Today", TimeRange.WEEK to "This week", TimeRange.MONTH to "This month")
    val index = options.indexOfFirst { it.first == selected }
    val thumb by animateFloatAsState(index.toFloat(), spring(dampingRatio = 0.75f), label = "thumb")
    val thumbFill = MaterialTheme.colorScheme.inverseSurface
    Box(
        modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(4.dp)
            .drawBehind {
                val w = size.width / options.size
                drawRoundRect(
                    thumbFill,
                    topLeft = androidx.compose.ui.geometry.Offset(w * thumb, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                )
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, (value, label) ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (i == index) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/** A folder-tab card: choosing Spent or Received slides the raised tab across. */
@Composable
private fun MoneyCard(state: HomeUiState, onAdd: () -> Unit, onAsk: () -> Unit, modifier: Modifier) {
    var received by remember { mutableStateOf(false) }
    val from by animateFloatAsState(if (received) 0.5f else 0f, tween(320, easing = FastOutSlowInEasing), label = "tabFrom")
    val to by animateFloatAsState(if (received) 1f else 0.5f, tween(320, easing = FastOutSlowInEasing), label = "tabTo")
    val scheme = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()) {
        Surface(
            shape = FolderTabShape(from, to),
            color = scheme.surface,
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 66.dp, bottom = 18.dp)) {
                Text(
                    if (received) "Money received" else "Money spent",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                AnimatedRupees(
                    if (received) state.receivedPaise else state.spentPaise,
                    MaterialTheme.typography.displayMedium,
                    scheme.onSurface,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionPill("Add", false, onAdd, Modifier.weight(1f)) { ink ->
                        Icon(Icons.Rounded.Add, null, tint = ink)
                    }
                    ActionPill("Ask AI", true, onAsk, Modifier.weight(1f)) { ink ->
                        Icon(SparkIcon, null, Modifier.size(20.dp), tint = ink)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(54.dp)) {
            TabLabel("Spent", !received, Modifier.weight(1f)) { received = false }
            TabLabel("Received", received, Modifier.weight(1f)) { received = true }
        }
    }
}

@Composable
private fun TabLabel(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleLarge,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun ActionPill(
    text: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .bounceClick(onClick = onClick)
            .height(56.dp)
            .clip(shape)
            .background(if (filled) scheme.inverseSurface else scheme.surface)
            .then(if (filled) Modifier else Modifier.border(1.5.dp, scheme.outline, shape))
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(if (filled) Lime else scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (filled) Ink else scheme.primary)
        }
        Text(
            text,
            Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = if (filled) scheme.inverseOnSurface else scheme.onSurface,
        )
    }
}

@Composable
private fun ReadingOffBanner(onOpen: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .bounceClick(onClick = onOpen)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Lime)
            .padding(start = 18.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reading is off", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text("Turn it on to record payments for you", style = MaterialTheme.typography.bodySmall, color = Ink.copy(alpha = 0.75f))
        }
        ArrowChip(Ink, Lime)
    }
}

@Composable
private fun CategoryGrid(byCategory: Map<Category, Long>, onOpen: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val tiles = listOf(
        Triple(Category.FOOD, scheme.primaryContainer, scheme.onPrimaryContainer),
        Triple(Category.TRANSPORT, scheme.errorContainer, scheme.onErrorContainer),
        Triple(Category.SHOPPING, scheme.tertiaryContainer, scheme.onTertiaryContainer),
        Triple(Category.BILLS, scheme.surface, scheme.onSurface),
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (category, fill, ink) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .bounceClick(onClick = onOpen)
                            .height(132.dp)
                            .clip(MaterialTheme.shapes.large)
                            .background(fill)
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(category.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, color = ink)
                            ArrowChip(scheme.inverseSurface, scheme.inverseOnSurface, size = 34.dp)
                        }
                        Spacer(Modifier.weight(1f))
                        AnimatedRupees(byCategory[category] ?: 0L, MaterialTheme.typography.headlineMedium, ink)
                    }
                }
            }
        }
        val other = byCategory[Category.OTHER] ?: 0L
        if (other > 0) {
            Row(
                Modifier
                    .bounceClick(onClick = onOpen)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(scheme.surface)
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text("Other", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                AnimatedRupees(other, MaterialTheme.typography.titleMedium, scheme.onSurface)
            }
        }
    }
}

/** This week in solid ink, last week as a hatched ghost behind each bar. */
@Composable
private fun WeekPanel(state: HomeUiState, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onTertiaryContainer
    val highest = (state.week + state.lastWeek).maxOfOrNull { it.spentPaise }?.coerceAtLeast(1L) ?: 1L
    Surface(shape = MaterialTheme.shapes.large, color = scheme.tertiaryContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("This week", style = MaterialTheme.typography.titleLarge, color = ink)
                    Text(
                        formatRupees(state.week.sumOf { it.spentPaise }) + " spent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink.copy(alpha = 0.7f),
                    )
                }
                Text("Ghost: last week", style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.65f))
            }
            Row(
                Modifier.fillMaxWidth().height(168.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                state.week.forEachIndexed { i, day ->
                    DayBar(day, state.lastWeek.getOrNull(i)?.spentPaise ?: 0L, highest, day.date == state.today, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DayBar(day: DayTotal, ghostPaise: Long, highest: Long, isToday: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val maxBar = 122.dp
    val height by animateDpAsState(
        targetValue = 14.dp + maxBar * (day.spentPaise.toFloat() / highest),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "bar",
    )
    val ghost by animateDpAsState(
        targetValue = 14.dp + maxBar * (ghostPaise.toFloat() / highest),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "ghost",
    )
    val shape = RoundedCornerShape(50)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(maxBar + 14.dp), contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(ghost)
                    .clip(shape)
                    .hatched(scheme.onTertiaryContainer.copy(alpha = 0.18f), scheme.surface.copy(alpha = 0.55f)),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(shape)
                    .background(if (isToday) scheme.primary else scheme.inverseSurface),
            )
        }
        Text(
            day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onTertiaryContainer.copy(alpha = if (isToday) 1f else 0.7f),
            maxLines = 1,
        )
    }
}

/** Ink panel. The newest record is the lime highlight, the rest sit in outlined rows. */
@Composable
private fun RecentPanel(
    state: HomeUiState,
    onViewRecords: () -> Unit,
    onRecord: (MoneyRecord) -> Unit,
    modifier: Modifier,
) {
    val ink = MaterialTheme.colorScheme.inverseOnSurface
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.inverseSurface,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Recent", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = ink)
                Box(Modifier.bounceClick(onClick = onViewRecords).labelled("View all records")) { ArrowChip(Lime, Ink, size = 48.dp) }
            }
            if (state.recent.isEmpty()) {
                Text(
                    "Nothing yet. Tap Add, or turn on your payment apps in Settings.",
                    Modifier.padding(6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink.copy(alpha = 0.72f),
                )
            }
            state.recent.forEachIndexed { i, record ->
                val category = if (record.type == RecordType.EXPENSE) Categoriser.of(record.merchant, state.rules).label else null
                val highlight = i == 0
                val shape = RoundedCornerShape(26.dp)
                Box(
                    Modifier
                        .clip(shape)
                        .then(if (highlight) Modifier.background(Lime) else Modifier.border(1.dp, ink.copy(alpha = 0.18f), shape))
                        .padding(10.dp),
                ) {
                    RecordRow(
                        record,
                        category,
                        onDark = !highlight,
                        onLime = highlight,
                        onClick = { onRecord(record) },
                    )
                }
            }
        }
    }
}
