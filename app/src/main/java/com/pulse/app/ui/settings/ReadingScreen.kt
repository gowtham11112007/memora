package com.pulse.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.data.AppPermission
import com.pulse.app.data.CHAT_PACKAGES
import com.pulse.app.data.MESSAGING_PACKAGES
import com.pulse.app.domain.CaptureDecision
import com.pulse.app.ui.components.AppIcon
import com.pulse.app.ui.components.FilterPills
import com.pulse.app.ui.components.NoteCard
import com.pulse.app.ui.components.ScreenHeader
import com.pulse.app.ui.components.SectionLabel
import com.pulse.app.ui.components.formatCaptureTime
import com.pulse.app.ui.components.labelled
import com.pulse.app.ui.components.riseIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppItem(val pkg: String, val label: String)
data class AppRow(val app: AppItem, val allowed: Boolean, val lastSeen: Long? = null)
data class ReadingUiState(
    val loading: Boolean = true,
    val sources: List<AppRow> = emptyList(),
    val others: List<AppRow> = emptyList(),
    val chats: List<AppRow> = emptyList(),
)

/** Worth surfacing first (only shown if installed). Bank SMS arrive through the messaging apps. */
private val SOURCE_PACKAGES = MESSAGING_PACKAGES + setOf(
    "com.google.android.apps.nbu.paisa.user", // Google Pay
    "com.phonepe.app", // PhonePe
    "net.one97.paytm", // Paytm
    "in.org.npci.upiapp", // BHIM
    "com.dreamplug.androidapp", // CRED
)

private const val GPAY = "com.google.android.apps.nbu.paisa.user"

private data class Sample(val title: String, val text: String)

private val PAYMENT_SAMPLES = listOf(
    Sample("Google Pay", "Paid ₹250.00 to Swiggy. UPI Ref No. 412345678901"),
    Sample("Google Pay", "₹1,200.00 sent to Uber India. UPI Ref 509812340011"),
    Sample("Google Pay", "Received ₹5,000.00 from Rahul Kumar"),
)
private val OTP_SAMPLE = Sample("Google Pay", "482913 is your OTP for login. Do not share it with anyone.")

