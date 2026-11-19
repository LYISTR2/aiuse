package ltd.usage.tracker.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

fun Context.supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 跟随系统「减弱动效」：动画时长或过渡缩放为 0 时关掉形状变形和数字滚动。
 * 不读 AccessibilityManager 的推荐超时，那会把「只要开了无障碍」也当成减弱动效。
 */
fun Context.isReduceMotionEnabled(): Boolean {
    fun scale(key: String): Float = Settings.Global.getFloat(contentResolver, key, 1f)
    return scale(Settings.Global.ANIMATOR_DURATION_SCALE) == 0f ||
        scale(Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f
}

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.isReduceMotionEnabled() }
}

/**
 * 应用主题。入口改为 [MaterialExpressiveTheme] + [MotionScheme.expressive]。
 *
 * [dark] 深浅色；[dynamicColor] 为 true 且系统支持时用壁纸动态取色，否则用种子色板。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun UsageTrackerTheme(
    dark: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val useDynamic = dynamicColor && context.supportsDynamicColor()
    val scheme = when {
        useDynamic && dark -> dynamicDarkColorScheme(context)
        useDynamic && !dark -> dynamicLightColorScheme(context)
        dark -> VibrantDarkScheme
        else -> VibrantLightScheme
    }
    val warning = if (dark) WarningDark else WarningLight
    val colors = AppColors(isDark = dark, scheme = scheme, warning = warning)
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}
