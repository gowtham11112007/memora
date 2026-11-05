package com.pulse.app.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.data.ActivityEntry
import com.pulse.app.data.AppPermission
import com.pulse.app.data.ContactRule
import com.pulse.app.data.isPersonalApp
import com.pulse.app.domain.CaptureDecision
import com.pulse.app.domain.ContactMatcher
import com.pulse.app.ui.components.AppIcon
import com.pulse.app.ui.components.ArrowChip
import com.pulse.app.ui.components.CircleIconButton
import com.pulse.app.ui.components.FilterPills
import com.pulse.app.ui.components.NoteCard
import com.pulse.app.ui.components.ScreenHeader
import com.pulse.app.ui.components.SectionLabel
import com.pulse.app.ui.components.bounceClick
import com.pulse.app.ui.components.formatCaptureTime
import com.pulse.app.ui.components.rememberAppLabel
import com.pulse.app.ui.components.rememberUndo
import com.pulse.app.ui.components.riseIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ActivityUiState(
    val entries: List<ActivityEntry> = emptyList(),
    val rules: List<ContactRule> = emptyList(),
)

enum class ActivityFilter(val label: String) {
    ALL("All"),
    SAVED("Saved"),
    STARRED("Starred"),
    BLOCKED("Blocked"),
}

class ActivityViewModel(private val graph: PulseGraph) : ViewModel() {
    private val rules = graph.db.contactRuleDao()

    val state = combine(graph.db.activityDao().observeRecent(), rules.observeAll()) { entries, people ->
        ActivityUiState(entries, people)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityUiState())

    fun setRule(name: String, starred: Boolean) {
        val label = name.trim()
        if (label.isEmpty()) return
        viewModelScope.launch { rules.upsert(ContactRule(label.lowercase(), label, starred)) }
    }

    fun remove(rule: ContactRule) {
        viewModelScope.launch { rules.delete(rule) }
    }

    fun stopReading(pkg: String, label: String) {
        viewModelScope.launch { graph.db.appPermissionDao().upsert(AppPermission(pkg, label, false)) }
    }

    fun restore(rule: ContactRule) {
        viewModelScope.launch { rules.upsert(rule) }
    }
}

