package ltd.usage.tracker.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ltd.usage.tracker.ui.SortMode
import ltd.usage.tracker.ui.theme.Type

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HeaderActions(
    refreshing: Boolean,
    dark: Boolean,
    sortMode: SortMode,
    dynamicColor: Boolean,
    dynamicColorAvailable: Boolean,
    onRefresh: () -> Unit,
    onToggleTheme: () -> Unit,
    onSortModeChange: (SortMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var settingsMenu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme

    Box {
        IconButton(onClick = { sortMenu = true }) {
            Box {
                Icon(
                    Icons.Outlined.SwapVert,
                    contentDescription = "排序：${sortMode.label}",
                    tint = if (sortMode != SortMode.MANUAL) scheme.primary else scheme.onSurfaceVariant,
                )
                if (sortMode != SortMode.MANUAL) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                    )
                }
            }
        }
        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            mode.label,
                            fontSize = Type.BodySize.sp,
                            fontWeight = if (mode == sortMode) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (mode == sortMode) scheme.primary else scheme.onSurface,
                        )
                    },
                    trailingIcon = {
                        if (mode == sortMode) {
                            Icon(Icons.Outlined.Check, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
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

    IconButton(onClick = onToggleTheme) {
        Icon(
            if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            contentDescription = if (dark) "切换浅色" else "切换深色",
            tint = scheme.onSurfaceVariant,
        )
    }

    if (refreshing) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            LoadingIndicator(Modifier.size(22.dp))
        }
    } else {
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = scheme.onSurfaceVariant)
        }
    }

    Box {
        IconButton(onClick = { settingsMenu = true }) {
            Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = scheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = settingsMenu, onDismissRequest = { settingsMenu = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (dynamicColorAvailable) "动态取色" else "动态取色（需 Android 12+）",
                        fontSize = Type.BodySize.sp,
                    )
                },
                trailingIcon = {
                    Switch(
                        checked = dynamicColor && dynamicColorAvailable,
                        onCheckedChange = { if (dynamicColorAvailable) onDynamicColorChange(it) },
                        enabled = dynamicColorAvailable,
                    )
                },
                onClick = { if (dynamicColorAvailable) onDynamicColorChange(!dynamicColor) },
            )
        }
    }
}
