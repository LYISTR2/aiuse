package ltd.usage.tracker.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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

/** 汇总卡：全屏唯一的 primaryContainer，圆角 24dp。 */
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
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
            .testTag("summaryCard"),
        shape = RoundedCornerShape(24.dp),
        color = scheme.primaryContainer,
        contentColor = scheme.onPrimaryContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            if (total != null) {
                RollingNumber(
                    text = formatMoney(total),
                    style = SummaryNumberStyle,
                    color = scheme.onPrimaryContainer,
                    reduceMotion = reduceMotion,
                )
                Spacer(Modifier.height(8.dp))
                CurrencyToggleRow(
                    current = currency,
                    onChange = onCurrencyChange,
                    caption = summaryCaption(
                        count = count,
                        otherCurrencies = otherCurrencies,
                        hasPercentOnly = hasPercentOnly,
                        fxLive = fxLive,
                        failedAll = false,
                    ),
                )
            } else {
                Text(
                    "暂无数据",
                    fontSize = Type.PlaceholderSize.sp,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(8.dp))
                CurrencyToggleRow(
                    current = currency,
                    onChange = onCurrencyChange,
                    caption = summaryCaption(
                        count = count,
                        otherCurrencies = otherCurrencies,
                        hasPercentOnly = hasPercentOnly,
                        fxLive = fxLive,
                        failedAll = true,
                    ),
                )
            }
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

/**
 * 按钮组在左、说明文字在右，整行高度固定 40dp。
 * 关闭 LocalMinimumInteractiveComponentSize 的 48dp 布局预留，避免把行高撑开；
 * 每个 32dp 按钮放在 40dp 高的可点子盒子里，把上下各 4dp 也算进点击区。
 */
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
            .height(40.dp)
            .testTag("summaryCurrencyRow"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CurrencyToggle(current = current, onChange = onChange)
        Spacer(Modifier.width(8.dp))
        Text(
            caption,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CurrencyToggle(
    current: String,
    onChange: (String) -> Unit,
) {
    val options = listOf("USD", "CNY")
    // 关掉 48dp 布局预留，行高才压得住；系统仍可能在输入层把点击区扩到约 48dp
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
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
                        Box(
                            modifier = Modifier
                                .height(40.dp)
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                ) { onChange(c) },
                            contentAlignment = Alignment.Center,
                        ) {
                            ToggleButton(
                                checked = c == current,
                                onCheckedChange = { onChange(c) },
                                buttonSize = ToggleButtonSize.ExtraSmall,
                                shapes = shapes,
                                interactionSource = interaction,
                                modifier = Modifier
                                    .animateWidth(interaction)
                                    .semantics { role = Role.Tab },
                            ) {
                                Text(c, fontSize = 13.sp)
                            }
                        }
                    },
                    menuContent = {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(c) },
                            onClick = { onChange(c) },
                        )
                    },
                )
            }
        }
    }
}
