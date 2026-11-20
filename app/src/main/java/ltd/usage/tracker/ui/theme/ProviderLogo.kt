package ltd.usage.tracker.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ltd.usage.tracker.R
import ltd.usage.tracker.data.Provider

/** 实际绘制用的变体：彩色原色，或单色再 tint 成 onSurface。 */
enum class ProviderLogoVariant { COLOR, MONO }

/**
 * 一家服务商对应的 logo 资源与在亮/暗色下该用哪套。
 * [colorRes] 为空表示 lobe-icons 没有彩色版（或按需求只用单色）。
 */
data class ProviderLogoSpec(
    val colorRes: Int?,
    val monoRes: Int,
    val useColorInLight: Boolean,
    val useColorInDark: Boolean,
) {
    fun variant(isDark: Boolean): ProviderLogoVariant {
        val preferColor = if (isDark) useColorInDark else useColorInLight
        return if (preferColor && colorRes != null) ProviderLogoVariant.COLOR else ProviderLogoVariant.MONO
    }

    fun drawable(isDark: Boolean): Int =
        if (variant(isDark) == ProviderLogoVariant.COLOR) colorRes!! else monoRes
}

/**
 * 已收录服务商的 logo 映射。未知 providerId 返回 null，调用方回退到首字母。
 * ChatGPT 订阅与 OpenAI 共用，Claude 订阅与 Anthropic/Claude 共用。
 */
fun providerLogoSpec(providerId: String): ProviderLogoSpec? = when (Provider.from(providerId)) {
    Provider.DEEPSEEK -> ProviderLogoSpec(
        R.drawable.ic_logo_deepseek_color, R.drawable.ic_logo_deepseek_mono,
        useColorInLight = true, useColorInDark = false, // #4D6BFE 在暗色容器上对比不足 3:1
    )
    Provider.OPENAI, Provider.CHATGPT_SUB -> ProviderLogoSpec(
        null, R.drawable.ic_logo_openai_mono,
        useColorInLight = false, useColorInDark = false, // 无 Color 变体
    )
    Provider.ANTHROPIC, Provider.CLAUDE_SUB -> ProviderLogoSpec(
        R.drawable.ic_logo_claude_color, R.drawable.ic_logo_claude_mono,
        useColorInLight = true, useColorInDark = true, // 品牌橙 #D97757，暗色对比足够
    )
    Provider.KIMI -> ProviderLogoSpec(
        R.drawable.ic_logo_kimi_color, R.drawable.ic_logo_kimi_mono,
        useColorInLight = true, useColorInDark = false, // 彩色版主体是 currentColor/黑，暗色会消失
    )
    Provider.ZHIPU -> ProviderLogoSpec(
        R.drawable.ic_logo_zhipu_color, R.drawable.ic_logo_zhipu_mono,
        useColorInLight = true, useColorInDark = false, // #3859FF 暗色对比不足
    )
    Provider.XAI -> ProviderLogoSpec(
        null, R.drawable.ic_logo_xai_mono,
        useColorInLight = false, useColorInDark = false, // 无 Color 变体
    )
    Provider.MISTRAL -> ProviderLogoSpec(
        R.drawable.ic_logo_mistral_color, R.drawable.ic_logo_mistral_mono,
        useColorInLight = true, useColorInDark = true, // 多色风车，两套背景下都认得出
    )
    Provider.OPENROUTER -> ProviderLogoSpec(
        null, R.drawable.ic_logo_openrouter_mono,
        useColorInLight = false, useColorInDark = false, // Color 是霓虹黄，亮色几乎看不见，按单色处理
    )
    Provider.SILICONFLOW -> ProviderLogoSpec(
        R.drawable.ic_logo_siliconcloud_color, R.drawable.ic_logo_siliconcloud_mono,
        useColorInLight = true, useColorInDark = false, // #6E29F6 暗色对比不足
    )
    Provider.NOVITA -> ProviderLogoSpec(
        R.drawable.ic_logo_novita_color, R.drawable.ic_logo_novita_mono,
        useColorInLight = false, useColorInDark = true, // #23D57C 在浅色容器上几乎看不见
    )
    Provider.DEEPINFRA -> ProviderLogoSpec(
        R.drawable.ic_logo_deepinfra_color, R.drawable.ic_logo_deepinfra_mono,
        useColorInLight = true, useColorInDark = false, // 深蓝 #2A3275 在暗色里会融进底
    )
    Provider.STEPFUN -> ProviderLogoSpec(
        R.drawable.ic_logo_stepfun_color, R.drawable.ic_logo_stepfun_mono,
        useColorInLight = true, useColorInDark = true, // 蓝渐变在两套背景上都清楚
    )
    Provider.OPENCODE_GO -> ProviderLogoSpec(
        null, R.drawable.ic_logo_opencode_mono,
        useColorInLight = false, useColorInDark = false, // 无 Color 变体
    )
    Provider.COMMAND_CODE -> ProviderLogoSpec(
        null, R.drawable.ic_logo_commandcode_mono,
        useColorInLight = false, useColorInDark = false, // 无 Color 变体
    )
    null -> null
}

/** 测试与调用方用：null 表示没有收录 logo，应回退首字母。 */
fun providerLogoVariant(providerId: String, isDark: Boolean): ProviderLogoVariant? =
    providerLogoSpec(providerId)?.variant(isDark)

/**
 * 只画 logo 本身（登录标题用 20dp）。旁边已有厂商名，contentDescription 为 null。
 * 未知服务商不画。
 */
@Composable
fun ProviderLogoImage(
    providerId: String,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    val spec = providerLogoSpec(providerId) ?: return
    val isDark = Palette.isDark
    val useColor = spec.variant(isDark) == ProviderLogoVariant.COLOR
    Icon(
        painter = painterResource(spec.drawable(isDark)),
        contentDescription = null,
        modifier = modifier.size(size),
        tint = if (useColor) Color.Unspecified else MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * 40dp MaterialShapes 容器 + 居中 22dp logo。
 * 底色用中性的 surfaceContainerHighest，不用 tonal。没有 logo 时回退首字母。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProviderMarkBadge(
    providerId: String,
    accountName: String,
    providerName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    logoSize: Dp = 22.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = providerPolygon(providerId).toShape()
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(scheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (providerLogoSpec(providerId) != null) {
            ProviderLogoImage(providerId = providerId, size = logoSize)
        } else {
            Text(
                providerLetter(accountName, providerName),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = scheme.onSurface,
            )
        }
    }
}
