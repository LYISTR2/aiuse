package ltd.usage.tracker.ui.account

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.fx.Fx
import ltd.usage.tracker.ui.format.formatMoney
import ltd.usage.tracker.ui.format.metricCaption
import ltd.usage.tracker.ui.theme.Motion
import ltd.usage.tracker.ui.theme.NumberStyle
import ltd.usage.tracker.ui.theme.Type
import ltd.usage.tracker.ui.theme.usageBarColor

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
internal fun MetricRow(
    m: Metric,
    displayCurrency: String,
    compact: Boolean = false,
    wavy: Boolean = false,
) {
    val cur = m.currency ?: displayCurrency
    val amount = m.balanceMinor
    val shown = amount?.let {
        runCatching { Fx.convert(it, cur, displayCurrency) }.getOrDefault(it)
    }

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
            if (usedFraction != null) {
                UsageBar(fraction = usedFraction, color = usageBarColor(usedFraction), wavy = wavy)
                Spacer(Modifier.height(3.dp))
            }
            Text(metricCaption(m), fontSize = Type.LabelSize.sp, color = MaterialTheme.colorScheme.outline)
            return@Column
        }
        Row {
            Text(
                m.label,
                fontSize = Type.LabelSize.sp,
                color = MaterialTheme.colorScheme.outline,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = NumberStyle,
                modifier = Modifier.alignByBaseline(),
            )
            if (shown != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    displayCurrency,
                    fontSize = Type.MicroSize.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        if (usedFraction != null) {
            Spacer(Modifier.height(5.dp))
            UsageBar(fraction = usedFraction, color = usageBarColor(usedFraction), wavy = wavy)
            m.note?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, fontSize = Type.LabelSize.sp, color = MaterialTheme.colorScheme.outline)
            }
        } else {
            m.note?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, fontSize = Type.LabelSize.sp, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

/**
 * 用量条：8dp、圆头、带 stop indicator。Wavy 只在刷新中出现，静态数值走直条。
 */
@Composable
private fun UsageBar(fraction: Float, color: androidx.compose.ui.graphics.Color, wavy: Boolean) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = Motion.effectsDefault(),
        label = "usageBar",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val strokeWidth = with(LocalDensity.current) { 8.dp.toPx() }
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    val modifier = Modifier.fillMaxWidth().height(8.dp)
    if (wavy) {
        LinearWavyProgressIndicator(
            progress = { animated },
            modifier = modifier,
            color = color,
            trackColor = track,
            stroke = stroke,
            trackStroke = stroke,
            stopSize = WavyProgressIndicatorDefaults.LinearTrackStopIndicatorSize,
        )
    } else {
        LinearProgressIndicator(
            progress = { animated },
            modifier = modifier,
            color = color,
            trackColor = track,
            strokeCap = StrokeCap.Round,
            gapSize = 4.dp,
            drawStopIndicator = {
                drawCircle(color = color, radius = size.height / 2f)
            },
        )
    }
}
