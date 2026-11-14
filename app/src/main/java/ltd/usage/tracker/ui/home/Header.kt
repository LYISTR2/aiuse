package ltd.usage.tracker.ui.home

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.ui.SortMode
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.Type

// ---------------------------------------------------------------- 头部

@Composable
internal fun Header(
    refreshing: Boolean,
    dark: Boolean,
    sortMode: SortMode,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onToggleTheme: () -> Unit,
    onSortModeChange: (SortMode) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 7.dp, top = 18.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标按钮已是 48dp 触控区，相邻按钮之间不再加间距；右内边距 7dp 让最右图标的位置与旧版一致
        Text(
            "用量",
            fontSize = Type.HeadlineSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.TextPrimary,
            letterSpacing = (-0.3).sp,
        )
        Spacer(Modifier.weight(1f))
        // 排序入口：非手动模式时图标加重，让「当前不是自定义顺序」一眼可见
        Box {
            IconAction(
                Icons.Outlined.SwapVert,
                "排序：${sortMode.label}",
                false,
                { sortMenu = true },
                emphasized = sortMode != SortMode.MANUAL,
            )
            DropdownMenu(
                expanded = sortMenu,
                onDismissRequest = { sortMenu = false },
                containerColor = Palette.SurfaceHover,
            ) {
                SortMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                mode.label,
                                fontSize = Type.BodySize.sp,
                                color = if (mode == sortMode) Palette.TextPrimary else Palette.TextSecondary,
                                fontWeight = if (mode == sortMode) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = {
                            if (mode == sortMode) {
                                Icon(
                                    Icons.Outlined.Check,
                                    null,
                                    tint = Palette.TextPrimary,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        },
                        onClick = {
                            onSortModeChange(mode)
                            sortMenu = false
                        },
                    )
                }
            }
        }
        IconAction(
            if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            if (dark) "切换浅色" else "切换深色",
            false,
            onToggleTheme,
        )
        IconAction(Icons.Outlined.Refresh, "刷新", refreshing, onRefresh)
        IconAction(Icons.Outlined.Add, "添加", false, onAdd)
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    label: String,
    spinning: Boolean,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "angle",
    )
    Box(
        modifier = Modifier
            // 48dp 是 Material 最小触控尺寸；图标仍 19dp，视觉不变
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = !spinning, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (emphasized) Palette.TextPrimary else Palette.TextSecondary,
            modifier = Modifier
                .size(19.dp)
                .rotate(if (spinning) angle else 0f),
        )
    }
}