@Composable
private fun rememberActivityViewModel(): ActivityViewModel {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    return viewModel(factory = viewModelFactory { initializer { ActivityViewModel(graph) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(onBack: () -> Unit, onOpenPeople: () -> Unit, onOpenApps: () -> Unit) {
    val vm = rememberActivityViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    var opened by remember { mutableStateOf<ActivityEntry?>(null) }
    ActivityContent(state, onBack, onOpenPeople, onOpenApps, onEntry = { opened = it })

    opened?.let { entry ->
        val name = entry.title
        val appLabel = rememberAppLabel(entry.packageName)
        val canManage = isPersonalApp(entry.packageName) && name.isNotBlank()
        val rule = if (canManage) ContactMatcher.match(name, state.rules) { it.name } else null
        ModalBottomSheet(onDismissRequest = { opened = null }, containerColor = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(name.ifBlank { "Hidden for your privacy" }, style = MaterialTheme.typography.titleLarge)
                Text(
                    "From $appLabel · ${formatCaptureTime(entry.at)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(outcomeLabel(entry.outcome), style = MaterialTheme.typography.titleMedium)
                if (entry.detail.isNotBlank()) Text(entry.detail, style = MaterialTheme.typography.bodyMedium)
                if (canManage) {
                    Text(
                        "Star saves their longer messages to Second Brain. Mute means Memora skips their messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.setRule(name, true); opened = null },
                            enabled = rule?.starred != true,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (rule?.starred == true) "Starred" else "Star") }
                        FilledTonalButton(
                            onClick = { vm.setRule(name, false); opened = null },
                            enabled = rule?.starred != false,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (rule?.starred == false) "Muted" else "Mute") }
                    }
                    if (rule != null) {
                        OutlinedButton(onClick = { vm.remove(rule); opened = null }, modifier = Modifier.fillMaxWidth()) {
                            Text("Treat them normally")
                        }
                    }
                }
                OutlinedButton(onClick = { vm.stopReading(entry.packageName, appLabel); opened = null }, modifier = Modifier.fillMaxWidth()) {
                    Text("Stop reading $appLabel")
                }
            }
        }
    }
}

private fun outcomeLabel(outcome: String): String = when (outcome) {
    CaptureDecision.STORE.name -> "Saved"
    CaptureDecision.NOTHING_USEFUL.name -> "Read, nothing kept"
    CaptureDecision.DROPPED_SENSITIVE.name -> "Blocked: code or password"
    CaptureDecision.MUTED.name -> "Muted contact"
    CaptureDecision.DUPLICATE.name -> "Already recorded"
    else -> "Read"
}

private fun isBlocked(entry: ActivityEntry) =
    entry.outcome == CaptureDecision.DROPPED_SENSITIVE.name || entry.outcome == CaptureDecision.MUTED.name

/** Plain data in, callbacks out. No database, so tests can render it directly. */
@Composable
fun ActivityContent(
    state: ActivityUiState,
    onBack: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenApps: () -> Unit,
    onEntry: (ActivityEntry) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    val starred = { entry: ActivityEntry ->
        isPersonalApp(entry.packageName) && ContactMatcher.match(entry.title, state.rules) { it.name }?.starred == true
    }
    val shown = state.entries.filter {
        when (filter) {
            ActivityFilter.ALL -> true
            ActivityFilter.SAVED -> it.outcome == CaptureDecision.STORE.name
            ActivityFilter.STARRED -> starred(it)
            ActivityFilter.BLOCKED -> isBlocked(it)
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("Notifications read", onBack) }
        item { SummaryCard(state.entries, Modifier.padding(top = 6.dp).riseIn(0)) }
        item {
            Row(Modifier.fillMaxWidth().riseIn(1), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ShortcutTile("People", "Star or mute", Modifier.weight(1f), onOpenPeople)
                ShortcutTile("Allowed apps", "Choose what's read", Modifier.weight(1f), onOpenApps)
            }
        }
        item {
            FilterPills(
                options = ActivityFilter.entries.map { it to it.label },
                selected = filter,
                onSelect = { filter = it },
                modifier = Modifier.padding(vertical = 4.dp).riseIn(1),
            )
        }
        if (shown.isEmpty()) {
            item {
                NoteCard(
                    null,
                    if (state.entries.isEmpty()) {
                        "Nothing read yet. Notifications from the apps you allow will show here. Memora keeps this list for 7 days."
                    } else {
                        "Nothing here for this filter."
                    },
                )
            }
        } else {
            items(shown, key = { it.id }) { entry -> EntryRow(entry, starred(entry), onEntry, Modifier.animateItem()) }
        }
    }
}

@Composable
private fun SummaryCard(entries: List<ActivityEntry>, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.inverseOnSurface
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.inverseSurface, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Stat("${entries.size}", "Read", ink, Modifier.weight(1f))
            Stat("${entries.count { it.outcome == CaptureDecision.STORE.name }}", "Saved", ink, Modifier.weight(1f))
            Stat("${entries.count(::isBlocked)}", "Blocked", ink, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(value: String, label: String, ink: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = ink)
        Text(label, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.7f))
    }
}

@Composable
private fun ShortcutTile(title: String, note: String, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .bounceClick(onClick = onClick)
            .clip(MaterialTheme.shapes.medium)
            .background(scheme.secondaryContainer)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = scheme.onSecondaryContainer)
            Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer.copy(alpha = 0.72f))
        }
        ArrowChip(scheme.onSecondaryContainer, scheme.secondaryContainer, size = 32.dp)
    }
}

