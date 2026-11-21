package ltd.usage.tracker.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 兜底色板：Obsidian & Porcelain Telemetry（深邃石墨蓝 + 瓷白）。
 * 动态取色关闭、或系统低于 Android 12 时使用。
 */
internal val VibrantLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6ECFF),
    onPrimaryContainer = Color(0xFF132054),
    inversePrimary = Color(0xFF93B0FF),
    secondary = Color(0xFF0D9488),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF064E49),
    tertiary = Color(0xFF059669),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD1FAE5),
    onTertiaryContainer = Color(0xFF064E3B),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFF4F6FA),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    surfaceDim = Color(0xFFE6EAF2),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF1F5F9),
    surfaceContainerHighest = Color(0xFFE7ECF4),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFFE2E8F0),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF8FAFC),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF3B5BDB),
)

internal val VibrantDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF7C9BFF),
    onPrimary = Color(0xFF09173D),
    primaryContainer = Color(0xFF192647),
    onPrimaryContainer = Color(0xFFE4ECFF),
    inversePrimary = Color(0xFF3B5BDB),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF133336),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF022C22),
    tertiaryContainer = Color(0xFF0D3B2E),
    onTertiaryContainer = Color(0xFFA7F3D0),
    error = Color(0xFFFB7185),
    onError = Color(0xFF4C0519),
    errorContainer = Color(0xFF441220),
    onErrorContainer = Color(0xFFFFE4E6),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF0B0E14),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E2636),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceDim = Color(0xFF080A0F),
    surfaceBright = Color(0xFF1E2636),
    surfaceContainerLowest = Color(0xFF070A0F),
    surfaceContainerLow = Color(0xFF10151F),
    surfaceContainer = Color(0xFF141B26),
    surfaceContainerHigh = Color(0xFF1B2433),
    surfaceContainerHighest = Color(0xFF232E40),
    outline = Color(0xFF78859A),
    outlineVariant = Color(0xFF222C3D),
    inverseSurface = Color(0xFFF1F5F9),
    inverseOnSurface = Color(0xFF0F172A),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF7C9BFF),
)

/**
 * 额度警示扩展色：语义色不跟随动态取色，避免壁纸把「快用完」染成别的意思。
 */
data class WarningColors(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val WarningLight = WarningColors(
    warning = Color(0xFFD97706),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF78350F),
)

internal val WarningDark = WarningColors(
    warning = Color(0xFFFBBF24),
    onWarning = Color(0xFF451A03),
    warningContainer = Color(0xFF422A08),
    onWarningContainer = Color(0xFFFEF3C7),
)
