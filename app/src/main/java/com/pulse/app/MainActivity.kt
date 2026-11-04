package com.pulse.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pulse.app.ui.ask.AskScreen
import com.pulse.app.ui.brain.BrainScreen
import com.pulse.app.ui.calendar.CalendarScreen
import com.pulse.app.ui.components.PulseNav
import com.pulse.app.ui.home.HomeScreen
import com.pulse.app.ui.records.RecordsScreen
import com.pulse.app.ui.settings.AiScreen
import com.pulse.app.ui.settings.ReadingScreen
import com.pulse.app.ui.settings.SettingsScreen
import com.pulse.app.ui.theme.PulseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PulseTheme { PulseRoot() } }
    }
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
}

@Composable
private fun PulseRoot() {
    var route by rememberSaveable { mutableStateOf(Route.HOME) }
    val snackbar = remember { SnackbarHostState() }

    // Reminder popups need this on Android 13+, so ask at launch, not when Calendar first opens.
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val back = {
        route = if (route == Route.READING || route == Route.AI) Route.SETTINGS else Route.HOME
    }
    BackHandler(enabled = route != Route.HOME, onBack = back)

    PulseShell(route, { route = it }, snackbar) { current ->
        when (current) {
            Route.HOME -> HomeScreen(
                onViewRecords = { route = Route.RECORDS },
                onOpenReading = { route = Route.READING },
                onOpenCalendar = { route = Route.CALENDAR },
                onAsk = { route = Route.ASK },
            )
            Route.CALENDAR -> CalendarScreen()
            Route.ASK -> AskScreen(onOpenAi = { route = Route.AI })
            Route.BRAIN -> BrainScreen()
            Route.SETTINGS -> SettingsScreen(
                onOpenReading = { route = Route.READING },
                onOpenAi = { route = Route.AI },
            )
            Route.RECORDS -> RecordsScreen(onBack = back)
            Route.READING -> ReadingScreen(snackbar, onBack = back)
            Route.AI -> AiScreen(onBack = back)
        }
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
        ) { current -> content(current) }

        AnimatedVisibility(
            visible = route.tab != null && !keyboardOpen,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 10.dp),
            enter = slideInVertically(tween(320)) { it * 2 } + fadeIn(),
            exit = slideOutVertically(tween(220)) { it * 2 } + fadeOut(),
        ) {
            PulseNav(selected = route.tab ?: 0, onSelect = { index -> onRoute(tabs[index]) })
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp))
    }
}
