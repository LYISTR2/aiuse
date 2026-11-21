package ltd.usage.tracker.ui.account

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 单条指标：标签 · 平滑圆角进度条 · 数值。
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

    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (compact) {
            if (usedFraction != null) {
                UsageBar(fraction = usedFraction, color = usageBarColor(usedFraction), wavy = wavy)
                Spacer(Modifier.height(5.dp))
            }
            Text(
                metricCaption(m),
                fontSize = Type.LabelSize.sp,
                color = scheme.onSurfaceVariant,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                m.label,
                fontSize = Type.CaptionSize.sp,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurfaceVariant,
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
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                style = NumberStyle,
                modifier = Modifier.alignByBaseline(),
            )
            if (shown != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    displayCurrency,
                    fontSize = Type.MicroSize.sp,
                    color = scheme.outline,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        if (usedFraction != null) {
            Spacer(Modifier.height(6.dp))
            UsageBar(fraction = usedFraction, color = usageBarColor(usedFraction), wavy = wavy)
            m.note?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, fontSize = Type.LabelSize.sp, color = scheme.outline)
            }
        } else {
            m.note?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, fontSize = Type.LabelSize.sp, color = scheme.outline)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UsageBar(fraction: Float, color: Color, wavy: Boolean) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = Motion.effectsDefault(),
        label = "usageBar",
    )
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    if (wavy) {
        val strokeWidth = with(LocalDensity.current) { 6.dp.toPx() }
        val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        LinearWavyProgressIndicator(
            progress = { animated },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = track,
            stroke = stroke,
            trackStroke = stroke,
            stopSize = 0.dp,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(track),
        ) {
            if (animated > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(color),
                )
            }
        }
    }
}
