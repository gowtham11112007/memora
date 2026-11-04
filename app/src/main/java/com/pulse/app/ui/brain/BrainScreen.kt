package com.pulse.app.ui.brain

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.data.MoneyRecord
import com.pulse.app.data.Note
import com.pulse.app.domain.NoteKind
import com.pulse.app.domain.PrivacyFilter
import com.pulse.app.ui.components.CircleIconButton
import com.pulse.app.ui.components.FilterPills
import com.pulse.app.ui.components.NoteCard
import com.pulse.app.ui.components.formatCaptureTime
import com.pulse.app.ui.components.riseIn
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BrainUiState(
    val kind: NoteKind? = null,
    val search: String = "",
    val notes: List<Note> = emptyList(),
)

class BrainViewModel(private val graph: PulseGraph) : ViewModel() {
    private val kind = MutableStateFlow<NoteKind?>(null)
    private val search = MutableStateFlow("")
    private val dao = graph.db.noteDao()

    val state = combine(kind, search, dao.observeAll()) { k, q, notes ->
        BrainUiState(
            k,
            q,
            notes.filter { (k == null || it.kind == k.name) && (q.isBlank() || it.text.contains(q.trim(), ignoreCase = true)) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrainUiState())

    fun selectKind(value: NoteKind?) {
        kind.value = value
    }

    fun setSearch(value: String) {
        search.value = value
    }

    /** Returns false if the text looks like a password or PIN, which Memora refuses to store. */
    fun add(text: String): Boolean {
        if (PrivacyFilter.isSensitive(text)) return false
        viewModelScope.launch {
            dao.insert(
                Note(
                    text = text.trim(),
                    kind = NoteKind.NOTE.name,
                    createdAt = System.currentTimeMillis(),
                    source = MoneyRecord.MANUAL,
                ),
            )
        }
        return true
    }

    fun togglePin(note: Note) {
        viewModelScope.launch { dao.update(note.copy(pinned = !note.pinned)) }
    }

    fun delete(note: Note) {
        viewModelScope.launch { dao.delete(note) }
    }
}

@Composable
fun BrainScreen() {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    val vm: BrainViewModel = viewModel(factory = viewModelFactory { initializer { BrainViewModel(graph) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    BrainContent(state, vm::selectKind, vm::setSearch, { adding = true }, vm::togglePin, vm::delete)
    if (adding) AddNoteSheet({ adding = false }, vm::add)
}

/** Plain data in, callbacks out. No database, so tests can render it directly. */
@Composable
fun BrainContent(
    state: BrainUiState,
    onKind: (NoteKind?) -> Unit,
    onSearch: (String) -> Unit,
    onAdd: () -> Unit,
    onPin: (Note) -> Unit,
    onDelete: (Note) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().riseIn(0), verticalAlignment = Alignment.Top) {
                Text("Second\nBrain", Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, color = scheme.onBackground)
                CircleIconButton(Icons.Rounded.Add, "Add a note", onAdd, size = 56, container = Lime, tint = Ink)
            }
        }
        item { SearchField(state.search, onSearch, Modifier.riseIn(1)) }
        item {
            val options = listOf<Pair<NoteKind?, String>>(null to "All") + NoteKind.entries.map { it to it.label }
            FilterPills(options, state.kind, onKind, Modifier.riseIn(1))
        }
        if (state.notes.isEmpty()) {
            item {
                NoteCard(
                    "Nothing here yet",
                    "Bills, deliveries, tickets and events from your allowed apps appear here. " +
                        "You can also tap + or tell the chat \"remember that...\".",
                )
            }
        } else {
            items(state.notes, key = { it.id }) { note -> NoteRow(note, onPin, onDelete, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(scheme.surface)
            .padding(horizontal = 20.dp, vertical = 15.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { field ->
                if (value.isEmpty()) Text("Search your brain", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                field()
            },
        )
    }
}

@Composable
private fun NoteRow(note: Note, onPin: (Note) -> Unit, onDelete: (Note) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val kind = runCatching { NoteKind.valueOf(note.kind) }.getOrDefault(NoteKind.NOTE)
    val (fill, ink) = when (kind) {
        NoteKind.NOTE -> scheme.surface to scheme.onSurface
        NoteKind.BILL -> scheme.errorContainer to scheme.onErrorContainer
        NoteKind.DELIVERY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        NoteKind.TICKET -> scheme.secondaryContainer to scheme.onSecondaryContainer
        NoteKind.EVENT -> scheme.primaryContainer to scheme.onPrimaryContainer
    }
    Surface(shape = MaterialTheme.shapes.medium, color = fill, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(note.text, style = MaterialTheme.typography.titleMedium, color = ink)
                Text(
                    "${kind.label} · ${formatCaptureTime(note.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.7f),
                )
            }
            CircleIconButton(
                Icons.Rounded.Star,
                if (note.pinned) "Unpin" else "Pin",
                { onPin(note) },
                size = 42,
                container = Color.Transparent,
                tint = if (note.pinned) scheme.primary else ink.copy(alpha = 0.28f),
            )
            CircleIconButton(Icons.Rounded.Close, "Delete note", { onDelete(note) }, size = 42, container = Color.Transparent, tint = ink.copy(alpha = 0.6f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddNoteSheet(onDismiss: () -> Unit, onSave: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Remember this", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; refused = false },
                label = { Text("What should I remember?") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (refused) {
                Text(
                    "That looks like a password or PIN, so Memora won't store it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { if (onSave(text)) onDismiss() else refused = true },
                enabled = text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}
