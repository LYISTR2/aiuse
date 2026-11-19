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

/**
 * 应用色：Material colorScheme 加警告扩展色。
 * 原先 MonoColors 的角色都映射到方案角色，调用点仍写 Palette.xxx，避免一次改完所有引用。
 */
data class AppColors(
    val isDark: Boolean,
    val scheme: ColorScheme,
    val warning: WarningColors,
) {
    val Bg: Color get() = scheme.background
    val Surface: Color get() = scheme.surface
    val SurfaceHover: Color get() = scheme.surfaceContainerHigh
    val Divider: Color get() = scheme.outlineVariant
    val TextPrimary: Color get() = scheme.onSurface
    val TextSecondary: Color get() = scheme.onSurfaceVariant
    /** 第三级灰用 outline，和 onSurface / onSurfaceVariant 凑齐三级。 */
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

/**
 * 排版尺寸。金额改走 [SummaryNumberStyle] / [CardNumberStyle]；
 * 其余仍用这些常量，避免一次改完所有中文字号。
 */
object Type {
    const val DisplaySize = 28f
    const val TitleSize = 15f
    const val BodySize = 13f
    const val CaptionSize = 12f
    const val LabelSize = 11f
    const val MicroSize = 10f
    const val HeadlineSize = 22f
    const val SheetTitleSize = 17f
    const val PlaceholderSize = 18f
    const val CaptionLineHeight = 18f
    const val LabelLineHeight = 15f
}

/**
 * Roboto Flex 可变字体，只用于数字，中文仍走系统 Noto Sans CJK。
 * Compose 1.13 把 fontVariationSettings 从 TextStyle 挪到了 Font 上，
 * 所以不同字重/宽度要做成不同的 FontFamily，不能靠 TextStyle.copy。
 */
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

/** 等宽数字，避免刷新时位数跳动。 */
val NumberStyle = TextStyle(
    fontFamily = RobotoFlexFamily,
    fontFeatureSettings = "tnum",
)

/** 汇总金额：displayLarge 57sp / wght 650 / wdth 112。 */
val SummaryNumberStyle = TextStyle(
    fontFamily = robotoFlexFamily(650, 112f),
    fontFeatureSettings = "tnum",
    fontSize = 57.sp,
    fontWeight = FontWeight(650),
    letterSpacing = (-0.8).sp,
)

/** 卡片金额：titleLarge 22sp / wght 600。 */
val CardNumberStyle = TextStyle(
    fontFamily = robotoFlexFamily(600),
    fontFeatureSettings = "tnum",
    fontSize = 22.sp,
    fontWeight = FontWeight(600),
    letterSpacing = (-0.2).sp,
)

/**
 * 动效 —— 取自 Material 3 Expressive 官方规范。
 *
 * 令牌数值保持不变：位移用 Spatial fast（damping 0.6 / stiffness 800），
 * 淡入用 Effects fast（1.0 / 3800）。全局另有 MotionScheme.expressive()。
 */
object Motion {
    fun <T> spatialFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.6f, stiffness = 800f)

    fun <T> spatialDefault(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> effectsFast(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1.0f, stiffness = 3800f)

    fun <T> effectsDefault(): FiniteAnimationSpec<T> =
        spring(dampingRatio = 1.0f, stiffness = 1600f)

    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** 额度语义档：阈值写在这里，UI 和测试共用，避免两处各写一遍。 */
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
