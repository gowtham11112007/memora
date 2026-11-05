package com.pulse.app.ui.settings

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.view.WindowManager
import com.pulse.app.FingerprintUnlock
import com.pulse.app.MainActivity
import com.pulse.app.PulseApp
import com.pulse.app.PulseGraph
import com.pulse.app.domain.Category
import com.pulse.app.domain.Exporter
import com.pulse.app.domain.MoneyItem
import com.pulse.app.domain.RecordType
import com.pulse.app.service.EveningBrief
import java.time.ZoneId
import com.pulse.app.ui.components.ArrowChip
import com.pulse.app.ui.components.LocalSnackbar
import com.pulse.app.ui.components.bounceClick
import com.pulse.app.ui.components.labelled
import com.pulse.app.ui.components.riseIn
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val expenses: Int = 0,
    val income: Int = 0,
    val blocked: Int = 0,
    val allowedApps: Int = 0,
    val encrypted: Boolean = false,
)

class SettingsViewModel(private val graph: PulseGraph) : ViewModel() {
    private val db = graph.db

    val state = combine(
        db.moneyRecordDao().observeAll(),
        db.dropLogDao().observeCountSince(0),
        db.appPermissionDao().observeAll(),
    ) { records, blocked, permissions ->
        SettingsUiState(
            expenses = records.count { it.type == RecordType.EXPENSE },
            income = records.count { it.type == RecordType.INCOME },
            blocked = blocked,
            allowedApps = permissions.count { it.allowed },
            encrypted = graph.isDatabaseEncrypted(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    suspend fun exportCsv(): String {
        val items = db.moneyRecordDao().all().map { MoneyItem(it.amountPaise, it.merchant, it.type, it.occurredAt) }
        val rules = db.merchantRuleDao().all().associate { it.merchant to Category.valueOf(it.category) }
        return Exporter.toCsv(items, rules, ZoneId.systemDefault())
    }

    fun deleteEverything() {
        viewModelScope.launch(Dispatchers.IO) {
            // In demo mode this clears only the sample data, and real reminders and keys stay.
            if (!graph.demo) {
                graph.reminders.cancelAll()
                com.pulse.app.service.EmailDigest.schedule(graph.appContext, false)
                graph.aiSettings.clear()
            }
            graph.moneyPrefs.clear()
            db.clearAllTables()
        }
    }
}

@Composable
fun SettingsScreen(
    onOpenReading: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenPeople: () -> Unit,
    onOpenActivity: () -> Unit,
) {
    val graph = (LocalContext.current.applicationContext as PulseApp).graph
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer { SettingsViewModel(graph) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val accessOn by rememberNotificationAccess()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var evening by remember { mutableStateOf(EveningBrief.isOn(context)) }
    var autoReminders by remember { mutableStateOf(graph.aiSettings.isAutoReminders()) }
    var appLock by remember { mutableStateOf(graph.aiSettings.isAppLock()) }
    val snackbar = LocalSnackbar.current
    SettingsContent(
        state,
        accessOn,
        graph.aiSettings.load().isSet,
        onOpenReading,
        onOpenAi,
        vm::deleteEverything,
        onOpenPeople = onOpenPeople,
        onOpenActivity = onOpenActivity,
        autoRemindersOn = autoReminders,
        onAutoReminders = {
            autoReminders = it
            graph.aiSettings.setAutoReminders(it)
        },
        appLockOn = appLock,
        onAppLock = { on ->
            if (on && !context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
                scope.launch { snackbar.showSnackbar("Set a screen lock on your phone first.") }
            } else {
                val apply = {
                    appLock = on
                    graph.aiSettings.setAppLock(on)
                    val window = (context as? Activity)?.window
                    if (on) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    Unit
                }
                // A fingerprint first, so the lock is known to work and nobody else can turn it off.
                FingerprintUnlock.ask(context, apply, apply)
            }
        },
        eveningOn = evening,
        onEvening = {
            evening = it
            EveningBrief.set(context.applicationContext, it)
        },
        onExport = {
            scope.launch {
                val csv = vm.exportCsv()
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Memora records")
                    .putExtra(Intent.EXTRA_TEXT, csv)
                context.startActivity(Intent.createChooser(send, "Export my records"))
            }
        },
        demoOn = graph.demo,
        onDemo = { on ->
            (context.applicationContext as PulseApp).switchDemo(on)
            // A fresh task gives every screen a new ViewModel, reading from the other database.
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        },
    )
}

/** Plain data in, callbacks out. No database, so tests can render it directly. */
@Composable
fun SettingsContent(
    state: SettingsUiState,
    accessOn: Boolean,
    aiConnected: Boolean,
    onOpenReading: () -> Unit,
    onOpenAi: () -> Unit,
    onDeleteEverything: () -> Unit,
    demoOn: Boolean = false,
    onDemo: (Boolean) -> Unit = {},
    eveningOn: Boolean = true,
    onEvening: (Boolean) -> Unit = {},
    onExport: () -> Unit = {},
    onOpenPeople: () -> Unit = {},
    onOpenActivity: () -> Unit = {},
    autoRemindersOn: Boolean = false,
    onAutoReminders: (Boolean) -> Unit = {},
    appLockOn: Boolean = false,
    onAppLock: (Boolean) -> Unit = {},
) {
    var showPrivacy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val readingNote = when {
        !accessOn -> "Off. Tap to turn on"
        state.allowedApps == 1 -> "On · 1 source"
        else -> "On · ${state.allowedApps} sources"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 128.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Settings",
                Modifier.padding(bottom = 4.dp).riseIn(0),
                style = MaterialTheme.typography.headlineLarge,
                color = scheme.onBackground,
            )
        }
        item { DataCard(state, Modifier.riseIn(1)) }
        item {
            Column(Modifier.riseIn(2), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControlTile("Notification reading", readingNote, Lime, Ink, Modifier.weight(1f), onOpenReading)
                    ControlTile(
                        "AI and email",
                        "Smart reading and summaries",
                        scheme.inverseSurface,
                        scheme.inverseOnSurface,
                        Modifier.weight(1f),
                        onOpenAi,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ControlTile("Privacy", if (state.encrypted) "Encrypted on this phone" else "What stays on this phone", scheme.secondaryContainer, scheme.onSecondaryContainer, Modifier.weight(1f)) { showPrivacy = true }
                    ControlTile(
                        if (demoOn) "Delete sample data" else "Delete my data",
                        "Remove everything",
                        scheme.errorContainer,
                        scheme.onErrorContainer,
                        Modifier.weight(1f),
                    ) { confirmDelete = true }
                }
            }
        }
        item { LinkRow("People", "Star or mute contacts in your chat and SMS apps.", onOpenPeople, Modifier.riseIn(3)) }
        item { LinkRow("Notifications read", "See what Memora read and what it did with each one.", onOpenActivity, Modifier.riseIn(3)) }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = Modifier.fillMaxWidth().riseIn(3)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App lock", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Asks for your fingerprint or PIN when you open Memora. Also hides it in recent apps and blocks screenshots.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = appLockOn, onCheckedChange = onAppLock, modifier = Modifier.labelled("App lock"))
                }
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = Modifier.fillMaxWidth().riseIn(3)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic reminders", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Sets a reminder without asking when a non-chat app gives a clear date, and shows Undo. Chats and unclear dates still ask.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoRemindersOn, onCheckedChange = onAutoReminders, modifier = Modifier.labelled("Automatic reminders"))
                }
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = Modifier.fillMaxWidth().riseIn(3)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Evening summary", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "A short recap around 9 PM, on days with something to report. Created on your phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = eveningOn, onCheckedChange = onEvening, modifier = Modifier.labelled("Evening summary"))
                }
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = Modifier.fillMaxWidth().riseIn(3)) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Export my records", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Share your money records as CSV. You choose the app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onExport) { Text("Export") }
                }
            }
        }
        item {
            Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = Modifier.fillMaxWidth().riseIn(3)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Demo mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Shows sample data for presenting. Your real data stays hidden and unchanged.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = demoOn, onCheckedChange = onDemo, modifier = Modifier.labelled("Demo mode"))
                }
            }
        }
    }

    if (showPrivacy) {
        AlertDialog(
            onDismissRequest = { showPrivacy = false },
            confirmButton = { TextButton(onClick = { showPrivacy = false }) { Text("Got it") } },
            title = { Text("Your privacy") },
            text = {
                Text(
                    "• Your records stay on this phone. While Smart reading is on, messages from apps you allow are sent to cloud AI services from Google and Groq. You can turn it off in AI and email.\n" +
                        "• Only apps you turn on are read.\n" +
                        "• OTPs, passwords, CVVs and PINs are dropped before anything is saved.\n" +
                        "• Long reference numbers are masked.\n" +
                        "• Message text is deleted after 24 hours.\n" +
                        "• Short lines from useful messages stay in Second Brain until you delete them. This includes useful lines from chats you allow, and longer messages from people you star.\n" +
                        "• The notifications-read list keeps the sender and what was saved for 7 days. It never keeps messages that had nothing useful.\n" +
                        "• General questions you type are sent to those services too. Google's free tier may use this data to improve its products, so don't type private details.\n" +
                        "• Your chats are saved on this phone until you delete them. Notes and earlier chats that help with a question are sent with it. You can turn this off in AI and email.\n" +
                        "• Codes, passwords, bank alerts and muted people are never sent to them.\n" +
                        "• If you turn on email, weekly totals are sent through Resend. They name no merchants or messages.\n" +
                        if (state.encrypted) {
                            "• Your records, notes, reminders and chats are encrypted on this phone. Your balance, budget and settings are kept separately and are not. App lock guards the screens."
                        } else {
                            "• Your saved data isn't encrypted on this phone right now. Memora tries again each time it starts. App lock guards the screens, not the stored file."
                        },
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = { onDeleteEverything(); confirmDelete = false }) { Text("Delete everything") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            title = { Text("Delete all your data?") },
            text = { Text("This removes every record, reminder, category, allowed app and AI setting from this phone. It can't be undone.") },
        )
    }
}

@Composable
private fun LinkRow(title: String, note: String, onClick: () -> Unit, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = MaterialTheme.shapes.medium, color = scheme.surface, modifier = modifier.bounceClick(onClick = onClick).fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(note, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            ArrowChip(scheme.inverseSurface, scheme.inverseOnSurface, size = 38.dp)
        }
    }
}

