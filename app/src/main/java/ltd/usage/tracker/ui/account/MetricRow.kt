package ltd.usage.tracker.ui.account

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.fx.Fx
import ltd.usage.tracker.ui.format.formatMoney
import ltd.usage.tracker.ui.format.metricCaption
import ltd.usage.tracker.ui.theme.Motion
import ltd.usage.tracker.ui.theme.NumberStyle
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.Type

// ---------------------------------------------------------------- 指标行与进度条

/**
 * 单条指标：标签 · 进度条 · 数值。
 *
 * 进度条只在「有总额度」时出现：
 *  - 有 [Metric.quotaMinor]：已用 = quota - balance，精确到分
 *  - 只有 [Metric.usedPercent]：直接用百分比
 *  - 两者都没有：不画进度条，只显示金额（如 DeepSeek 只报余额）
 *
 * 不拿余额瞎猜分母 —— 那会画出「永远 100% 未用」的假进度条。
 */
@Composable
internal fun MetricRow(m: Metric, displayCurrency: String, compact: Boolean = false) {
    val cur = m.currency ?: displayCurrency
    val amount = m.balanceMinor
    val shown = amount?.let {
        runCatching { Fx.convert(it, cur, displayCurrency) }.getOrDefault(it)
    }

    // 已用比例 0f..1f；null 表示无法计算，不画进度条
    val usedFraction: Float? = when {
        m.quotaMinor != null && m.quotaMinor > 0 && amount != null -> {
            val used = m.quotaMinor - amount
            (used.toDouble() / m.quotaMinor).coerceIn(0.0, 1.0).toFloat()
        }
        m.usedPercent != null -> (m.usedPercent / 100.0).coerceIn(0.0, 1.0).toFloat()
        else -> null
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        if (compact) {
            // 数值已在右上角显示：只画条和「标签 · 已用 x% · 重置」一行说明
            if (usedFraction != null) {
                UsageBar(fraction = usedFraction, color = usageBarColor(usedFraction))
                Spacer(Modifier.height(3.dp))
            }
            Text(metricCaption(m), fontSize = Type.LabelSize.sp, color = Palette.TextTertiary)
            return@Column
        }
        // 标签、数值、单位三种字号，按基线对齐
        Row {
            Text(
                m.label,
                fontSize = Type.LabelSize.sp,
                color = Palette.TextTertiary,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.alignByBaseline(),
            )
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    shown != null -> formatMoney(shown)
                    m.usedPercent != null -> "剩 ${(100.0 - m.usedPercent).coerceIn(0.0, 100.0).toInt()}%"
                    else -> "—"
                },
                fontSize = Type.CaptionSize.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.TextSecondary,
                style = NumberStyle,
                modifier = Modifier.alignByBaseline(),
            )
            if (shown != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    displayCurrency,
                    fontSize = Type.MicroSize.sp,
                    color = Palette.TextTertiary,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        if (usedFraction != null) {
            Spacer(Modifier.height(5.dp))
            UsageBar(fraction = usedFraction, color = usageBarColor(usedFraction))
            m.note?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, fontSize = Type.LabelSize.sp, color = Palette.TextTertiary)
            }
        } else {
            m.note?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = Type.LabelSize.sp, color = Palette.TextTertiary)
            }
        }
    }
}

/**
 * 用量条颜色分两档：≥80% 警示（Warning），≥95% 见底（Negative）。
 * 单档 90% 时，从 89% 到 100% 看起来一模一样，来不及提前发现。
 */
@Composable
private fun usageBarColor(fraction: Float): Color = when {
    fraction >= 0.95f -> Palette.Negative
    fraction >= 0.80f -> Palette.Warning
    else -> Palette.BarFill
}

/**
 * 用量进度条。
 *
 * 极简风格：4dp 高、0 圆角硬边（圆角在细条上会显得像胶囊，破坏硬边风格）。
 * 填充色由调用方按档位给出。动画用 effectsDefault，临界阻尼不回弹。
 */
@Composable
private fun UsageBar(fraction: Float, color: Color) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = Motion.effectsDefault(),
        label = "usageBar",
    )
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(Palette.Track),
    ) {
        val fillWidth = maxWidth * animated
        Box(
            Modifier
                .width(fillWidth)
                .fillMaxHeight()
                .background(color),
        )
    }
}
