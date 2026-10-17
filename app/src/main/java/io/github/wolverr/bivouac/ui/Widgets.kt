package io.github.wolverr.bivouac.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app mark: a tent under a pastel rainbow. Same geometry as the launcher icon (108-unit grid). */
@Composable
fun BivouacMark(size: Dp, modifier: Modifier = Modifier) {
    val rainbow = LocalRainbow.current.pastel
    val tent = MaterialTheme.colorScheme.onBackground
    val door = MaterialTheme.colorScheme.background
    Canvas(modifier.size(size)) {
        val u = this.size.width / 62f  // the mark is the icon's 23..85 x 21..83 window
        fun p(x: Float, y: Float) = Offset((x - 23f) * u, (y - 21f) * u)
        rainbow.forEachIndexed { i, c ->
            val r = (28f - i * 3.2f) * u
            val center = p(54f, 62f)
            drawArc(
                c, startAngle = 180f, sweepAngle = 180f, useCenter = false,
                topLeft = Offset(center.x - r, center.y - r), size = Size(2 * r, 2 * r),
                style = Stroke(width = 3.2f * u, cap = StrokeCap.Round),
            )
        }
        drawPath(Path().apply { moveTo(p(54f, 42f)); lineTo(p(72f, 72f)); lineTo(p(36f, 72f)); close() }, tent)
        drawPath(Path().apply { moveTo(p(54f, 55f)); lineTo(p(60f, 72f)); lineTo(p(48f, 72f)); close() }, door)
    }
}

private fun Path.moveTo(o: Offset) = moveTo(o.x, o.y)
private fun Path.lineTo(o: Offset) = lineTo(o.x, o.y)

/**
 * Progress in rainbow colors. [progress] null = working without a known end:
 * the colors drift along the bar instead.
 */
@Composable
fun RainbowProgress(progress: Float?, modifier: Modifier = Modifier) {
    val rainbow = LocalRainbow.current.pastel
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val shift by rememberInfiniteTransition(label = "rainbow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "shift",
    )
    Canvas(modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))) {
        drawRect(track)
        val w = size.width
        val span = w * 0.6f
        val brush = Brush.linearGradient(
            rainbow + rainbow.first(),
            start = Offset(shift * span, 0f), end = Offset(shift * span + span, 0f), tileMode = TileMode.Repeated,
        )
        drawRect(brush, size = Size(w * (progress?.coerceIn(0f, 1f) ?: 1f), size.height))
    }
}

/** A citation number in its source's color, e.g. the "3" of [3]. */
@Composable
fun SourceBadge(n: Int, label: String = n.toString(), modifier: Modifier = Modifier) {
    val rb = LocalRainbow.current
    Box(
        modifier.size(22.dp).clip(CircleShape).background(rb.pastel(n)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = Color(0xFF222222), fontSize = 11.sp, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** A small rounded tag: "Offline", "Recommended", "Loaded". */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, textColor: Color = Color(0xFF222222)) {
    Box(modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}
