package ltd.usage.tracker.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.ui.SortMode
import ltd.usage.tracker.ui.UiState
import ltd.usage.tracker.ui.format.formatMoney
import ltd.usage.tracker.ui.theme.RollingNumber
import ltd.usage.tracker.ui.theme.rememberReduceMotion

internal fun shouldShowTopBarSubtitle(summaryCardVisible: Boolean): Boolean = !summaryCardVisible

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun UsageHome(
    state: UiState,
    latestFor: (String) -> UsageSnapshot?,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onToggleTheme: () -> Unit,
    onSortModeChange: (SortMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onCurrencyChange: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRelogin: (StoredAccount) -> Unit = {},
    snackbar: SnackbarHostState,
    dynamicColorAvailable: Boolean,
    listState: LazyListState = rememberLazyListState(),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val fabExpanded by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80
        }
    }
    val summary = remember(state.accounts, state.displayCurrency, state.snapshots) {
        computeUsageSummary(state.accounts, state.displayCurrency, latestFor)
    }
    val reduceMotion = rememberReduceMotion()
    val subtitleText = when {
        summary.total != null -> "${formatMoney(summary.total)} ${state.displayCurrency}"
        state.accounts.isEmpty() -> null
        else -> "暂无数据"
    }
    val showSubtitle by remember {
        derivedStateOf { shouldShowTopBarSubtitle(summaryCardVisible = listState.firstVisibleItemIndex == 0) }
    }
    val pullState = rememberPullToRefreshState()
    val scheme = MaterialTheme.colorScheme

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = scheme.background,
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        "用量",
                        fontWeight = FontWeight.Bold,
                    )
                },
                subtitle = subtitleText?.let { text ->
                    {
                        if (showSubtitle) {
                            if (summary.total != null) {
                                RollingNumber(
                                    text = text,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = scheme.onSurfaceVariant,
                                    reduceMotion = reduceMotion,
                                )
                            } else {
                                Text(text)
                            }
                        }
                    }
                },
                expandedHeight = TopAppBarDefaults.LargeFlexibleAppBarWithoutSubtitleExpandedHeight,
                actions = {
                    HeaderActions(
                        refreshing = state.refreshing,
                        dark = state.dark,
                        sortMode = state.sortMode,
                        dynamicColor = state.dynamicColor,
                        dynamicColorAvailable = dynamicColorAvailable,
                        onRefresh = onRefresh,
                        onToggleTheme = onToggleTheme,
                        onSortModeChange = onSortModeChange,
                        onDynamicColorChange = onDynamicColorChange,
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    scrolledContainerColor = scheme.surfaceContainerLow,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("添加服务", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)) },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(22.dp)) },
                onClick = onAdd,
                expanded = fabExpanded || state.accounts.isEmpty(),
                shape = RoundedCornerShape(16.dp),
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbar) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = pullState,
            indicator = {
                if (state.refreshing || pullState.distanceFraction > 0f) {
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp),
                    ) {
                        LoadingIndicator()
                    }
                }
            },
        ) {
            ServicesContent(
                state = state,
                latestFor = latestFor,
                onDelete = onDelete,
                onCurrencyChange = onCurrencyChange,
                onMove = onMove,
                onAdd = onAdd,
                onRelogin = onRelogin,
                listState = listState,
                contentPadding = PaddingValues(bottom = 96.dp),
                refreshing = state.refreshing,
            )
        }
    }
}
