package ltd.usage.tracker.ui.account

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.fx.Fx
import ltd.usage.tracker.ui.format.formatMoney
import ltd.usage.tracker.ui.format.humanizeError
import ltd.usage.tracker.ui.format.humanizeNote
import ltd.usage.tracker.ui.format.isSameText
import ltd.usage.tracker.ui.format.metricCaption
import ltd.usage.tracker.ui.format.providerLabelOrNull
import ltd.usage.tracker.ui.theme.CardNumberStyle
import ltd.usage.tracker.ui.theme.Motion
import ltd.usage.tracker.ui.theme.ProviderMarkBadge
import ltd.usage.tracker.ui.theme.RollingNumber
import ltd.usage.tracker.ui.theme.Type
import ltd.usage.tracker.ui.theme.UsageLevel
import ltd.usage.tracker.ui.theme.onWarningContainer
import ltd.usage.tracker.ui.theme.rememberReduceMotion
import ltd.usage.tracker.ui.theme.usageLevel
import ltd.usage.tracker.ui.theme.warningContainer

/**
 * 账号只有一个指标、并且右上角显示的正是它时返回该指标。
 */
internal fun singleHeadlineMetric(snapshot: UsageSnapshot?): Metric? {
    if (snapshot == null || snapshot.error != null) return null
    val m = snapshot.metrics.singleOrNull() ?: return null
    val total = snapshot.balanceMinor
    val shown = if (total != null) {
        m.balanceMinor == total && (m.currency ?: snapshot.currency) == snapshot.currency
    } else {
        m.usedPercent != null
    }
    return m.takeIf { shown }
}

/** 保留供 ExpressiveMappingTest 验证圆角映射规则。 */
internal fun groupItemShape(
    index: Int,
    count: Int,
    large: Dp,
    small: Dp = 6.dp,
    lifted: Boolean = false,
): RoundedCornerShape {
    if (lifted) return RoundedCornerShape(28.dp)
    return when {
        count <= 1 -> RoundedCornerShape(large)
        index == 0 -> RoundedCornerShape(topStart = large, topEnd = large, bottomStart = small, bottomEnd = small)
        index == count - 1 -> RoundedCornerShape(topStart = small, topEnd = small, bottomStart = large, bottomEnd = large)
        else -> RoundedCornerShape(small)
    }
}

