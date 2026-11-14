package ltd.usage.tracker.ui.home

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.ui.format.formatMoney
import ltd.usage.tracker.ui.theme.NumberStyle
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.Type

/** 汇总：一行，不占大块空间。 */
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 18.dp),
    ) {
        if (total != null) {
            // 大数字与币种切换按基线对齐：两者字号差 2.5 倍，底边对齐会让小字「坐」在下沉部上
            Row {
                Text(
                    formatMoney(total),
                    fontSize = Type.DisplaySize.sp,
                    fontWeight = FontWeight.Bold,
                    color = Palette.TextPrimary,
                    letterSpacing = (-0.8).sp,
                    style = NumberStyle,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(6.dp))
                CurrencyToggle(
                    current = currency,
                    onChange = onCurrencyChange,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(2.dp))
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
                fontSize = Type.CaptionSize.sp,
                color = Palette.TextTertiary,
            )
        } else {
            // 全部失败时不用大号破折号（会渲染成一根粗横条），改用弱化文字
            Text(
                "暂无数据",
                fontSize = Type.PlaceholderSize.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.TextTertiary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$count 个服务 · 全部获取失败",
                fontSize = Type.CaptionSize.sp,
                color = Palette.TextTertiary,
            )
        }
    }
}

/**
 * 币种切换：USD / CNY 两段，点一下切换。
 *
 * 用文字而不是图标 —— 币种是文字概念，图标表达不了。
 * 选中项用主文字色 + 半粗，未选中用三级灰，靠字重和颜色区分，不画胶囊背景。
 *
 * 文字只有 ~25dp 宽，直接点很难点中。点击层与文字分开：文字按原样排版（外观不变），
 * 每个选项另有一个透明点击层，用 minimumInteractiveComponentSize 撑到 48dp、以文字为中心叠放。
 * 外层高度取 max(文字, 48dp) 让纵向点击区落在布局内；横向点击层会略超出文字左右，不影响相邻排版。
 */
@Composable
private fun CurrencyToggle(
    current: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf("USD", "CNY")
    Layout(
        modifier = modifier,
        content = {
            // 0..n-1：可见文字（不可点，语义交给点击层）
            options.forEach { c ->
                val selected = c == current
                Text(
                    c,
                    fontSize = Type.CaptionSize.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Palette.TextPrimary else Palette.TextTertiary,
                    modifier = Modifier
                        .clearAndSetSemantics {}
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            // n：分隔符
            Text(
                "/",
                fontSize = Type.MicroSize.sp,
                color = Palette.TextTertiary,
                modifier = Modifier
                    .clearAndSetSemantics {}
                    .padding(horizontal = 3.dp),
            )
            // n+1..：透明点击层。clickable 必须在 minimumInteractiveComponentSize 外侧，点击区才是 48dp
            options.forEach { c ->
                Box(
                    Modifier
                        .semantics {
                            contentDescription = "金额显示为 $c"
                            selected = c == current
                        }
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) { onChange(c) }
                        .minimumInteractiveComponentSize()
                )
            }
        },
    ) { measurables, constraints ->
        val n = options.size
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val texts = (0 until n).map { measurables[it].measure(loose) }
        val sep = measurables[n].measure(loose)
        val textH = texts.maxOf { it.height }
        val h = maxOf(textH, 48.dp.roundToPx())
        // 横向依次排：文字0 / 分隔 / 文字1 …，与旧版 Row 完全一致
        val xs = IntArray(n)
        var x = 0
        texts.forEachIndexed { i, p ->
            if (i > 0) x += sep.width
            xs[i] = x
            x += p.width
        }
        // 点击层按文字尺寸约束测量，minimumInteractiveComponentSize 会自行扩到 48dp 并居中于文字
        val hits = (0 until n).map { i ->
            measurables[n + 1 + i].measure(Constraints.fixed(texts[i].width, textH))
        }
        val textTop = (h - textH) / 2
        layout(x, h) {
            texts.forEachIndexed { i, p -> p.placeRelative(xs[i], textTop + (textH - p.height) / 2) }
            for (i in 1 until n) {
                sep.placeRelative(xs[i] - sep.width, textTop + (textH - sep.height) / 2)
            }
            hits.forEachIndexed { i, p -> p.placeRelative(xs[i], textTop) }
        }
    }
}
