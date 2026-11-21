package ltd.usage.tracker.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.fx.Fx
import ltd.usage.tracker.ui.format.formatMoney
import ltd.usage.tracker.ui.theme.RollingNumber
import ltd.usage.tracker.ui.theme.SummaryNumberStyle
import ltd.usage.tracker.ui.theme.Type
import ltd.usage.tracker.ui.theme.rememberReduceMotion
import ltd.usage.tracker.ui.theme.warning

/** 汇总计算结果，顶栏副标题和汇总卡共用。 */
internal data class UsageSummary(
    val total: Long?,
    val unconvertible: Set<String>,
    val hasPercentOnly: Boolean,
)

internal fun computeUsageSummary(
    accounts: List<StoredAccount>,
    displayCurrency: String,
    latestFor: (String) -> UsageSnapshot?,
): UsageSummary {
    val target = displayCurrency
    var converted = 0L
    var convertedAny = false
    val unconvertible = mutableSetOf<String>()

    accounts.forEach { acc ->
        val snap = latestFor(acc.id)?.takeIf { it.error == null } ?: return@forEach
        val fromMetrics = snap.metrics.mapNotNull { m ->
            m.balanceMinor?.let { (m.currency ?: snap.currency ?: target) to it }
        }
        val items = fromMetrics.ifEmpty {
            listOfNotNull(snap.balanceMinor?.let { (snap.currency ?: target) to it })
        }
        for ((cur, v) in items) {
            val r = runCatching { Fx.convert(v, cur, target) }.getOrNull()
            if (r != null) {
                converted += r
                convertedAny = true
            } else {
                unconvertible += cur
            }
        }
    }
    val hasPercentOnly = accounts.any { acc ->
        val snap = latestFor(acc.id)?.takeIf { it.error == null } ?: return@any false
        snap.balanceMinor == null && snap.metrics.any { it.usedPercent != null }
    }
    return UsageSummary(
        total = if (convertedAny) converted else null,
        unconvertible = unconvertible,
        hasPercentOnly = hasPercentOnly,
    )
}

@Composable
internal fun formatSummarySubtitle(summary: UsageSummary, currency: String): String {
    val total = summary.total ?: return "暂无数据"
    return "${formatMoney(total)} $currency"
}

/** 汇总卡：顶部 Hero 资产总览卡，圆角 24dp，带细描边与紧凑币种切换。 */
@Composable
internal fun SummaryRow(
    total: Long?,
    currency: String,
    count: Int,
    otherCurrencies: Set<String> = emptySet(),
    hasPercentOnly: Boolean = false,
    fxLive: Boolean = true,
    onCurrencyChange: (String) -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val scheme = MaterialTheme.colorScheme
    val symbol = if (currency == "CNY") "¥" else "$"

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp)
            .testTag("summaryCard"),
        shape = RoundedCornerShape(24.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.22f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "总可用余额",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                    color = scheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (fxLive) scheme.tertiary else scheme.warning),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if (fxLive) "实时汇率" else "离线估算",
                        fontSize = Type.LabelSize.sp,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            if (total != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        symbol,
                        style = SummaryNumberStyle.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
                        color = scheme.onPrimaryContainer.copy(alpha = 0.78f),
                        modifier = Modifier.padding(bottom = 5.dp, end = 3.dp),
                    )
                    RollingNumber(
                        text = formatMoney(total),
                        style = SummaryNumberStyle,
                        color = scheme.onPrimaryContainer,
                        reduceMotion = reduceMotion,
                    )
                }
            } else {
                Text(
                    "暂无数据",
                    fontSize = Type.PlaceholderSize.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }

            Spacer(Modifier.height(10.dp))
            CurrencyToggleRow(
                current = currency,
                onChange = onCurrencyChange,
                caption = summaryCaption(
                    count = count,
                    otherCurrencies = otherCurrencies,
                    hasPercentOnly = hasPercentOnly,
                    fxLive = fxLive,
                    failedAll = total == null,
                ),
            )
        }
    }
}

internal fun summaryCaption(
    count: Int,
    otherCurrencies: Set<String> = emptySet(),
    hasPercentOnly: Boolean = false,
    fxLive: Boolean = true,
    failedAll: Boolean = false,
): String = buildString {
    if (failedAll) {
        append("$count 个服务 · 全部获取失败")
        return@buildString
    }
    append("$count 个服务")
    if (otherCurrencies.isNotEmpty()) {
        append(" · 未能换算 ")
        append(otherCurrencies.sorted().joinToString("/"))
    }
    if (hasPercentOnly) append(" · 含按比例计费的额度")
    if (!fxLive) append(" · 离线汇率")
}

@Composable
private fun CurrencyToggleRow(
    current: String,
    onChange: (String) -> Unit,
    caption: String,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .testTag("summaryCurrencyRow"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CurrencySegmentedPill(current = current, onChange = onChange)
        Spacer(Modifier.width(10.dp))
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onPrimaryContainer.copy(alpha = 0.76f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CurrencySegmentedPill(
    current: String,
    onChange: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val options = listOf("USD", "CNY")
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.onPrimaryContainer.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { code ->
                val isSelected = code == current
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isSelected) scheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                        .semantics {
                            role = Role.Tab
                            selected = isSelected
                        }
                        .clickable { onChange(code) }
                        .padding(horizontal = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        code,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (isSelected) scheme.onPrimary else scheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}
