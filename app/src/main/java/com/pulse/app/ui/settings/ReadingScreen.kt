package com.pulse.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
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

/** Plain data in, callbacks out. No database, so tests can render it directly. */
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
    var showAll by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val visibleOthers = state.others.filter { showAll || it.allowed }
    val hiddenCount = state.others.size - visibleOthers.size
    val found = (state.sources + state.chats + state.others)
        .filter { it.app.label.contains(query.trim(), ignoreCase = true) }
        .sortedBy { it.app.label.lowercase() }
    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ScreenHeader("Notification reading", onBack) }
        item { StatusCard(accessOn, paused, onOpenAccess, Modifier.padding(top = 6.dp).riseIn(0)) }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search your apps") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().riseIn(1),
            )
        }
        if (!searching) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().riseIn(1),
                ) {
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
                }
            }
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().riseIn(1),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Keep Memora running", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "In App info, set battery use to Unrestricted",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onOpenBattery) { Text("Open") }
                    }
                }
            }
        }
        if (state.loading) {
            item { Text("Loading apps…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (searching) {
            if (found.isEmpty()) {
                item { NoteCard(null, "No app called \"${query.trim()}\" is installed.") }
            } else {
                items(found, key = { "f-" + it.app.pkg }) { AppToggleRow(it, onToggle) }
            }
        } else {
            item { SectionLabel("Money sources", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
            item {
                NoteCard(
                    null,
                    "Payment apps usually notify you only when you receive money. To catch money you send, turn on your " +
                        "SMS app, so Memora can read the bank's message. Personal SMS are never saved.",
                )
            }
            items(state.sources, key = { "s-" + it.app.pkg }) { AppToggleRow(it, onToggle) }
            if (state.chats.isNotEmpty()) {
                item { SectionLabel("Chats", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                item {
                    NoteCard(
                        null,
                        "Memora offers a reminder or to-do when a message asks for one, and asks you first. " +
                            "Chats aren't saved, unless you star that person. You can star or mute people from the bell on Home.",
                    )
                }
                items(state.chats, key = { "c-" + it.app.pkg }) { AppToggleRow(it, onToggle) }
            }
            if (visibleOthers.isNotEmpty()) {
                item { SectionLabel("Other apps", Modifier.padding(top = 8.dp, bottom = 2.dp)) }
                items(visibleOthers, key = { "o-" + it.app.pkg }) { AppToggleRow(it, onToggle) }
            }
            if (hiddenCount > 0 || showAll) {
                item {
                    TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (showAll) "Show fewer apps" else "Show all apps ($hiddenCount more)")
                    }
                }
            }
        }
        if (!searching) item { DemoCard(onPayment, onOtp) }
    }
}

@Composable
private fun StatusCard(accessOn: Boolean, paused: Boolean, onOpenAccess: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val working = accessOn && !paused
    val fill = if (working) scheme.tertiaryContainer else scheme.errorContainer
    val ink = if (working) scheme.onTertiaryContainer else scheme.onErrorContainer
    Surface(shape = MaterialTheme.shapes.large, color = fill, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Status", style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.72f))
            Text(
                when {
                    !accessOn -> "Reading is off"
                    paused -> "Reading is paused"
                    else -> "Reading is on"
                },
                style = MaterialTheme.typography.headlineMedium,
                color = ink,
            )
            Text(
                when {
                    !accessOn -> "Android needs your permission before Memora can read any notification."
                    paused -> "Memora is ignoring every notification until you turn this back on."
                    else -> "Only money, reminders and useful updates are kept. Everything else is ignored."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = ink.copy(alpha = 0.78f),
            )
            if (!accessOn) {
                Box(Modifier.padding(top = 6.dp)) {
                    Button(onClick = onOpenAccess, modifier = Modifier.fillMaxWidth()) { Text("Turn on notification reading") }
                }
            }
        }
    }
}

@Composable
private fun AppToggleRow(row: AppRow, onToggle: (AppItem, Boolean) -> Unit) {
    val isSms = row.app.pkg in MESSAGING_PACKAGES
    val seen = row.lastSeen?.let { "Last read ${formatCaptureTime(it)}" } ?: "Nothing read yet"
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(row.app.pkg)
            Column(Modifier.weight(1f)) {
                Text(
                    row.app.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        isSms -> "Bank SMS for money you send. $seen"
                        row.app.pkg in CHAT_PACKAGES -> "Reminders and to-dos. $seen"
                        else -> seen
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = row.allowed,
                onCheckedChange = { onToggle(row.app, it) },
                modifier = Modifier.labelled("Read notifications from ${row.app.label}"),
            )
        }
    }
}

@Composable
private fun DemoCard(onPayment: () -> Unit, onOtp: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Demo tools", style = MaterialTheme.typography.titleMedium)
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
