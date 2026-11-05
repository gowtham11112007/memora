package com.pulse.app.ui.onboarding

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pulse.app.R
import com.pulse.app.ui.components.bounceClick
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import com.pulse.app.ui.theme.Mint
import com.pulse.app.ui.theme.Periwinkle
import kotlinx.coroutines.launch

private data class Page(val fill: Color, val title: String, val body: String)

private val PAGES = listOf(
    Page(Lime, "Your life,\nremembered\nprivately.", "Memora turns the notifications you already get into money records, reminders and notes."),
    Page(Periwinkle, "You stay\nin control.", "Only apps you choose are read. OTPs and passwords are dropped, and long numbers are masked. Smart reading sends messages from those apps to cloud AI services, and you can turn it off in Settings."),
    Page(Mint, "Let's set\nit up.", "Turn on reading to start, or look around with sample data first. You can change this any time in Settings."),
)

/** First-run welcome. Colours follow the page, so swiping feels like moving through one surface. */
@Composable
fun OnboardingContent(
    onTurnOnReading: () -> Unit,
    onTrySample: () -> Unit,
    onSkip: () -> Unit,
    startPage: Int = 0,
) {
    val pager = rememberPagerState(initialPage = startPage) { PAGES.size }
    val scope = rememberCoroutineScope()
    val fill by animateColorAsState(PAGES[pager.currentPage].fill, label = "onboardingFill")
    val last = pager.currentPage == PAGES.lastIndex
    Column(
        Modifier
            .fillMaxSize()
            .background(fill)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(Ink)) {
            Image(painterResource(R.drawable.ic_launcher_foreground), "Memora logo", Modifier.fillMaxSize())
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { index ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
                Text(PAGES[index].title, style = MaterialTheme.typography.displaySmall, color = Ink)
                Spacer(Modifier.height(14.dp))
                Text(PAGES[index].body, style = MaterialTheme.typography.bodyLarge, color = Ink.copy(alpha = 0.78f))
                Spacer(Modifier.height(24.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PAGES.indices.forEach { index ->
                val width by animateDpAsState(if (index == pager.currentPage) 26.dp else 8.dp, label = "dot")
                Box(
                    Modifier
                        .height(8.dp)
                        .width(width)
                        .clip(CircleShape)
                        .background(Ink.copy(alpha = if (index == pager.currentPage) 1f else 0.25f)),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (last) {
            WideButton("Turn on reading", filled = true, onTurnOnReading)
            Spacer(Modifier.height(10.dp))
            WideButton("Try with sample data", filled = false, onTrySample)
            Spacer(Modifier.height(10.dp))
            WideButton("Skip for now", filled = false, onSkip)
        } else {
            WideButton("Next", filled = true) { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
            Spacer(Modifier.height(10.dp))
            WideButton("Skip", filled = false, onSkip)
        }
    }
}

@Composable
private fun WideButton(text: String, filled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onClick)
            .height(56.dp)
            .clip(shape)
            .then(if (filled) Modifier.background(Ink) else Modifier.border(1.5.dp, Ink.copy(alpha = 0.3f), shape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = if (filled) Lime else Ink,
        )
    }
}
