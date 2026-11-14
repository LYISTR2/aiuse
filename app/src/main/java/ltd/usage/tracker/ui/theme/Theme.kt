package ltd.usage.tracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private fun schemeOf(c: MonoColors) = if (c.isDark) {
    darkColorScheme(
        primary = c.Accent,
        onPrimary = c.Bg,
        background = c.Bg,
        onBackground = c.TextPrimary,
        surface = c.Surface,
        onSurface = c.TextPrimary,
        surfaceVariant = c.SurfaceHover,
        onSurfaceVariant = c.TextSecondary,
        outline = c.Divider,
        outlineVariant = c.Divider,
        error = c.Negative,
        onError = c.Bg,
    )
} else {
    lightColorScheme(
        primary = c.Accent,
        onPrimary = c.Bg,
        background = c.Bg,
        onBackground = c.TextPrimary,
        surface = c.Surface,
        onSurface = c.TextPrimary,
        surfaceVariant = c.SurfaceHover,
        onSurfaceVariant = c.TextSecondary,
        outline = c.Divider,
        outlineVariant = c.Divider,
        error = c.Negative,
        onError = c.Bg,
    )
}

/**
 * 应用主题。
 *
 * [dark] 决定用哪套配色；配色同时通过 CompositionLocal 下发，
 * 这样 `Palette.xxx` 这种非 Composable 上下文里也能拿到正确颜色。
 */
@Composable
fun UsageTrackerTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(LocalMonoColors provides colors) {
        MaterialTheme(colorScheme = schemeOf(colors), content = content)
    }
}
