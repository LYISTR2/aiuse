package ltd.usage.tracker.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.fx.Fx
import ltd.usage.tracker.ui.format.formatMoney
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.RollingNumber
import ltd.usage.tracker.ui.theme.SummaryNumberStyle
import ltd.usage.tracker.ui.theme.Type
import ltd.usage.tracker.ui.theme.rememberReduceMotion

/** 汇总计算结果，顶栏副标题和汇总卡共用，避免算两遍还对不上。 */
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
        if (fromMetrics.isNotEmpty()) {
            fromMetrics.forEach { (cur, v) ->
                val r = runCatching { Fx.convert(v, cur, target) }.getOrNull()
                if (r != null) {
                    converted += r
                    convertedAny = true
                } else {
                    unconvertible += cur
                }
            }
        } else {
            snap.balanceMinor?.let {
                val cur = snap.currency ?: target
                val r = runCatching { Fx.convert(it, cur, target) }.getOrNull()
                if (r != null) {
                    converted += r
                    convertedAny = true
                } else {
                    unconvertible += cur
                }
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

/** 汇总卡：全屏唯一的 primaryContainer，圆角 28dp。 */
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            if (total != null) {
                RollingNumber(
                    text = formatMoney(total),
                    style = SummaryNumberStyle,
                    color = scheme.onPrimaryContainer,
                    reduceMotion = reduceMotion,
                )
                Spacer(Modifier.height(12.dp))
                CurrencyToggle(current = currency, onChange = onCurrencyChange)
                Spacer(Modifier.height(8.dp))
                Text(
                    buildString {
                        append("$count 个服务")
                        if (otherCurrencies.isNotEmpty()) {
                            append(" · 未能换算 ")
                            append(otherCurrencies.sorted().joinToString("/"))
                        }
                        if (hasPercentOnly) append(" · 含按比例计费的额度")
                        if (!fxLive) append(" · 离线汇率")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            } else {
                Text(
                    "暂无数据",
                    fontSize = Type.PlaceholderSize.sp,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
                CurrencyToggle(current = currency, onChange = onCurrencyChange)
                Spacer(Modifier.height(8.dp))
                Text(
                    "$count 个服务 · 全部获取失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CurrencyToggle(
    current: String,
    onChange: (String) -> Unit,
) {
    val options = listOf("USD", "CNY")
    val scheme = MaterialTheme.colorScheme
    ButtonGroup(overflowIndicator = {}) {
        options.forEachIndexed { index, c ->
            customItem(
                buttonGroupContent = {
                    val interaction = remember { MutableInteractionSource() }
                    val shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                    ToggleButton(
                        checked = c == current,
                        onCheckedChange = { onChange(c) },
                        shapes = shapes,
                        interactionSource = interaction,
                        modifier = Modifier
                            .animateWidth(interaction)
                            .semantics { role = Role.Tab },
                    ) {
                        Text(c)
                    }
                },
                menuContent = {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(c) },
                        onClick = {
                            onChange(c)
                        },
                    )
                },
            )
        }
    }
}
