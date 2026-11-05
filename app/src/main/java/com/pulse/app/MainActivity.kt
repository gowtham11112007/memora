package com.pulse.app

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import com.pulse.app.service.EveningBrief
import com.pulse.app.ui.activity.ActivityScreen
import com.pulse.app.ui.activity.PeopleScreen
import com.pulse.app.ui.ask.AskScreen
import com.pulse.app.ui.ask.ChatHistoryScreen
import com.pulse.app.ui.todo.TodoScreen
import com.pulse.app.ui.onboarding.OnboardingContent
import com.pulse.app.ui.brain.BrainScreen
import com.pulse.app.ui.calendar.CalendarScreen
import com.pulse.app.ui.components.LocalSnackbar
import com.pulse.app.ui.components.PulseNav
import com.pulse.app.ui.home.HomeScreen
import com.pulse.app.ui.records.RecordsScreen
import com.pulse.app.ui.settings.AiScreen
import com.pulse.app.ui.settings.ReadingScreen
import com.pulse.app.ui.settings.SettingsScreen
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import com.pulse.app.ui.theme.PulseTheme

class MainActivity : ComponentActivity() {
    /** The page a tapped notification asked for. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(EXTRA_OPEN)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only on a fresh start, so turning the phone doesn't reopen the page.
        if (savedInstanceState == null) openRequest.value = intent.getStringExtra(EXTRA_OPEN)
        enableEdgeToEdge()
        // With App lock on, the screen is also hidden from recent apps and screenshots.
        if ((application as PulseApp).graph.aiSettings.isAppLock()) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { PulseTheme { PulseRoot(openRequest.value) { openRequest.value = null } } }
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}

/**
 * Android's own fingerprint prompt, which also offers the phone's PIN if a finger isn't read.
 * Older Android, or anything unexpected, falls back to the screen lock page, so nobody gets locked out.
 */
object FingerprintUnlock {
    fun ask(context: Context, onUnlocked: () -> Unit, onUnavailable: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            onUnavailable()
            return
        }
        runCatching {
            BiometricPrompt.Builder(context)
                .setTitle("Unlock Memora")
                .setSubtitle("Use your fingerprint")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build()
                .authenticate(
                    CancellationSignal(),
                    context.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onUnlocked()

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            // Backing out just leaves the lock screen up. A real problem, like too many tries, uses the PIN page.
                            val backedOut = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED ||
                                errorCode == BiometricPrompt.BIOMETRIC_ERROR_TIMEOUT
                            if (!backedOut) onUnavailable()
                        }
                    },
                )
        }.onFailure { onUnavailable() }
    }
}

/** Decides when App lock asks again. A short trip away, like a share sheet, doesn't count. */
object AppLockRule {
    const val GRACE_MS = 60_000L

    fun shouldLock(lockOn: Boolean, leftAt: Long, now: Long): Boolean =
        lockOn && (leftAt == 0L || now - leftAt > GRACE_MS)
}

/** Tabs sit on the bottom bar. The others open on top of a tab. */
enum class Route(val tab: Int?) {
    HOME(0),
    CALENDAR(1),
    ASK(2),
    BRAIN(3),
    SETTINGS(4),
    RECORDS(null),
    READING(null),
    AI(null),
    ACTIVITY(null),
    PEOPLE(null),
    TODO(null),
    CHAT_HISTORY(null),
}

