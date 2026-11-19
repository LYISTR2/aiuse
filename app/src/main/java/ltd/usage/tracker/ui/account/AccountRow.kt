package ltd.usage.tracker.ui.account

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import ltd.usage.tracker.ui.theme.NumberStyle
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.RollingNumber
import ltd.usage.tracker.ui.theme.Type
import ltd.usage.tracker.ui.theme.providerLetter
import ltd.usage.tracker.ui.theme.providerPolygon
import ltd.usage.tracker.ui.theme.rememberReduceMotion

/**
 * 账号只有一个指标、并且右上角显示的正是它时返回该指标。
 * 右上角规则见 RowTrailing：有总余额显示余额，否则显示已用最高的百分比。
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
 * 账号行：分组列表卡片。首尾 24dp 圆角，中间 6dp，项间隔 2dp。
 * 按压把 24dp 收到 12dp，不再用缩放。被拿起时变成 28dp 全圆角，tonal elevation +2。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
) {
    val provider = Provider.from(account.providerId)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = rememberReduceMotion()
    val largeCorner by animateDpAsState(
        targetValue = if (pressed && !lifted && !reduceMotion) 12.dp else 24.dp,
        animationSpec = Motion.spatialFast(),
        label = "pressCorner",
    )
    val shape = groupItemShape(index, count, large = largeCorner, lifted = lifted)
    val scheme = MaterialTheme.colorScheme
    val containerColor = if (lifted) scheme.surfaceContainerHigh else scheme.surfaceContainer
    val bg by animateColorAsState(
        targetValue = containerColor,
        animationSpec = Motion.effectsFast(),
        label = "dragBg",
    )
    val elevation = if (lifted) 2.dp else 0.dp

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

    val bottomGap = if (index == count - 1) 0.dp else 2.dp
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
                            modifier = Modifier.size(20.dp),
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
                    .clickable(interactionSource = interaction, indication = null) {},
                shape = shape,
                color = bg,
                tonalElevation = elevation,
                shadowElevation = 0.dp,
            ) {
                AccountRowContent(
                    account = account,
                    providerName = provider?.displayName ?: account.providerId,
                    snapshot = snapshot,
                    displayCurrency = displayCurrency,
                    refreshing = refreshing,
                    reduceMotion = reduceMotion,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProviderAvatar(providerId: String, accountName: String, providerName: String) {
    val scheme = MaterialTheme.colorScheme
    val shape = providerPolygon(providerId).toShape()
    Box(
        Modifier
            .size(40.dp)
            .clip(shape)
            .background(scheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            providerLetter(accountName, providerName),
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = scheme.onSecondaryContainer,
        )
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
) {
    val failed = snapshot?.error != null
    val headline = singleHeadlineMetric(snapshot)
    val headlineCaption = headline?.let { metricCaption(it) }
    val scheme = MaterialTheme.colorScheme

    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProviderAvatar(account.providerId, account.name, providerName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    account.name,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val providerLabel = providerLabelOrNull(account.name, providerName)
                val detail = snapshot?.error?.let { humanizeError(it) }
                    ?: snapshot?.note?.let { humanizeNote(it) }
                        ?.takeUnless { headlineCaption != null && isSameText(it, headlineCaption) }
                if (providerLabel != null || detail != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildAnnotatedString {
                            providerLabel?.let { append(it) }
                            if (providerLabel != null && detail != null) append(" · ")
                            if (detail != null) {
                                if (failed) withStyle(SpanStyle(color = scheme.error)) { append(detail) }
                                else append(detail)
                            }
                        },
                        fontSize = Type.LabelSize.sp,
                        color = scheme.outline,
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
            Spacer(Modifier.height(8.dp))
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
    when {
        snapshot?.error != null -> Text(
            "—",
            style = CardNumberStyle,
            color = MaterialTheme.colorScheme.outline,
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
                    color = MaterialTheme.colorScheme.onSurface,
                    reduceMotion = reduceMotion,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    displayCurrency,
                    fontSize = Type.MicroSize.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        else -> {
            val tightest = snapshot?.metrics.orEmpty()
                .filter { it.usedPercent != null }
                .maxByOrNull { it.usedPercent!! }
            if (tightest != null) {
                val left = (100.0 - tightest.usedPercent!!).coerceIn(0.0, 100.0)
                RollingNumber(
                    text = "剩 ${left.toInt()}%",
                    style = CardNumberStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    reduceMotion = reduceMotion,
                    modifier = modifier,
                )
            } else {
                Text(
                    "无数据",
                    fontSize = Type.CaptionSize.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = modifier,
                )
            }
        }
    }
}
