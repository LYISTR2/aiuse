package ltd.usage.tracker.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/**
 * 设计系统 —— 极简单色，支持深/浅两套。
 *
 * 原则：
 *  1. 只有黑、白、灰 + 一个强调色。没有渐变、没有彩色头像、没有装饰性色块。
 *  2. 信息密度优先：紧凑行高、小圆角、少留白。
 *  3. 层次靠字重和字号，不靠颜色和阴影。
 *
 * 两套配色不是简单反色，而是各自调过对比度：
 * 浅色下纯黑文字太硬，用 #111 配 #FAFAFA 背景；分割线要更淡，否则显脏。
 */
data class MonoColors(
    val isDark: Boolean,
    val Bg: Color,
    val Surface: Color,
    val SurfaceHover: Color,
    val Divider: Color,
    val TextPrimary: Color,
    val TextSecondary: Color,
    val TextTertiary: Color,
    val Accent: Color,
    val Positive: Color,
    val Negative: Color,
    /** 额度紧张（≥80%）的警示色；≥95% 才升级为 [Negative]，两档让「快用完」和「已见底」能区分。 */
    val Warning: Color,
    /** 进度条已用部分的填充色。 */
    val BarFill: Color,
    /** 进度条未用部分的底色。 */
    val Track: Color,
    /** 红色删除底上的图标色；深浅两套都是白。 */
    val OnNegative: Color,
)

val DarkColors = MonoColors(
    isDark = true,
    Bg = Color(0xFF0A0A0A),
    Surface = Color(0xFF121212),
    SurfaceHover = Color(0xFF1A1A1A),
    Divider = Color(0xFF232323),
    TextPrimary = Color(0xFFF5F5F5),
    // 原 #8A8A8A / #565656 在 #0A0A0A 上对比度不足（三级灰约 2.7:1），提亮到可读
    TextSecondary = Color(0xFFA3A3A3),
    TextTertiary = Color(0xFF7A7A7A),
    Accent = Color(0xFFE8E8E8),
    Positive = Color(0xFF6EE7A8),
    Negative = Color(0xFFF87171),
    Warning = Color(0xFFF5A524),
    BarFill = Color(0xFFE8E8E8),
    Track = Color(0xFF232323),
    OnNegative = Color.White,
)

val LightColors = MonoColors(
    isDark = false,
    Bg = Color(0xFFFAFAFA),
    Surface = Color(0xFFFFFFFF),
    SurfaceHover = Color(0xFFF0F0F0),
    Divider = Color(0xFFE4E4E4),
    TextPrimary = Color(0xFF111111),
    // 浅色三级灰 #767676 在纯白卡片上约 4.5:1，是还能当正文读的最浅灰
    TextSecondary = Color(0xFF525252),
    TextTertiary = Color(0xFF767676),
    Accent = Color(0xFF111111),
    Positive = Color(0xFF12874F),
    Negative = Color(0xFFC0392B),
    // 浅色底上亮黄看不清，用压暗的琥珀色
    Warning = Color(0xFFB7791F),
    BarFill = Color(0xFF111111),
    Track = Color(0xFFE4E4E4),
    OnNegative = Color.White,
)

val LocalMonoColors = staticCompositionLocalOf { DarkColors }

/**
 * 当前配色。
 *
 * 声明成 `@Composable` 属性，让调用点保持 `Palette.Bg` 的写法不变，
 * 但实际读取的是 CompositionLocal —— 切换主题时整棵树自动重组。
 */
val Palette: MonoColors
    @Composable @ReadOnlyComposable get() = LocalMonoColors.current

/**
 * 排版。
 *
 * 字号阶梯紧凑，靠字重拉开层次：
 *   数字 28sp Bold / 标题 15sp SemiBold / 正文 13sp / 辅助 12sp / 标签 11sp
 *
 * 10sp 对中文笔画太密，只留给金额后的币种单位和分隔符这类纯拉丁短符号；
 * 服务商名、指标标签、说明文字一律不小于 11sp。
 */
object Type {
    const val DisplaySize = 28f
    const val TitleSize = 15f
    const val BodySize = 13f
    const val CaptionSize = 12f
    /** 次要中文文字：服务商、指标标签、说明。 */
    const val LabelSize = 11f
    /** 只用于币种单位和分隔符。 */
    const val MicroSize = 10f

    /** 页面大标题「用量」与 xAI 设备码。 */
    const val HeadlineSize = 22f
    /** 底部面板 / 弹窗标题。 */
    const val SheetTitleSize = 17f
    /** 汇总区「暂无数据」占位：比 28sp 数字小得多，一眼看出不是数据。 */
    const val PlaceholderSize = 18f
    /** 多行说明的行高：辅助字号 12sp 配 18，标签字号 11sp 配 15。 */
    const val CaptionLineHeight = 18f
    const val LabelLineHeight = 15f
}

/** 金额、百分比统一用等宽数字：刷新时位数不变的数字不会左右跳。 */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

/**
 * 动效 —— 取自 Material 3 Expressive 官方规范。
 *
 * 极简风格下动效要短、要快、不要弹：
 * 位移用 Spatial fast（damping 0.6 / stiffness 800），淡入用 Effects fast（1.0 / 3800）。
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
