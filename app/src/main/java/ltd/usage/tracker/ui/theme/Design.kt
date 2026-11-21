package ltd.usage.tracker.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.R

data class AppColors(
    val isDark: Boolean,
    val scheme: ColorScheme,
    val warning: WarningColors,
) {
    val Bg: Color get() = scheme.background
    val Surface: Color get() = scheme.surfaceContainer
    val SurfaceHover: Color get() = scheme.surfaceContainerHigh
    val Divider: Color get() = scheme.outlineVariant
    val TextPrimary: Color get() = scheme.onSurface
    val TextSecondary: Color get() = scheme.onSurfaceVariant
    val TextTertiary: Color get() = scheme.outline
    val Accent: Color get() = scheme.primary
    val Positive: Color get() = scheme.tertiary
    val Negative: Color get() = scheme.error
    val Warning: Color get() = warning.warning
    val BarFill: Color get() = scheme.primary
    val Track: Color get() = scheme.surfaceContainerHighest
    val OnNegative: Color get() = scheme.onError
    val OnErrorContainer: Color get() = scheme.onErrorContainer
    val ErrorContainer: Color get() = scheme.errorContainer
}

internal val LocalAppColors = staticCompositionLocalOf {
    AppColors(isDark = true, scheme = VibrantDarkScheme, warning = WarningDark)
}

val Palette: AppColors
    @Composable @ReadOnlyComposable get() = LocalAppColors.current

val ColorScheme.warning: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning.warning

val ColorScheme.onWarning: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning.onWarning

val ColorScheme.warningContainer: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning.warningContainer

val ColorScheme.onWarningContainer: Color
    @Composable @ReadOnlyComposable get() = LocalAppColors.current.warning.onWarningContainer

object Type {
    const val DisplaySize = 28f
    const val TitleSize = 15f
    const val BodySize = 14f
    const val CaptionSize = 12f
    const val LabelSize = 11f
    const val MicroSize = 10f
    const val HeadlineSize = 22f
    const val SheetTitleSize = 18f
    const val PlaceholderSize = 18f
    const val CaptionLineHeight = 18f
    const val LabelLineHeight = 16f
}

private fun robotoFlexFamily(weight: Int, width: Float = 100f): FontFamily = FontFamily(
    Font(
        resId = R.font.roboto_flex,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(
            FontVariation.weight(weight),
            FontVariation.width(width),
        ),
    ),
)

val RobotoFlexFamily: FontFamily = robotoFlexFamily(400)

val NumberStyle = TextStyle(
    fontFamily = RobotoFlexFamily,
    fontFeatureSettings = "tnum",
)

val SummaryNumberStyle = TextStyle(
    fontFamily = robotoFlexFamily(700, 104f),
    fontFeatureSettings = "tnum",
    fontSize = 38.sp,
    fontWeight = FontWeight(700),
    letterSpacing = (-1.0).sp,
)

val CardNumberStyle = TextStyle(
    fontFamily = robotoFlexFamily(600),
    fontFeatureSettings = "tnum",
    fontSize = 21.sp,
    fontWeight = FontWeight(600),
    letterSpacing = (-0.3).sp,
)

object Motion {
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.72f, stiffness = 800f)

    fun <T> spatialDefault(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1.0f, stiffness = 3800f)

    fun <T> effectsDefault(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1.0f, stiffness = 1600f)

    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

enum class UsageLevel { NORMAL, WARNING, CRITICAL }

fun usageLevel(fraction: Float): UsageLevel = when {
    fraction >= 0.95f -> UsageLevel.CRITICAL
    fraction >= 0.80f -> UsageLevel.WARNING
    else -> UsageLevel.NORMAL
}

@Composable
fun usageBarColor(fraction: Float): Color {
    val scheme = MaterialTheme.colorScheme
    return when (usageLevel(fraction)) {
        UsageLevel.CRITICAL -> scheme.error
        UsageLevel.WARNING -> scheme.warning
        UsageLevel.NORMAL -> scheme.primary
    }
}
