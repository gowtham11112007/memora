package com.pulse.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.pulse.app.data.MoneyRecord
import com.pulse.app.domain.RecordType
import com.pulse.app.domain.formatRupees
import com.pulse.app.ui.theme.Ink
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A soft spring that shrinks a control slightly while it is pressed. */
@Composable
fun Modifier.bounceClick(role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, role = role) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }
}

/** Fades and rises into place. A later index starts slightly later, which gives a cascade. */
@Composable
fun Modifier.riseIn(index: Int = 0): Modifier {
    var shown by rememberSaveable { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 420, delayMillis = index * 55, easing = FastOutSlowInEasing),
        label = "rise",
    )
    LaunchedEffect(Unit) { shown = true }
    return graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 28.dp.toPx()
    }
}

/** The diagonal hatch used on calendar days and chart bars. Clip the element first. */
fun Modifier.hatched(line: Color, ground: Color): Modifier = drawBehind {
    drawRect(ground)
    val step = 5.dp.toPx()
    var x = -size.height
    while (x < size.width) {
        drawLine(line, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.5.dp.toPx())
        x += step
    }
}

/** A rupee amount that counts up or down when it changes. */
@Composable
fun AnimatedRupees(paise: Long, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(paise.toFloat(), tween(650, easing = FastOutSlowInEasing), label = "amount")
    // At rest show the exact value, because a Float cannot hold large amounts precisely.
    val text = if (kotlin.math.abs(shown - paise.toFloat()) < 1f) formatRupees(paise) else formatRupees(shown.toLong() / 100 * 100)
    Text(text, modifier, style = style, color = color, maxLines = 1)
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier,
    )
}

/** Round button used in screen headers and on cards. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Int = 54,
    container: Color = MaterialTheme.colorScheme.surface,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier
            .bounceClick(onClick = onClick)
            .size(size.dp)
            .clip(CircleShape)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint)
    }
}

/** Back button and centred title. The trailing slot balances the button so the title stays centred. */
@Composable
fun ScreenHeader(title: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
        Text(
            title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (trailing != null) trailing() else Spacer(Modifier.size(54.dp))
    }
}

@Composable
fun <T> FilterPills(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val fill by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                label = "pillFill",
            )
            val ink by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "pillInk",
            )
            val shape = RoundedCornerShape(50)
            Box(
                modifier = Modifier
                    .bounceClick(Role.Tab) { onSelect(value) }
                    .clip(shape)
                    .background(fill)
                    .border(1.dp, if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outline, shape)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
            }
        }
    }
}

/** Soft overlapping petals in a card's corner. Decorative only. */
@Composable
fun PetalDecoration(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val origin = Offset(size.width * 0.80f, size.height * 1.02f)
        val petal = Size(size.height * 0.36f, size.height * 0.78f)
        listOf(-58f, -22f, 14f, 50f).forEach { angle ->
            rotate(angle, pivot = origin) {
                drawOval(color, topLeft = Offset(origin.x - petal.width / 2, origin.y - petal.height), size = petal)
            }
        }
    }
}

/** One transaction. onDark is for rows inside the dark panel. */
@Composable
fun RecordRow(
    record: MoneyRecord,
    category: String?,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    onLime: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val income = record.type == RecordType.INCOME
    val name = record.merchant ?: if (income) "Unknown sender" else "Unknown payee"
    val primaryText = when {
        onLime -> Ink
        onDark -> MaterialTheme.colorScheme.inverseOnSurface
        else -> MaterialTheme.colorScheme.onSurface
    }
    val secondaryText = if (onDark || onLime) primaryText.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant
    val tile = when {
        onLime -> Ink.copy(alpha = 0.1f)
        onDark -> Color.White.copy(alpha = 0.12f)
        income -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val time = formatCaptureTime(record.occurredAt)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.bounceClick(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(tile), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = primaryText)
        }
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                color = primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (category != null) "$category · $time" else time,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryText,
                maxLines = 1,
            )
        }
        Text(
            (if (income) "+" else "−") + formatRupees(record.amountPaise),
            style = MaterialTheme.typography.titleMedium,
            color = primaryText,
        )
    }
}

/** A plain message card, used for empty states and short explanations. */
@Composable
fun NoteCard(title: String?, body: String, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The real icon of an installed app, on a soft lavender tile. Blank tile if it cannot be loaded. */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun rememberAppLabel(packageName: String): String {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName.substringAfterLast('.'))
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val dayFormat = DateTimeFormatter.ofPattern("d MMM, h:mm a")

fun formatCaptureTime(epochMs: Long): String {
    val zoned = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return if (zoned.toLocalDate() == LocalDate.now()) zoned.format(timeFormat) else zoned.format(dayFormat)
}
