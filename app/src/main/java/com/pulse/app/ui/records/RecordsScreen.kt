package com.pulse.app.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.data.MoneyRecord
import com.pulse.app.data.RawEvent
import com.pulse.app.domain.Categoriser
import com.pulse.app.domain.Category
import com.pulse.app.domain.RecordType
import com.pulse.app.ui.components.AppIcon
import com.pulse.app.ui.components.FilterPills
import com.pulse.app.ui.components.NoteCard
import com.pulse.app.ui.components.RecordActionsViewModel
import com.pulse.app.ui.components.RecordDetailSheet
import com.pulse.app.ui.components.RecordRow
import com.pulse.app.ui.components.ScreenHeader
import com.pulse.app.ui.components.formatCaptureTime
import com.pulse.app.ui.components.rememberAppLabel
import com.pulse.app.ui.components.rememberUndo
import com.pulse.app.ui.components.riseIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class RecordFilter { ALL, SPENT, RECEIVED, RAW }

data class RecordsUiState(
    val filter: RecordFilter = RecordFilter.ALL,
    val records: List<MoneyRecord> = emptyList(),
    val raw: List<RawEvent> = emptyList(),
    val rules: Map<String, Category> = emptyMap(),
)

class RecordsViewModel(graph: PulseGraph) : RecordActionsViewModel(graph) {
    private val filter = MutableStateFlow(RecordFilter.ALL)

    val state = combine(
        filter,
        graph.db.moneyRecordDao().observeAll(),
        graph.db.rawEventDao().observeRecent(200),
        graph.db.merchantRuleDao().observeAll(),
    ) { selected, records, raw, rules ->
        RecordsUiState(
            filter = selected,
            records = when (selected) {
                RecordFilter.ALL, RecordFilter.RAW -> records
                RecordFilter.SPENT -> records.filter { it.type == RecordType.EXPENSE }
                RecordFilter.RECEIVED -> records.filter { it.type == RecordType.INCOME }
            },
            raw = raw,
            rules = rules.associate { it.merchant to Category.valueOf(it.category) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordsUiState())

    fun select(value: RecordFilter) {
        filter.value = value
    }
}

@Composable
fun RecordsScreen(onBack: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    val vm: RecordsViewModel = viewModel(factory = viewModelFactory { initializer { RecordsViewModel(graph) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<MoneyRecord?>(null) }
    RecordsContent(state, vm::select, onBack) { open = it }
    val undo = rememberUndo()
    open?.let { record ->
        RecordDetailSheet(record, state.rules, { open = null }, vm::setCategory, vm::rename) {
            vm.delete(it)
            undo("Record deleted") { vm.restore(it) }
        }
    }
}

private fun dayLabel(epochMs: Long): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH))
    }
}

@Composable
fun RecordsContent(
    state: RecordsUiState,
    onFilter: (RecordFilter) -> Unit,
    onBack: () -> Unit,
    onRecord: (MoneyRecord) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val shown = state.records.filter { query.isBlank() || it.merchant.orEmpty().contains(query.trim(), ignoreCase = true) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("Records", onBack) }
        item {
            FilterPills(
                options = listOf(
                    RecordFilter.ALL to "All",
                    RecordFilter.SPENT to "Spent",
                    RecordFilter.RECEIVED to "Received",
                    RecordFilter.RAW to "Raw text",
                ),
                selected = state.filter,
                onSelect = onFilter,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
        }
        if (state.filter == RecordFilter.RAW) {
            item {
                NoteCard(null, "Raw text is deleted after 24 hours. Long reference numbers are masked before saving.")
            }
            items(state.raw, key = { "r-${it.id}" }) { RawRow(it, Modifier.animateItem()) }
        } else if (state.records.isEmpty()) {
            item { NoteCard("Nothing here yet", "Records appear when an allowed app posts a payment, or when you add one on Home.") }
        } else {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search by name") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (shown.isEmpty()) item { NoteCard(null, "No records match \"$query\".") }
            shown.groupBy { dayLabel(it.occurredAt) }.forEach { (day, records) ->
                item(key = "day-$day") {
                    Text(
                        day,
                        Modifier.padding(top = 8.dp, start = 4.dp).animateItem(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(records, key = { it.id }) { record ->
                    val category = if (record.type == RecordType.EXPENSE) Categoriser.of(record.merchant, state.rules).label else null
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().animateItem(),
                    ) {
                        RecordRow(record, category, Modifier.padding(14.dp), onClick = { onRecord(record) })
                    }
                }
            }
        }
    }
}

@Composable
private fun RawRow(event: RawEvent, modifier: Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth().riseIn(),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            AppIcon(event.packageName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(rememberAppLabel(event.packageName), style = MaterialTheme.typography.titleMedium)
                Text(
                    event.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatCaptureTime(event.postedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
