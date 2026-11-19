package ltd.usage.tracker.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 兜底色板：种子 #4A6300，Vibrant 变体，对比度 0。
 * 由 @material/material-color-utilities 0.2.7 的 SchemeVibrant 离线生成，不手调。
 * 动态取色关闭、或系统低于 Android 12 时使用。
 */
internal val VibrantLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF4D6700),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBCF600),
    onPrimaryContainer = Color(0xFF151F00),
    inversePrimary = Color(0xFFA4D700),
    secondary = Color(0xFF4F6441),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD1EABE),
    onSecondaryContainer = Color(0xFF0E2005),
    tertiary = Color(0xFF3C6842),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBDEFBF),
    onTertiaryContainer = Color(0xFF002109),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF8FBE6),
    onBackground = Color(0xFF191D11),
    surface = Color(0xFFF8FBE6),
    onSurface = Color(0xFF191D11),
    surfaceVariant = Color(0xFFE0E5CC),
    onSurfaceVariant = Color(0xFF444937),
    surfaceDim = Color(0xFFD9DCC8),
    surfaceBright = Color(0xFFF8FBE6),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F5E1),
    surfaceContainer = Color(0xFFEDF0DB),
    surfaceContainerHigh = Color(0xFFE7EAD6),
    surfaceContainerHighest = Color(0xFFE1E4D0),
    outline = Color(0xFF747965),
    outlineVariant = Color(0xFFC4C9B1),
    inverseSurface = Color(0xFF2E3224),
    inverseOnSurface = Color(0xFFF0F3DE),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFF4D6700),
)

internal val VibrantDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFA4D700),
    onPrimary = Color(0xFF263500),
    primaryContainer = Color(0xFF394D00),
    onPrimaryContainer = Color(0xFFBCF600),
    inversePrimary = Color(0xFF4D6700),
    secondary = Color(0xFFB6CEA3),
    onSecondary = Color(0xFF223517),
    secondaryContainer = Color(0xFF384C2C),
    onSecondaryContainer = Color(0xFFD1EABE),
    tertiary = Color(0xFFA2D2A4),
    onTertiary = Color(0xFF0A3818),
    tertiaryContainer = Color(0xFF24502C),
    onTertiaryContainer = Color(0xFFBDEFBF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111509),
    onBackground = Color(0xFFE1E4D0),
    surface = Color(0xFF111509),
    onSurface = Color(0xFFE1E4D0),
    surfaceVariant = Color(0xFF444937),
    onSurfaceVariant = Color(0xFFC4C9B1),
    surfaceDim = Color(0xFF111509),
    surfaceBright = Color(0xFF373B2D),
    surfaceContainerLowest = Color(0xFF0C0F05),
    surfaceContainerLow = Color(0xFF191D11),
    surfaceContainer = Color(0xFF1D2114),
    surfaceContainerHigh = Color(0xFF282B1E),
    surfaceContainerHighest = Color(0xFF333628),
    outline = Color(0xFF8E937D),
    outlineVariant = Color(0xFF444937),
    inverseSurface = Color(0xFFE1E4D0),
    inverseOnSurface = Color(0xFF2E3224),
    scrim = Color(0xFF000000),
    surfaceTint = Color(0xFFA4D700),
)

/**
 * 额度警示扩展色：同样用 SchemeVibrant，种子 #B7791F，取 primary 四件套。
 * 语义色不跟随动态取色，避免墙纸把「快用完」染成别的意思。
 */
data class WarningColors(
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

internal val WarningLight = WarningColors(
    warning = Color(0xFF855300),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFDDB8),
    onWarningContainer = Color(0xFF2A1700),
)

internal val WarningDark = WarningColors(
    warning = Color(0xFFFFB95F),
    onWarning = Color(0xFF472A00),
    warningContainer = Color(0xFF653E00),
    onWarningContainer = Color(0xFFFFDDB8),
)
