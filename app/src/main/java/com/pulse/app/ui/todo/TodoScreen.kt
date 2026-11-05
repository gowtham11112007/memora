package com.pulse.app.ui.todo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
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
import com.pulse.app.data.Todo
import com.pulse.app.domain.PrivacyFilter
import com.pulse.app.service.ReminderPrompt
import com.pulse.app.ui.components.CircleIconButton
import com.pulse.app.ui.components.NoteCard
import com.pulse.app.ui.components.ScreenHeader
import com.pulse.app.ui.components.SectionLabel
import com.pulse.app.ui.components.rememberUndo
import com.pulse.app.ui.components.riseIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodoViewModel(private val graph: PulseGraph) : ViewModel() {
    private val dao = graph.db.todoDao()
    val todos = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || PrivacyFilter.isSensitive(clean)) return
        viewModelScope.launch { dao.insert(Todo(text = clean, createdAt = System.currentTimeMillis(), source = MoneyRecord.MANUAL)) }
    }

    fun toggle(todo: Todo) {
        viewModelScope.launch { dao.update(todo.copy(done = !todo.done)) }
    }

    fun accept(todo: Todo) {
        viewModelScope.launch {
            dao.update(todo.copy(confirmed = true))
            ReminderPrompt.dismissTodo(graph.appContext, todo.id)
        }
    }

    fun delete(todo: Todo) {
        viewModelScope.launch {
            dao.delete(todo)
            ReminderPrompt.dismissTodo(graph.appContext, todo.id)
        }
    }

    fun restore(todo: Todo) {
        viewModelScope.launch { dao.insert(todo) }
    }
}

@Composable
fun TodoScreen(onBack: () -> Unit) {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    val vm: TodoViewModel = viewModel(factory = viewModelFactory { initializer { TodoViewModel(graph) } })
    val todos by vm.todos.collectAsStateWithLifecycle()
    val undo = rememberUndo()
    TodoContent(todos, onBack, vm::add, vm::toggle, vm::accept) {
        vm.delete(it)
        undo("To-do deleted") { vm.restore(it) }
    }
}

/** Plain data in, callbacks out. No database, so tests can render it directly. */
@Composable
fun TodoContent(
    todos: List<Todo>,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onToggle: (Todo) -> Unit,
    onAccept: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
) {
    val suggested = todos.filter { !it.confirmed }
    val open = todos.filter { it.confirmed && !it.done }
    val done = todos.filter { it.confirmed && it.done }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("To-do list", onBack) }
        item { AddRow(onAdd, Modifier.padding(top = 6.dp).riseIn(0)) }
        if (suggested.isNotEmpty()) {
            item { SectionLabel("From your messages", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(suggested, key = { it.id }) { SuggestedRow(it, onAccept, onDelete, Modifier.animateItem()) }
        }
        if (open.isEmpty() && suggested.isEmpty() && done.isEmpty()) {
            item { NoteCard(null, "Nothing here yet. Type something above, or say \"add milk to my to-do\" in chat.") }
        }
        if (open.isNotEmpty()) {
            item { SectionLabel("To do · ${open.size}", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(open, key = { it.id }) { TodoRow(it, onToggle, onDelete, Modifier.animateItem()) }
        }
        if (done.isNotEmpty()) {
            item { SectionLabel("Done · ${done.size}", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(done, key = { it.id }) { TodoRow(it, onToggle, onDelete, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun AddRow(onAdd: (String) -> Unit, modifier: Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add something to do") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        CircleIconButton(
            Icons.Rounded.Add,
            "Add to-do",
            { submit() },
            container = MaterialTheme.colorScheme.primary,
            tint = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@Composable
private fun SuggestedRow(todo: Todo, onAccept: (Todo) -> Unit, onDelete: (Todo) -> Unit, modifier: Modifier) {
    val fill = MaterialTheme.colorScheme.secondaryContainer
    val ink = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(shape = MaterialTheme.shapes.medium, color = fill, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text(todo.text, style = MaterialTheme.typography.titleMedium, color = ink)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Add this to your list?", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.75f))
                TextButton(onClick = { onDelete(todo) }) { Text("No", color = ink) }
                TextButton(onClick = { onAccept(todo) }) { Text("Add", color = ink) }
            }
        }
    }
}

@Composable
private fun TodoRow(todo: Todo, onToggle: (Todo) -> Unit, onDelete: (Todo) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val ink = if (todo.done) scheme.onSurfaceVariant else scheme.onSurface
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // An empty circle that fills with a tick when done, like a checkbox.
            CircleIconButton(
                Icons.Rounded.Check,
                if (todo.done) "Mark as not done" else "Mark as done",
                { onToggle(todo) },
                size = 48,
                container = if (todo.done) scheme.tertiaryContainer else scheme.surfaceVariant,
                tint = if (todo.done) scheme.onTertiaryContainer else Color.Transparent,
            )
            Text(
                todo.text,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = ink,
                textDecoration = if (todo.done) TextDecoration.LineThrough else null,
            )
            CircleIconButton(
                Icons.Rounded.Close,
                "Delete to-do",
                { onDelete(todo) },
                size = 48,
                container = Color.Transparent,
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}