@Composable
private fun DataCard(state: SettingsUiState, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val expenseColor = scheme.primary
    val incomeColor = Color(0xFF7FC7A0)
    val blockedColor = Color(0xFFFF8FB6)
    val total = (state.expenses + state.income + state.blocked).coerceAtLeast(1).toFloat()
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val grow by animateFloatAsState(if (started) 1f else 0f, tween(900, easing = FastOutSlowInEasing), label = "ring")
    Surface(shape = MaterialTheme.shapes.large, color = scheme.surface, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your data", style = MaterialTheme.typography.titleLarge)
                LegendRow(expenseColor, "Expenses · ${state.expenses}")
                LegendRow(incomeColor, "Income · ${state.income}")
                LegendRow(blockedColor, "Codes blocked · ${state.blocked}")
            }
            Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
                val track = scheme.surfaceVariant
                val hub = scheme.primaryContainer
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 13.dp.toPx()
                    val box = Size(size.width - stroke, size.height - stroke)
                    val corner = Offset(stroke / 2, stroke / 2)
                    drawArc(track, 0f, 360f, false, corner, box, style = Stroke(stroke))
                    var start = -90f
                    listOf(state.expenses to expenseColor, state.income to incomeColor, state.blocked to blockedColor).forEach { (count, color) ->
                        val sweep = 360f * (count / total) * grow
                        if (sweep > 0f) {
                            drawArc(color, start, sweep, false, corner, box, style = Stroke(stroke, cap = StrokeCap.Round))
                        }
                        start += sweep
                    }
                    drawCircle(hub, radius = size.minDimension * 0.27f)
                }
            }
        }
    }
}

@Composable
private fun LegendRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ControlTile(title: String, note: String, fill: Color, ink: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .bounceClick(onClick = onClick)
            .height(172.dp)
            .clip(MaterialTheme.shapes.large)
            .background(fill)
            .padding(16.dp),
    ) {
        ArrowChip(ink, fill, size = 38.dp)
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleLarge, color = ink)
        Spacer(Modifier.height(2.dp))
        Text(note, style = MaterialTheme.typography.bodySmall, color = ink.copy(alpha = 0.72f))
    }
}
