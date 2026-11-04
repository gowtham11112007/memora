package com.pulse.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pulse.app.ui.theme.Ink
import com.pulse.app.ui.theme.Lime
import com.pulse.app.ui.theme.NavDark

/** A four-point spark, the mark for Ask Memora. */
val SparkIcon: ImageVector = materialIcon(name = "Pulse.Spark", autoMirror = false) {
    materialPath {
        moveTo(12f, 2f)
        lineTo(14.3f, 9.7f)
        lineTo(22f, 12f)
        lineTo(14.3f, 14.3f)
        lineTo(12f, 22f)
        lineTo(9.7f, 14.3f)
        lineTo(2f, 12f)
        lineTo(9.7f, 9.7f)
        close()
    }
}

const val TAB_ASK = 2

/** Ink bar with a cup in the middle, holding the lime Ask button. The selected tab lifts and glows lime. */
@Composable
fun PulseNav(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp).height(96.dp)) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(70.dp)
                .clip(NotchedBarShape(cupRadius = 40.dp, corner = 30.dp))
                .background(NavDark)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavItem(Icons.Rounded.Home, "Home", selected == 0, Modifier.weight(1f)) { onSelect(0) }
            NavItem(Icons.Rounded.DateRange, "Calendar", selected == 1, Modifier.weight(1f)) { onSelect(1) }
            Spacer(Modifier.weight(1.1f))
            NavItem(Icons.Rounded.Star, "Second Brain", selected == 3, Modifier.weight(1f)) { onSelect(3) }
            NavItem(Icons.Rounded.Settings, "Settings", selected == 4, Modifier.weight(1f)) { onSelect(4) }
        }
        AskButton(selected == TAB_ASK, Modifier.align(Alignment.TopCenter)) { onSelect(TAB_ASK) }
    }
}

@Composable
private fun AskButton(isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "askScale",
    )
    val turn by animateFloatAsState(
        targetValue = if (isSelected) 90f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "askTurn",
    )
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(64.dp)
            .clip(CircleShape)
            .background(Lime)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(SparkIcon, "Ask Memora", Modifier.size(28.dp).graphicsLayer { rotationZ = turn }, tint = Ink)
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, isSelected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val fill by animateColorAsState(if (isSelected) Lime else Color.Transparent, label = "navFill")
    val tint by animateColorAsState(if (isSelected) Ink else Color.White.copy(alpha = 0.72f), label = "navTint")
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.92f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "navScale",
    )
    Box(
        modifier.selectable(selected = isSelected, role = Role.Tab, onClick = onClick).height(56.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .size(46.dp)
                .clip(CircleShape)
                .background(fill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tint)
        }
    }
}
