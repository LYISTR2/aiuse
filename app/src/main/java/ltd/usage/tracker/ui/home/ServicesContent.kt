package ltd.usage.tracker.ui.home

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.fx.Fx
import ltd.usage.tracker.ui.SortMode
import ltd.usage.tracker.ui.UiState
import ltd.usage.tracker.ui.account.AccountRow
import ltd.usage.tracker.ui.format.formatTime
import ltd.usage.tracker.ui.sortAccounts
import ltd.usage.tracker.ui.theme.Motion
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.Type

// ---------------------------------------------------------------- 服务列表

@Composable
internal fun ServicesContent(
    state: UiState,
    latestFor: (String) -> UsageSnapshot?,
    onDelete: (String) -> Unit,
    onCurrencyChange: (String) -> Unit = {},
    onMove: (Int, Int) -> Unit = { _, _ -> },
    onAdd: () -> Unit = {},
) {
    if (state.accounts.isEmpty()) {
        EmptyState(onAdd = onAdd)
        return
    }

    // 展示顺序：手动模式用存储顺序，其余按规则重排（不改存储）
    val ordered = remember(state.accounts, state.sortMode, state.displayCurrency, state.snapshots) {
        sortAccounts(state.accounts, state.sortMode, state.displayCurrency, latestFor)
    }
    val manual = state.sortMode == SortMode.MANUAL

    // 汇总：全部换算到展示币种后累加。
    // 换算经 USD 中转，汇率由 Fx 提供；拿不到实时汇率时用缓存/兜底值，
    // 不阻断展示，但会在副标题标注「离线汇率」。
    val target = state.displayCurrency
    var converted = 0L
    var convertedAny = false
    val unconvertible = mutableSetOf<String>()

    state.accounts.forEach { acc ->
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
    val total = if (convertedAny) converted else null

    // 有账号只有百分比额度、没有金额时，汇总金额不能代表全部，需提示
    val hasPercentOnly = state.accounts.any { acc ->
        val snap = latestFor(acc.id)?.takeIf { it.error == null } ?: return@any false
        snap.balanceMinor == null && snap.metrics.any { it.usedPercent != null }
    }

    val reorder = rememberReorderState(ordered, onMove)

    LazyColumn(
        state = reorder.listState,
        contentPadding = PaddingValues(bottom = 12.dp),
    ) {
        item {
            SummaryRow(
                total = total,
                currency = target,
                count = state.accounts.size,
                otherCurrencies = unconvertible,
                hasPercentOnly = hasPercentOnly,
                fxLive = state.fxLive,
                onCurrencyChange = onCurrencyChange,
            )
        }
        itemsIndexed(ordered, key = { _, a -> a.id }) { index, account ->
            // 入场动画：原来的 AnimatedVisibility 会多包一层布局容器，拖动重组时可能多一帧延迟。
            // animateItem 的 fadeInSpec 只对「列表已存在后新插入」的项生效，首屏那批不会淡入，
            // 所以首屏的错峰淡入 + 上滑用一次性的进度值在 graphicsLayer 里画，不参与布局。
            // rememberSaveable 让滑出屏幕再滑回来的行不重播入场。
            var entered by rememberSaveable { mutableStateOf(false) }
            val enter = remember { Animatable(if (entered) 1f else 0f) }
            LaunchedEffect(Unit) {
                if (!entered) {
                    delay(index * 30L)
                    enter.animateTo(1f, Motion.spatialFast())
                    entered = true
                }
            }
            val dragging = reorder.draggingId == account.id
            val lifted = reorder.isLifted(account.id)
            Box(
                Modifier
                    // 拖起来的行（含回弹中）要压在其他行上面，否则会被相邻行遮住
                    .zIndex(if (lifted) 1f else 0f)
                    // 被拖的行跟手指走，不能再叠位移动画，否则两者互相打架会抖；
                    // 回弹期间也只关这一行，其余行照常用弹簧让位，形成「互相挤压」的效果
                    .animateItem(
                        fadeInSpec = Motion.effectsFast(),
                        fadeOutSpec = null,
                        placementSpec = if (lifted) null else spring(
                            dampingRatio = 0.75f,
                            stiffness = 900f,
                        ),
                    )
                    // 所有状态都在 lambda 内读取：位移变化只触发重绘，不触发重组和重新布局
                    .graphicsLayer {
                        val p = enter.value
                        alpha = p.coerceIn(0f, 1f)
                        translationY = (1f - p) * size.height / 4f + reorder.translationOf(account.id)
                    }
                    .then(if (manual) Modifier.reorderable(reorder, account.id) else Modifier),
            ) {
                AccountRow(
                    account = account,
                    snapshot = latestFor(account.id),
                    displayCurrency = target,
                    onDelete = { onDelete(account.id) },
                    dragging = dragging,
                    // 任何一行在拖动或回弹时都关掉左滑，避免横竖两个手势同时生效
                    swipeEnabled = !reorder.isActive,
                )
            }
        }
        item {
            val last = state.snapshots.maxOfOrNull { it.capturedAt }
            if (last != null) {
                Text(
                    "更新于 ${formatTime(last)}",
                    fontSize = Type.CaptionSize.sp,
                    color = Palette.TextTertiary,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp),
                )
            }
        }
    }
}