class ReadingViewModel(private val graph: PulseGraph) : ViewModel() {
    private val db = graph.db
    private val installed = MutableStateFlow<List<AppItem>?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) { installed.value = loadLaunchableApps() }
    }

    val state = combine(installed, db.appPermissionDao().observeAll(), db.appSeenDao().observeAll()) { apps, permissions, seen ->
        if (apps == null) {
            ReadingUiState(loading = true)
        } else {
            val allowed = permissions.filter { it.allowed }.map { it.packageName }.toSet()
            val lastSeen = seen.associate { it.packageName to it.lastSeenAt }
            val rows = apps.map { AppRow(it, it.pkg in allowed, lastSeen[it.pkg]) }
            ReadingUiState(
                loading = false,
                // Messaging first: it is the only way to catch money the user sends.
                sources = rows.filter { it.app.pkg in SOURCE_PACKAGES }.sortedBy { it.app.pkg !in MESSAGING_PACKAGES },
                others = rows.filter { it.app.pkg !in SOURCE_PACKAGES && it.app.pkg !in CHAT_PACKAGES },
                chats = rows.filter { it.app.pkg in CHAT_PACKAGES },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingUiState())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()
    private var nextPayment = 0

    fun setAllowed(app: AppItem, allowed: Boolean) {
        viewModelScope.launch { db.appPermissionDao().upsert(AppPermission(app.pkg, app.label, allowed)) }
    }

    fun simulatePayment() = simulate(PAYMENT_SAMPLES[nextPayment++ % PAYMENT_SAMPLES.size])

    fun simulateOtp() = simulate(OTP_SAMPLE)

    /** Goes through the exact same CaptureRepository path as a real notification. */
    private fun simulate(sample: Sample) {
        viewModelScope.launch {
            val pkg = db.appPermissionDao().firstAllowedPackage() ?: GPAY
            val decision = graph.captureRepository.capture(pkg, sample.title, sample.text, System.currentTimeMillis())
            _messages.tryEmit(
                when (decision) {
                    CaptureDecision.STORE -> "Saved. It now shows on Home."
                    CaptureDecision.PAUSED -> "Reading is paused, so nothing was read."
                    CaptureDecision.IGNORED_NOT_ALLOWED -> "Ignored: no app is allowed yet. Turn one on below."
                    CaptureDecision.DROPPED_SENSITIVE -> "Dropped: it looks like a code or password. Nothing was stored."
                    CaptureDecision.DROPPED_EMPTY -> "Ignored: the notification had no text."
                    CaptureDecision.MUTED -> "Skipped: this sender is muted."
                    CaptureDecision.NOTHING_USEFUL -> "Read, but nothing in it was worth keeping."
                    CaptureDecision.DUPLICATE -> "Already recorded in the last 2 minutes."
                },
            )
        }
    }

    private fun loadLaunchableApps(): List<AppItem> {
        val ctx = graph.appContext
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppItem(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != ctx.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}

private fun isListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

/** Re-checks the permission every time the app comes back to the foreground (e.g. from Settings). */
@Composable
fun rememberNotificationAccess(): State<Boolean> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(isListenerEnabled(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = isListenerEnabled(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
fun ReadingScreen(snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = (context.applicationContext as PulseApp).graph
    val vm: ReadingViewModel = viewModel(factory = viewModelFactory { initializer { ReadingViewModel(graph) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val accessOn by rememberNotificationAccess()
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    var paused by remember { mutableStateOf(graph.aiSettings.isPaused()) }

    ReadingContent(
        paused = paused,
        onPause = {
            paused = it
            graph.aiSettings.setPaused(it)
        },
        state = state,
        accessOn = accessOn,
        onBack = onBack,
        onOpenAccess = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        onOpenBattery = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        },
        onToggle = vm::setAllowed,
        onPayment = vm::simulatePayment,
        onOtp = vm::simulateOtp,
    )
}

private enum class AppFilter(val label: String) {
    ALL("All"),
    ALLOWED("Allowed"),
    MONEY("Money"),
    CHATS("Chats"),
}

private data class AppSection(val title: String, val hint: String?, val rows: List<AppRow>)

/** Plain data in, callbacks out. No database, so tests can render it directly. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadingContent(
    state: ReadingUiState,
    accessOn: Boolean,
    onBack: () -> Unit,
    onOpenAccess: () -> Unit,
    onOpenBattery: () -> Unit,
    onToggle: (AppItem, Boolean) -> Unit,
    onPayment: () -> Unit,
    onOtp: () -> Unit,
    paused: Boolean = false,
    onPause: (Boolean) -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    val searching = query.isNotBlank()
    val all = state.sources + state.chats + state.others
    val matches = { row: AppRow -> row.app.label.contains(query.trim(), ignoreCase = true) }
    val moneyHint = "To catch money you send, turn on your SMS app. Payment apps often notify only when you receive money."
    val sections = when {
        filter == AppFilter.ALLOWED -> listOf(AppSection("Allowed", null, all.filter { it.allowed }.sortedBy { it.app.label.lowercase() }))
        filter == AppFilter.MONEY -> listOf(AppSection("Money", moneyHint, state.sources))
        filter == AppFilter.CHATS -> listOf(AppSection("Chats", null, state.chats))
        searching -> listOf(AppSection("Results", null, all.sortedBy { it.app.label.lowercase() }))
        else -> listOf(
            AppSection("Money", moneyHint, state.sources),
            AppSection("Chats", "For reminders, notes and to-dos. Only bank alerts count as money.", state.chats),
            AppSection("All apps", null, state.others),
        )
    }.map { it.copy(rows = it.rows.filter(matches)) }.filter { it.rows.isNotEmpty() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
    ) {
        item { ScreenHeader("Allowed apps", onBack) }
        item {
            StatusRow(accessOn, paused, all.count { it.allowed }, all.size, onOpenAccess, Modifier.padding(top = 14.dp).riseIn(0))
        }
        stickyHeader(key = "search") {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searching) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterPills(AppFilter.entries.map { it to it.label }, filter, { filter = it })
            }
        }
        when {
            state.loading -> item { Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            sections.isEmpty() -> item {
                NoteCard(
                    null,
                    when {
                        searching -> "No app called \"${query.trim()}\" is installed."
                        filter == AppFilter.ALLOWED -> "No apps allowed yet. Turn one on to start."
                        else -> "None of your apps fit here."
                    },
                )
            }
            else -> sections.forEach { section ->
                item(key = "t-" + section.title) {
                    Column(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionLabel(section.title)
                        section.hint?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                itemsIndexed(section.rows, key = { _, row -> section.title + "-" + row.app.pkg }) { index, row ->
                    AppToggleRow(row, index == 0, index == section.rows.lastIndex, onToggle)
                }
            }
        }
        if (!searching && filter == AppFilter.ALL) {
            item { SectionLabel("Options", Modifier.padding(top = 18.dp, bottom = 8.dp)) }
            item {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Pause reading", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Nothing is read while this is on.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = paused, onCheckedChange = onPause, modifier = Modifier.labelled("Pause reading"))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Keep Memora running", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "In App info, set battery use to Unrestricted.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = onOpenBattery) { Text("Open") }
                        }
                    }
                }
            }
            item { DemoCard(onPayment, onOtp) }
        }
    }
}

/** One calm line, and a button only when something needs doing. */
@Composable
private fun StatusRow(accessOn: Boolean, paused: Boolean, allowed: Int, total: Int, onOpenAccess: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val working = accessOn && !paused
    val fill = if (working) scheme.tertiaryContainer else scheme.errorContainer
    val ink = if (working) scheme.onTertiaryContainer else scheme.onErrorContainer
    Surface(shape = MaterialTheme.shapes.medium, color = fill, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !accessOn -> "Reading is off"
                        paused -> "Reading is paused"
                        else -> "Reading is on"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = ink,
                )
                Text(
                    if (accessOn) "$allowed of $total apps allowed" else "Android needs your permission first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.copy(alpha = 0.78f),
                )
            }
            if (!accessOn) Button(onClick = onOpenAccess) { Text("Turn on") }
        }
    }
}

@Composable
private fun AppToggleRow(row: AppRow, first: Boolean, last: Boolean, onToggle: (AppItem, Boolean) -> Unit) {
    val kind = when (row.app.pkg) {
        in MESSAGING_PACKAGES -> "Bank SMS"
        in CHAT_PACKAGES -> "Chats"
        in SOURCE_PACKAGES -> "Payments"
        else -> null
    }
    val seen = row.lastSeen?.let { "Read ${formatCaptureTime(it)}" }
    val corner = 18.dp
    Surface(
        shape = RoundedCornerShape(
            topStart = if (first) corner else 0.dp,
            topEnd = if (first) corner else 0.dp,
            bottomStart = if (last) corner else 0.dp,
            bottomEnd = if (last) corner else 0.dp,
        ),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable { onToggle(row.app, !row.allowed) },
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIcon(row.app.pkg)
                Column(Modifier.weight(1f)) {
                    Text(row.app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val note = listOfNotNull(kind, seen).joinToString(" · ")
                    if (note.isNotEmpty()) {
                        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Switch(
                    checked = row.allowed,
                    onCheckedChange = { onToggle(row.app, it) },
                    modifier = Modifier.labelled("Read notifications from ${row.app.label}"),
                )
            }
            if (!last) HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun DemoCard(onPayment: () -> Unit, onOtp: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Try a sample", style = MaterialTheme.typography.titleMedium)
            Text(
                "Sends a sample message through the same path as a real notification.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onPayment, modifier = Modifier.weight(1f)) { Text("Payment") }
                OutlinedButton(onClick = onOtp, modifier = Modifier.weight(1f)) { Text("OTP code") }
            }
        }
    }
}
