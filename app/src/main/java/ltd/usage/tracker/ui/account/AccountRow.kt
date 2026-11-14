package ltd.usage.tracker.ui.account

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
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
import ltd.usage.tracker.ui.theme.Motion
import ltd.usage.tracker.ui.theme.NumberStyle
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.Type

/** Roboto 的 x-height / em ≈ 0.528，用来把状态点放到小写字母中线上。 */
private const val X_HEIGHT_RATIO = 0.528f

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

/**
 * 账号行：两行紧凑布局，无卡片、无头像色块。
 * 第一行：状态点 · 账号名 · 右侧金额（三者按账号名首行基线对齐）；
 * 第二行：服务商（不重复时）· 状态说明，合成一行次要文字。
 * 左滑删除（只允许从右往左），删除由上层延迟提交以支持撤销。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountRow(
    account: StoredAccount,
    snapshot: UsageSnapshot?,
    displayCurrency: String = "USD",
    onDelete: () -> Unit,
    dragging: Boolean = false,
    swipeEnabled: Boolean = true,
) {
    val provider = Provider.from(account.providerId)
    // 按压缩放跟随真实按压状态：旧版 clickable 里翻转布尔值，松手后会一直停在 0.99
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !dragging) 0.99f else 1f,
        animationSpec = Motion.spatialFast(),
        label = "press",
    )
    // 拖动中给一点抬升感：底色浮起 + 轻微放大，明确「这行正被拿在手上」
    val lift by animateFloatAsState(
        targetValue = if (dragging) 1.01f else 1f,
        animationSpec = Motion.spatialFast(),
        label = "lift",
    )
    // 常态底色必须是不透明的 Bg：左滑时下面露出红底，透明底会让红色透上来
    val bg by animateColorAsState(
        targetValue = if (dragging) Palette.SurfaceHover else Palette.Bg,
        animationSpec = Motion.effectsFast(),
        label = "dragBg",
    )
    // 圆角只在浮起时出现（含落下的过渡），平时用直角，否则左滑时四角会透出红底
    val liftedShape = if (dragging || lift > 1.0005f) RoundedCornerShape(8.dp) else RectangleShape
    val dark = Palette.isDark
    val borderColor = Palette.Divider

    val latestOnDelete = rememberUpdatedState(onDelete)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) latestOnDelete.value()
            value != SwipeToDismissBoxValue.StartToEnd
        },
        positionalThreshold = { total -> total * 0.4f },
    )
    // 撤销后这一行重新进入列表时，rememberSaveable 可能恢复出「已滑走」的状态，必须拉回原位
    LaunchedEffect(Unit) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            dismissState.snapTo(SwipeToDismissBoxValue.Settled)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        // 分割线左缩进 36dp = 行内边距 20 + 状态点 6 + 间距 10，与账号名文字左边对齐
        HorizontalDivider(
            modifier = Modifier.padding(start = 36.dp),
            color = Palette.Divider,
            thickness = 0.5.dp,
        )
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            // 拖动排序进行中（含回弹）禁用左滑，两个手势不抢同一根手指
            gesturesEnabled = swipeEnabled,
            backgroundContent = {
                if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Palette.Negative)
                            .padding(end = 24.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "删除",
                            tint = Palette.OnNegative,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = lift
                        scaleY = lift
                        // 拖动中必须有阴影，否则「浮起来」在深色背景上看不出来；6dp 足够，12dp 太重
                        shadowElevation = if (dragging) 6.dp.toPx() else 0f
                        shape = liftedShape
                        clip = dragging
                    }
                    .background(bg, liftedShape)
                    // 深色下阴影几乎不可见，补一圈细描边勾出浮起的边界
                    .then(
                        if (dragging && dark) Modifier.border(0.5.dp, borderColor, liftedShape)
                        else Modifier
                    )
                    .scale(scale)
                    // TalkBack 用户做不了左滑：提供同名自定义操作，走同一个 onDelete（延迟提交 + 4 秒撤销）；
                    // 与左滑同步，拖动排序进行中不提供
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
                    .clickable(interactionSource = interaction, indication = null) {}
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                AccountRowContent(
                    account = account,
                    providerName = provider?.displayName ?: account.providerId,
                    snapshot = snapshot,
                    displayCurrency = displayCurrency,
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
) {
    val density = LocalDensity.current
    val dotSize = 6.dp
    // 状态点中心 = 账号名首行基线上方半个 x-height；用 alignBy 交给 Row 对齐，不再靠 padding 目测
    val dotToBaseline = with(density) {
        (dotSize.toPx() / 2f + Type.BodySize.sp.toPx() * X_HEIGHT_RATIO / 2f).roundToInt()
    }
    val failed = snapshot?.error != null
    // 只有一个指标且右上角显示的就是它：不再重复画「标签 + 数值」，只留条和条下说明
    val headline = singleHeadlineMetric(snapshot)
    val headlineCaption = headline?.let { metricCaption(it) }

    Row {
        Box(
            Modifier
                .alignBy { dotToBaseline }
                .size(dotSize)
                .clip(CircleShape)
                .background(
                    when {
                        snapshot == null -> Palette.TextTertiary
                        failed -> Palette.Negative
                        else -> Palette.Positive
                    }
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            account.name,
            fontSize = Type.BodySize.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Spacer(Modifier.width(12.dp))
        // 右侧数值与账号名首行同一基线，不再浮在两行中间
        RowTrailing(
            snapshot = snapshot,
            displayCurrency = displayCurrency,
            modifier = Modifier.alignByBaseline(),
        )
    }

    // 文字列左缩进 16dp（点 6 + 间距 10），与账号名左边对齐
    Column(Modifier.padding(start = 16.dp)) {
        val providerLabel = providerLabelOrNull(account.name, providerName)
        val detail = snapshot?.error?.let { humanizeError(it) }
            ?: snapshot?.note?.let { humanizeNote(it) }
                // note 与条下说明重复（如「5h 窗口已用 42%」）时去掉，避免同一句话出现两次
                ?.takeUnless { headlineCaption != null && isSameText(it, headlineCaption) }
        if (providerLabel != null || detail != null) {
            Spacer(Modifier.height(3.dp))
            val negative = Palette.Negative
            Text(
                buildAnnotatedString {
                    providerLabel?.let { append(it) }
                    if (providerLabel != null && detail != null) append(" · ")
                    if (detail != null) {
                        // 错误说明保留红色，服务商部分仍是三级灰
                        if (failed) withStyle(SpanStyle(color = negative)) { append(detail) }
                        else append(detail)
                    }
                },
                fontSize = Type.LabelSize.sp,
                color = Palette.TextTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 多条指标（如 Mistral 的 API / Vibe 两个额度）：逐条展示
        val metrics = snapshot?.metrics.orEmpty()
        if (metrics.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            metrics.forEach { m ->
                MetricRow(m = m, displayCurrency = displayCurrency, compact = m === headline)
            }
        }
    }
}

@Composable
private fun RowTrailing(
    snapshot: UsageSnapshot?,
    displayCurrency: String,
    modifier: Modifier = Modifier,
) {
    when {
        // 失败不在右侧再喊一遍：红点和红色说明已经够了，这里只占位
        snapshot?.error != null -> Text(
            "—",
            fontSize = Type.TitleSize.sp,
            color = Palette.TextTertiary,
            modifier = modifier,
        )
        snapshot?.balanceMinor != null -> {
            val minor = snapshot.balanceMinor
            val cur = snapshot.currency ?: displayCurrency
            val shown = runCatching { Fx.convert(minor, cur, displayCurrency) }.getOrDefault(minor)
            Row(modifier) {
                Text(
                    formatMoney(shown),
                    fontSize = Type.TitleSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.TextPrimary,
                    letterSpacing = (-0.2).sp,
                    style = NumberStyle,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    displayCurrency,
                    fontSize = Type.MicroSize.sp,
                    color = Palette.TextTertiary,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        else -> {
            // 没有金额时，显示最紧张（已用最高）的那一项百分比
            val tightest = snapshot?.metrics.orEmpty()
                .filter { it.usedPercent != null }
                .maxByOrNull { it.usedPercent!! }
            if (tightest != null) {
                val left = (100.0 - tightest.usedPercent!!).coerceIn(0.0, 100.0)
                Text(
                    "剩 ${left.toInt()}%",
                    fontSize = Type.TitleSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.TextPrimary,
                    letterSpacing = (-0.2).sp,
                    style = NumberStyle,
                    modifier = modifier,
                )
            } else {
                Text(
                    "无数据",
                    fontSize = Type.CaptionSize.sp,
                    color = Palette.TextTertiary,
                    modifier = modifier,
                )
            }
        }
    }
}