@Composable
private fun EntryRow(entry: ActivityEntry, starred: Boolean, onEntry: (ActivityEntry) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val appLabel = rememberAppLabel(entry.packageName)
    val (pillFill, pillInk) = when {
        entry.outcome == CaptureDecision.STORE.name -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        isBlocked(entry) -> scheme.errorContainer to scheme.onErrorContainer
        else -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    val tap = Modifier.bounceClick { onEntry(entry) }
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = modifier.then(tap).fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(entry.packageName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (starred) {
                        Icon(Icons.Rounded.Star, contentDescription = "Starred", Modifier.size(16.dp), tint = scheme.primary)
                    }
                    Text(
                        entry.title.ifBlank { "Hidden for your privacy" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "$appLabel · ${formatCaptureTime(entry.at)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.detail.isNotBlank()) {
                    Text(entry.detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(50))
                        .background(pillFill)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(outcomeLabel(entry.outcome), style = MaterialTheme.typography.labelMedium, color = pillInk)
                }
            }
        }
    }
}

@Composable
fun PeopleScreen(onBack: () -> Unit) {
    val vm = rememberActivityViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val undo = rememberUndo()
    PeopleContent(state, onBack, vm::setRule) {
        vm.remove(it)
        undo("${it.label} removed") { vm.restore(it) }
    }
}

/** Plain data in, callbacks out. No database, so tests can render it directly. */
@Composable
fun PeopleContent(
    state: ActivityUiState,
    onBack: () -> Unit,
    onSet: (String, Boolean) -> Unit,
    onRemove: (ContactRule) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val recent = state.entries
        .filter { isPersonalApp(it.packageName) && it.title.isNotBlank() }
        .map { it.title }
        .distinctBy { it.lowercase() }
        .filter { title -> ContactMatcher.match(title, state.rules) { it.name } == null }
        .take(6)
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("People", onBack) }
        item {
            NoteCard(
                null,
                "Works in the chat and SMS apps you allow. Memora matches the name shown in the notification, not a phone number, " +
                    "so type it the way it appears. Starred people's longer messages go to Second Brain. Muted people are skipped.",
                Modifier.padding(top = 6.dp).riseIn(0),
            )
        }
        item {
            Column(Modifier.riseIn(1), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Name, for example Priya") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onSet(name, true); name = "" },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Star") }
                    OutlinedButton(
                        onClick = { onSet(name, false); name = "" },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Mute") }
                }
            }
        }
        val starred = state.rules.filter { it.starred }
        val muted = state.rules.filter { !it.starred }
        if (starred.isNotEmpty()) {
            item { SectionLabel("Starred", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(starred, key = { "s-" + it.name }) { RuleRow(it, onSet, onRemove, Modifier.animateItem()) }
        }
        if (muted.isNotEmpty()) {
            item { SectionLabel("Muted", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(muted, key = { "m-" + it.name }) { RuleRow(it, onSet, onRemove, Modifier.animateItem()) }
        }
        if (recent.isNotEmpty()) {
            item { SectionLabel("Seen recently", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            items(recent, key = { "r-$it" }) { title ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.animateItem().fillMaxWidth()) {
                    Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { onSet(title, true) }) { Text("Star") }
                        TextButton(onClick = { onSet(title, false) }) { Text("Mute") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: ContactRule, onSet: (String, Boolean) -> Unit, onRemove: (ContactRule) -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rule.starred) {
                Icon(Icons.Rounded.Star, contentDescription = null, Modifier.padding(end = 8.dp).size(18.dp), tint = scheme.primary)
            }
            Text(rule.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { onSet(rule.label, !rule.starred) }) { Text(if (rule.starred) "Mute" else "Star") }
            CircleIconButton(
                Icons.Rounded.Close,
                "Remove ${rule.label}",
                { onRemove(rule) },
                size = 48,
                container = Color.Transparent,
                tint = scheme.onSurfaceVariant,
            )
        }
    }
}
