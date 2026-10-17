package io.github.wolverr.bivouac.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.wolverr.bivouac.R

/*
 * The look follows vitalik.eth.limo: white page, #333 text, Inter, generous line height,
 * #337ab7 links, and a gold accent in dark mode. Color comes from the pastel rainbow of the
 * early Ethereum look: each source gets its own hue, and its [n] marker in the answer matches.
 */

/** Pastel hues for backgrounds and stripes, and a deeper twin of each for text on white. */
@Immutable
data class Rainbow(val pastel: List<Color>, val ink: List<Color>) {
    fun pastel(n: Int) = pastel[Math.floorMod(n - 1, pastel.size)]
    fun ink(n: Int) = ink[Math.floorMod(n - 1, ink.size)]
    val brush get() = Brush.horizontalGradient(pastel + pastel.first())
}

private val PASTEL = listOf(
    Color(0xFFF6A6C1),  // pink
    Color(0xFFFFC48C),  // peach
    Color(0xFFFFE07A),  // butter
    Color(0xFFA6E3C4),  // mint
    Color(0xFF9DD1F5),  // sky
    Color(0xFFC6B3F0),  // lavender
)
private val INK_LIGHT = listOf(
    Color(0xFFC2306A), Color(0xFFC0621A), Color(0xFF9A7A00),
    Color(0xFF1E8A5C), Color(0xFF2370B5), Color(0xFF6A4BC4),
)
private val INK_DARK = PASTEL

val LocalRainbow = staticCompositionLocalOf { Rainbow(PASTEL, INK_LIGHT) }

private val LightColors = lightColorScheme(
    primary = Color(0xFF337AB7),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEBF8),
    onPrimaryContainer = Color(0xFF173B5C),
    secondaryContainer = Color(0xFFEDE6FB),
    onSecondaryContainer = Color(0xFF3A2C66),
    background = Color.White,
    onBackground = Color(0xFF333333),
    surface = Color.White,
    onSurface = Color(0xFF333333),
    surfaceVariant = Color(0xFFF5F6F8),
    onSurfaceVariant = Color(0xFF6B7078),
    surfaceContainer = Color(0xFFF7F7F8),
    surfaceContainerHighest = Color(0xFFF0F1F4),
    outline = Color(0xFFD9DCE1),
    outlineVariant = Color(0xFFE8EAEE),
    error = Color(0xFFC2306A),
    errorContainer = Color(0xFFFDE7EF),
    onErrorContainer = Color(0xFF6B1036),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC3A554),
    onPrimary = Color(0xFF1C1C1C),
    primaryContainer = Color(0xFF3D3420),
    onPrimaryContainer = Color(0xFFF1E3B8),
    secondaryContainer = Color(0xFF2F2A40),
    onSecondaryContainer = Color(0xFFE2D9FA),
    background = Color(0xFF1C1C1C),
    onBackground = Color(0xFFE5E5E5),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFE5E5E5),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFA8A8A8),
    surfaceContainer = Color(0xFF232323),
    surfaceContainerHighest = Color(0xFF2D2D2D),
    outline = Color(0xFF444444),
    outlineVariant = Color(0xFF333333),
    error = Color(0xFFF6A6C1),
    errorContainer = Color(0xFF4A2030),
    onErrorContainer = Color(0xFFFDE7EF),
)

// One variable font file; each weight picks its instance on the wght axis.
@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

private fun TextStyle.inter(size: Int, line: Int, weight: Int = 400, spacing: Double = 0.0) = copy(
    fontFamily = Inter, fontSize = size.sp, lineHeight = line.sp,
    fontWeight = FontWeight(weight), letterSpacing = spacing.sp,
)

private val BivouacType = Typography().let { t ->
    Typography(
        displaySmall = t.displaySmall.inter(32, 40, 700, -0.5),
        headlineSmall = t.headlineSmall.inter(24, 32, 700, -0.3),
        titleLarge = t.titleLarge.inter(22, 28, 700, -0.3),
        titleMedium = t.titleMedium.inter(17, 24, 600),
        titleSmall = t.titleSmall.inter(15, 22, 600),
        // Long answers read like the blog: roomy lines, a hint of tracking.
        bodyLarge = t.bodyLarge.inter(16, 27, 400, 0.2),
        bodyMedium = t.bodyMedium.inter(15, 23, 400, 0.1),
        bodySmall = t.bodySmall.inter(13, 19, 400, 0.1),
        labelLarge = t.labelLarge.inter(14, 20, 600),
        labelMedium = t.labelMedium.inter(12, 16, 600, 0.2),
        labelSmall = t.labelSmall.inter(11, 15, 500, 0.2),
    )
}

@Composable
fun BivouacTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRainbow provides Rainbow(PASTEL, if (dark) INK_DARK else INK_LIGHT)) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = BivouacType, content = content)
    }
}

/** The thin pastel rainbow under the header. */
@Composable
fun RainbowRule(modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val brush = LocalRainbow.current.brush
    Canvas(modifier.fillMaxWidth().height(height)) {
        drawRect(brush, topLeft = Offset.Zero, size = size)
    }
}
