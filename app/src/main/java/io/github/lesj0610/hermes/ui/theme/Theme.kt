package io.github.lesj0610.hermes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Slate ground, single brass accent.
 *
 * The app commits to a dark surface in both system themes: transcripts are
 * mostly command output, logs and code, and a light ground makes those harder
 * to scan. Run state is carried by a separate semantic set that never doubles
 * as the accent.
 */
private val Ground = Color(0xFF10161A)
private val Panel = Color(0xFF19222A)
private val PanelRaised = Color(0xFF212C35)
private val Line = Color(0xFF2B3843)
private val TextPrimary = Color(0xFFE4EBF0)
private val TextMuted = Color(0xFF8FA0AC)
private val Brass = Color(0xFFD2A354)
private val OnBrass = Color(0xFF14181B)

/** State colors. Not interchangeable with the accent. */
data class RunColors(
    val running: Color = Brass,
    val awaiting: Color = Color(0xFFE0A83C),
    val completed: Color = Color(0xFF6FA97B),
    val failed: Color = Color(0xFFCB6058),
    val muted: Color = TextMuted,
    val line: Color = Line,
    val panel: Color = Panel,
    val panelRaised: Color = PanelRaised,
)

val LocalRunColors = staticCompositionLocalOf { RunColors() }

private val HermesColorScheme = darkColorScheme(
    primary = Brass,
    onPrimary = OnBrass,
    secondary = TextMuted,
    onSecondary = OnBrass,
    background = Ground,
    onBackground = TextPrimary,
    surface = Ground,
    onSurface = TextPrimary,
    surfaceVariant = Panel,
    onSurfaceVariant = TextMuted,
    surfaceContainer = Panel,
    surfaceContainerHigh = PanelRaised,
    outline = Line,
    outlineVariant = Line,
    error = Color(0xFFCB6058),
    onError = OnBrass,
)

/** Monospace carries machine-authored strings: commands, paths, tool names, ids. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)

private val HermesTypography = Typography(
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
    ),
)

/** No light variant on purpose — see the palette note above. */
@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRunColors provides RunColors()) {
        MaterialTheme(
            colorScheme = HermesColorScheme,
            typography = HermesTypography,
            content = content,
        )
    }
}