/**
 * 账号卡片：独立圆角卡片 + 细描边，支持左滑删除、长按拖动排序和按压反馈。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountRow(
    account: StoredAccount,
    snapshot: UsageSnapshot?,
    displayCurrency: String = "USD",
    onDelete: () -> Unit,
    dragging: Boolean = false,
    lifted: Boolean = false,
    swipeEnabled: Boolean = true,
    index: Int = 0,
    count: Int = 1,
    refreshing: Boolean = false,
    needsRelogin: Boolean = false,
    onRelogin: () -> Unit = {},
) {
    val provider = Provider.from(account.providerId)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val corner by animateDpAsState(
        targetValue = when {
            lifted -> 24.dp
            pressed && !reduceMotion -> 14.dp
            else -> 20.dp
        },
        animationSpec = Motion.spatialFast(),
        label = "cardCorner",
    )
    val shape = RoundedCornerShape(corner)
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        targetValue = if (lifted) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        animationSpec = Motion.effectsFast(),
        label = "cardBg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            lifted -> scheme.primary.copy(alpha = 0.55f)
            needsRelogin || snapshot?.error != null -> scheme.error.copy(alpha = 0.35f)
            else -> scheme.outlineVariant.copy(alpha = 0.75f)
        },
        animationSpec = Motion.effectsFast(),
        label = "cardBorder",
    )

    val latestOnDelete = rememberUpdatedState(onDelete)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) latestOnDelete.value()
            value != SwipeToDismissBoxValue.StartToEnd
        },
        positionalThreshold = { total -> total * 0.4f },
    )
    LaunchedEffect(Unit) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    val bottomGap = if (index == count - 1) 0.dp else 10.dp
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = bottomGap),
    ) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            gesturesEnabled = swipeEnabled,
            backgroundContent = {
                if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(shape)
                            .background(scheme.errorContainer)
                            .padding(end = 24.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = scheme.onErrorContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        if (swipeEnabled) {
                            customActions = listOf(
                                CustomAccessibilityAction("删除") {
                                    latestOnDelete.value()
                                    true
                                }
                            )
                        }
                    }
                    .clickable(interactionSource = interaction, indication = null) {
                        if (needsRelogin) onRelogin()
                    },
                shape = shape,
                color = bg,
                border = BorderStroke(1.dp, borderColor),
                tonalElevation = if (lifted) 3.dp else 0.dp,
                shadowElevation = if (lifted) 6.dp else 0.dp,
            ) {
                AccountRowContent(
                    account = account,
                    providerName = provider?.displayName ?: account.providerId,
                    snapshot = snapshot,
                    displayCurrency = displayCurrency,
                    refreshing = refreshing,
                    reduceMotion = reduceMotion,
                    needsRelogin = needsRelogin,
                )
            }
        }
    }
}

@Composable
private fun AccountRowContent(
    account: StoredAccount,
    providerName: String,
    snapshot: UsageSnapshot?,
    displayCurrency: String,
    refreshing: Boolean,
    reduceMotion: Boolean,
    needsRelogin: Boolean = false,
) {
    val failed = snapshot?.error != null || needsRelogin
    val headline = singleHeadlineMetric(snapshot)
    val headlineCaption = headline?.let { metricCaption(it) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderMarkBadge(
                providerId = account.providerId,
                accountName = account.name,
                providerName = providerName,
                size = 42.dp,
                logoSize = 22.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val providerLabel = providerLabelOrNull(account.name, providerName)
                val detail = if (needsRelogin) {
                    "需要重新登录"
                } else {
                    snapshot?.error?.let { humanizeError(it) }
                        ?: snapshot?.note?.let { humanizeNote(it) }
                            ?.takeUnless { headlineCaption != null && isSameText(it, headlineCaption) }
                }
                if (failed && detail != null) {
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = scheme.errorContainer.copy(alpha = 0.85f),
                    ) {
                        Text(
                            detail,
                            fontSize = Type.LabelSize.sp,
                            fontWeight = FontWeight.Medium,
                            color = scheme.onErrorContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                } else if (providerLabel != null || detail != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(providerLabel, detail).joinToString(" · "),
                        fontSize = Type.LabelSize.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            RowTrailing(
                snapshot = snapshot,
                displayCurrency = displayCurrency,
                reduceMotion = reduceMotion,
            )
        }

        val metrics = snapshot?.metrics.orEmpty()
        if (metrics.isNotEmpty()) {
            if (headline == null) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.55f), thickness = 0.75.dp)
                Spacer(Modifier.height(6.dp))
            } else {
                Spacer(Modifier.height(10.dp))
            }
            metrics.forEach { m ->
                MetricRow(
                    m = m,
                    displayCurrency = displayCurrency,
                    compact = m === headline,
                    wavy = refreshing,
                )
            }
        }
    }
}

@Composable
private fun RowTrailing(
    snapshot: UsageSnapshot?,
    displayCurrency: String,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    when {
        snapshot?.error != null -> Text(
            "—",
            style = CardNumberStyle,
            color = scheme.outline,
            modifier = modifier,
        )
        snapshot?.balanceMinor != null -> {
            val minor = snapshot.balanceMinor
            val cur = snapshot.currency ?: displayCurrency
            val shown = runCatching { Fx.convert(minor, cur, displayCurrency) }.getOrDefault(minor)
            Row(modifier, verticalAlignment = Alignment.Bottom) {
                RollingNumber(
                    text = formatMoney(shown),
                    style = CardNumberStyle,
                    color = scheme.onSurface,
                    reduceMotion = reduceMotion,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    displayCurrency,
                    fontSize = Type.MicroSize.sp,
                    fontWeight = FontWeight.Medium,
                    color = scheme.outline,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        else -> {
            val tightest = snapshot?.metrics.orEmpty()
                .filter { it.usedPercent != null }
                .maxByOrNull { it.usedPercent!! }
            if (tightest != null) {
                val usedFraction = (tightest.usedPercent!! / 100.0).coerceIn(0.0, 1.0).toFloat()
                val left = (100.0 - tightest.usedPercent).coerceIn(0.0, 100.0)
                val (pillBg, pillFg) = when (usageLevel(usedFraction)) {
                    UsageLevel.CRITICAL -> scheme.errorContainer to scheme.onErrorContainer
                    UsageLevel.WARNING -> scheme.warningContainer to scheme.onWarningContainer
                    UsageLevel.NORMAL -> scheme.secondaryContainer.copy(alpha = 0.7f) to scheme.onSecondaryContainer
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = pillBg,
                    modifier = modifier,
                ) {
                    RollingNumber(
                        text = "剩 ${left.toInt()}%",
                        style = CardNumberStyle.copy(fontSize = 16.sp),
                        color = pillFg,
                        reduceMotion = reduceMotion,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            } else {
                Text(
                    "无数据",
                    fontSize = Type.CaptionSize.sp,
                    color = scheme.outline,
                    modifier = modifier,
                )
            }
        }
    }
}
