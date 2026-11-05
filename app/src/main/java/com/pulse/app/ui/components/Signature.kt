package com.pulse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A card with a raised folder tab whose base curves into the card. The tab spans the fractions
 * [from] to [to] of the width, and it can slide because the fractions can be animated.
 */
class FolderTabShape(
    private val from: Float,
    private val to: Float,
    private val tabHeight: Dp = 54.dp,
    private val radius: Dp = 24.dp,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = with(density) { tabHeight.toPx() }
        val r = with(density) { radius.toPx() }
        val w = size.width
        val bottom = size.height
        val a = w * from
        val b = w * to
        val path = Path().apply {
            moveTo(a + r, 0f)
            lineTo(b - r, 0f)
            quadraticBezierTo(b, 0f, b, r)
            if (b < w - r) {
                lineTo(b, h - r)
                quadraticBezierTo(b, h, b + r, h)
                lineTo(w - r, h)
                quadraticBezierTo(w, h, w, h + r)
            }
            lineTo(w, bottom - r)
            quadraticBezierTo(w, bottom, w - r, bottom)
            lineTo(r, bottom)
            quadraticBezierTo(0f, bottom, 0f, bottom - r)
            if (a > r) {
                lineTo(0f, h + r)
                quadraticBezierTo(0f, h, r, h)
                lineTo(a - r, h)
                quadraticBezierTo(a, h, a, h - r)
                lineTo(a, r)
                quadraticBezierTo(a, 0f, a + r, 0f)
            } else {
                lineTo(0f, r)
                quadraticBezierTo(0f, 0f, r, 0f)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/** A rounded bar with a smooth cup cut out of the top centre, where the round button sits. */
class NotchedBarShape(private val cupRadius: Dp, private val corner: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { corner.toPx() }
        val cup = with(density) { cupRadius.toPx() }
        val mid = size.width / 2
        val flare = cup * 0.42f
        val depth = cup * 0.98f
        val path = Path().apply {
            moveTo(c, 0f)
            lineTo(mid - cup - flare, 0f)
            cubicTo(mid - cup, 0f, mid - cup, flare * 0.6f, mid - cup * 0.8f, depth * 0.62f)
            cubicTo(mid - cup * 0.55f, depth, mid - cup * 0.3f, depth, mid, depth)
            cubicTo(mid + cup * 0.3f, depth, mid + cup * 0.55f, depth, mid + cup * 0.8f, depth * 0.62f)
            cubicTo(mid + cup, flare * 0.6f, mid + cup, 0f, mid + cup + flare, 0f)
            lineTo(size.width - c, 0f)
            arcTo(Rect(size.width - 2 * c, 0f, size.width, 2 * c), -90f, 90f, false)
            lineTo(size.width, size.height - c)
            arcTo(Rect(size.width - 2 * c, size.height - 2 * c, size.width, size.height), 0f, 90f, false)
            lineTo(c, size.height)
            arcTo(Rect(0f, size.height - 2 * c, 2 * c, size.height), 90f, 90f, false)
            lineTo(0f, c)
            arcTo(Rect(0f, 0f, 2 * c, 2 * c), 180f, 90f, false)
            close()
        }
        return Outline.Generic(path)
    }
}

/** The small round arrow that sits in the corner of a tile, tilted to point up and out. */
@Composable
fun ArrowChip(fill: Color, tint: Color, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    Box(modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(size * 0.5f).rotate(-45f), tint = tint)
    }
}