@Composable
private fun PulseRoot(openRequest: String?, onOpened: () -> Unit) {
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    // Several pages can open the same sub-page, so Back returns to whichever one you came from.
    var history by rememberSaveable { mutableStateOf(listOf<Route>()) }
    val open = { next: Route ->
        if (next != route) {
            history = if (next.tab != null) emptyList() else history + route
            route = next
        }
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(openRequest) {
        Route.entries.firstOrNull { it.name == openRequest }?.let {
            open(it)
            onOpened()
        }
    }

    // Reminder popups need this on Android 13+, so ask at launch, not when Calendar first opens.
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        EveningBrief.apply(context.applicationContext)
    }

    val appPrefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }

    val settings = (context.applicationContext as PulseApp).graph.aiSettings
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    var locked by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // Saved on disk, so restarting the app can't skip the lock. A phone with no screen lock can't unlock it.
                Lifecycle.Event.ON_START -> locked = locked || (
                    keyguard.isDeviceSecure &&
                        AppLockRule.shouldLock(settings.isAppLock(), appPrefs.getLong("left_at", 0), System.currentTimeMillis())
                    )
                Lifecycle.Event.ON_STOP -> appPrefs.edit().putLong("left_at", System.currentTimeMillis()).apply()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Counting from now means coming back from the PIN page doesn't lock it again straight away.
    val unlocked = {
        appPrefs.edit().putLong("left_at", System.currentTimeMillis()).apply()
        locked = false
    }
    val screenLockPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) unlocked()
    }
    val askToUnlock = {
        FingerprintUnlock.ask(context, unlocked) {
            val page = keyguard.createConfirmDeviceCredentialIntent("Unlock Memora", "Use your screen lock to continue")
            if (page != null) screenLockPage.launch(page) else unlocked()
        }
    }
    if (locked) {
        // The prompt needs the app fully on screen, or Android cancels it.
        LaunchedEffect(Unit) { owner.lifecycle.withResumed { askToUnlock() } }
        LockContent(onUnlock = askToUnlock)
        return
    }

    var welcomed by rememberSaveable { mutableStateOf(appPrefs.getBoolean("welcomed", false)) }
    if (!welcomed) {
        val finish = {
            appPrefs.edit().putBoolean("welcomed", true).apply()
            welcomed = true
        }
        OnboardingContent(
            onTurnOnReading = {
                finish()
                open(Route.READING)
            },
            onTrySample = {
                finish()
                (context.applicationContext as PulseApp).switchDemo(true)
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            },
            onSkip = finish,
        )
        return
    }

    val back = {
        route = history.lastOrNull() ?: Route.HOME
        history = history.dropLast(1)
    }
    BackHandler(enabled = route != Route.HOME, onBack = back)

    PulseShell(route, { open(it) }, snackbar) { current ->
        when (current) {
            Route.HOME -> HomeScreen(
                onViewRecords = { open(Route.RECORDS) },
                onOpenReading = { open(Route.READING) },
                onOpenCalendar = { open(Route.CALENDAR) },
                onAsk = { open(Route.ASK) },
                onOpenActivity = { open(Route.ACTIVITY) },
            )
            Route.CALENDAR -> CalendarScreen(onOpenTodo = { open(Route.TODO) })
            Route.ASK -> AskScreen(onOpenAi = { open(Route.AI) }, onOpenHistory = { open(Route.CHAT_HISTORY) })
            Route.BRAIN -> BrainScreen()
            Route.SETTINGS -> SettingsScreen(
                onOpenReading = { open(Route.READING) },
                onOpenAi = { open(Route.AI) },
                onOpenPeople = { open(Route.PEOPLE) },
                onOpenActivity = { open(Route.ACTIVITY) },
            )
            Route.RECORDS -> RecordsScreen(onBack = back)
            Route.READING -> ReadingScreen(snackbar, onBack = back)
            Route.AI -> AiScreen(onBack = back)
            Route.ACTIVITY -> ActivityScreen(
                onBack = back,
                onOpenPeople = { open(Route.PEOPLE) },
                onOpenApps = { open(Route.READING) },
            )
            Route.PEOPLE -> PeopleScreen(onBack = back)
            Route.TODO -> TodoScreen(onBack = back)
            Route.CHAT_HISTORY -> ChatHistoryScreen(onBack = back)
        }
    }
}

@Composable
fun LockContent(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Ink).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, Modifier.size(48.dp), tint = Lime)
        Text("Memora is locked", Modifier.padding(top = 18.dp), style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text(
            "Use your fingerprint to open it.",
            Modifier.padding(top = 6.dp, bottom = 26.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.72f),
        )
        Button(
            onClick = onUnlock,
            colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Ink),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Unlock") }
    }
}

/**
 * The frame shared by every screen. Screens draw under the status bar so colour reaches the top.
 * Pages slide in the direction you move, and the bar drops away on sub-pages and while typing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PulseShell(
    route: Route,
    onRoute: (Route) -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable (Route) -> Unit,
) {
    val tabs = Route.entries.filter { it.tab != null }
    val keyboardOpen = WindowInsets.isImeVisible
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                val enter = slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { it * direction / 3 } +
                    fadeIn(tween(260, delayMillis = 60))
                val exit = slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it * direction / 5 } +
                    fadeOut(tween(160))
                enter togetherWith exit
            },
            label = "page",
        ) { current -> CompositionLocalProvider(LocalSnackbar provides snackbar) { content(current) } }

        AnimatedVisibility(
            visible = route.tab != null && !keyboardOpen,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            enter = slideInVertically(tween(320)) { it * 2 } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it * 2 } + fadeOut(),
        ) {
            PulseNav(selected = route.tab ?: 0, onSelect = { index -> onRoute(tabs[index]) })
        }
        // Sits above the bottom bar, so Undo is never hidden behind it.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 108.dp))
    }
}
