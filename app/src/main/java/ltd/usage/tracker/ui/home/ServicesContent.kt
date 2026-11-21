package ltd.usage.tracker.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.ui.SortMode
import ltd.usage.tracker.ui.UiState
import ltd.usage.tracker.ui.account.AccountRow
import ltd.usage.tracker.ui.format.formatTime
import ltd.usage.tracker.ui.sortAccounts
import ltd.usage.tracker.ui.theme.Motion
import ltd.usage.tracker.ui.theme.Type

@Composable
internal fun ServicesContent(
    state: UiState,
    latestFor: (String) -> UsageSnapshot?,
    onDelete: (String) -> Unit,
    onCurrencyChange: (String) -> Unit = {},
    onMove: (Int, Int) -> Unit = { _, _ -> },
    onAdd: () -> Unit = {},
    onRelogin: (StoredAccount) -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(bottom = 12.dp),
    refreshing: Boolean = false,
) {
    if (state.accounts.isEmpty()) {
        EmptyState(onAdd = onAdd)
        return
    }

    val ordered = remember(state.accounts, state.sortMode, state.displayCurrency, state.snapshots) {
        sortAccounts(state.accounts, state.sortMode, state.displayCurrency, latestFor)
    }
    val manual = state.sortMode == SortMode.MANUAL
    val target = state.displayCurrency
    val summary = remember(state.accounts, state.displayCurrency, state.snapshots) {
        computeUsageSummary(state.accounts, target, latestFor)
    }

    val reorder = rememberReorderState(ordered, onMove, listState)
    val haptic = LocalHapticFeedback.current

    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
    ) {
        item {
            SummaryRow(
                total = summary.total,
                currency = target,
                count = state.accounts.size,
                otherCurrencies = summary.unconvertible,
                hasPercentOnly = summary.hasPercentOnly,
                fxLive = state.fxLive,
                onCurrencyChange = onCurrencyChange,
            )
            if (state.credentialsUndecryptable) {
                DecryptErrorCard()
            }
        }
        itemsIndexed(ordered, key = { _, a -> a.id }) { index, account ->
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
                    .zIndex(if (lifted) 1f else 0f)
                    .animateItem(
                        fadeInSpec = Motion.effectsFast(),
                        fadeOutSpec = null,
                        placementSpec = if (lifted) null else spring(
                            dampingRatio = 0.75f,
                            stiffness = 900f,
                        ),
                    )
                    .graphicsLayer {
                        val p = enter.value
                        alpha = p.coerceIn(0f, 1f)
                        translationY = (1f - p) * size.height / 4f + reorder.translationOf(account.id)
                    }
                    .then(
                        if (manual) {
                            Modifier.reorderable(
                                reorder,
                                account.id,
                                onPickUp = {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onPutDown = {
                                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                },
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                AccountRow(
                    account = account,
                    snapshot = latestFor(account.id),
                    displayCurrency = target,
                    onDelete = { onDelete(account.id) },
                    dragging = dragging,
                    lifted = lifted,
                    swipeEnabled = !reorder.isActive,
                    index = index,
                    count = ordered.size,
                    refreshing = refreshing || account.id in state.refreshingIds,
                    needsRelogin = account.id in state.accountsNeedingRelogin,
                    onRelogin = { onRelogin(account) },
                )
            }
        }
        item {
            val last = state.snapshots.maxOfOrNull { it.capturedAt }
            if (last != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "更新于 ${formatTime(last)}",
                        fontSize = Type.CaptionSize.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}
