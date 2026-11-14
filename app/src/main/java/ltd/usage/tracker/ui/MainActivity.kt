package ltd.usage.tracker.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ltd.usage.tracker.ui.add.AddAccountSheet
import ltd.usage.tracker.ui.home.Header
import ltd.usage.tracker.ui.home.ServicesContent
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.ui.login.SubscriptionLoginDialog
import ltd.usage.tracker.ui.login.XaiLoginDialog
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.UsageTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsState()
            UsageTrackerTheme(dark = state.dark) {
                Surface(Modifier.fillMaxSize(), color = Palette.Bg) {
                    AppScreen(vm = vm, state = state)
                }
            }
        }
    }
}

@Composable
fun AppScreen(vm: MainViewModel = viewModel(), state: UiState = vm.state.collectAsState().value) {
    var showAdd by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // 左滑和 TalkBack「删除」共用同一套延迟提交 + 4 秒撤销，见 [UndoableDeletes]
    val deletes = rememberUndoableDeletes(snackbar) { id -> vm.removeAccount(id) }
    LaunchedEffect(state.accounts) { deletes.prune(state.accounts) }
    val visibleState = deletes.visible(state)

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // SnackbarHost 必须真实渲染，否则错误提示无处可见（曾导致「点击无反应」）
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(
                refreshing = state.refreshing,
                dark = state.dark,
                sortMode = state.sortMode,
                onRefresh = { vm.refreshAll() },
                onAdd = { showAdd = true },
                onToggleTheme = { vm.setDark(!state.dark) },
                onSortModeChange = { vm.setSortMode(it) },
            )

            Box(Modifier.weight(1f)) {
                ServicesContent(
                    state = visibleState,
                    latestFor = { id -> vm.latestFor(id) },
                    onDelete = { id -> deletes.request(id, state.accounts) },
                    onCurrencyChange = { vm.setDisplayCurrency(it) },
                    onMove = { from, to ->
                        // 有待删除的行被隐藏时，界面下标和存储下标不一致，按 id 换算回存储下标
                        val shown = visibleState.accounts
                        if (shown.size == state.accounts.size) {
                            vm.moveAccount(from, to)
                        } else if (from in shown.indices && to in shown.indices) {
                            val realFrom = state.accounts.indexOfFirst { it.id == shown[from].id }
                            val realTo = state.accounts.indexOfFirst { it.id == shown[to].id }
                            vm.moveAccount(realFrom, realTo)
                        }
                    },
                    onAdd = { showAdd = true },
                )
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 72.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = Palette.SurfaceHover,
                contentColor = Palette.TextPrimary,
                actionColor = Palette.TextPrimary,
                shape = RoundedCornerShape(10.dp),
            )
        }
    }

    state.xaiLogin?.let { login ->
        XaiLoginDialog(login = login, onCancel = { vm.cancelXaiLogin() })
    }

    state.oauthLogin?.let { login ->
        SubscriptionLoginDialog(
            login = login,
            onSubmitCode = { vm.submitClaudeCode(it) },
            onCancel = { vm.cancelOAuthLogin() },
        )
    }

    if (showAdd) {
        AddAccountSheet(
            onDismiss = { showAdd = false },
            onConfirm = { p, n, t ->
                vm.addAccount(p, n, t)
                showAdd = false
            },
            onXaiLogin = {
                showAdd = false
                vm.loginXai()
            },
            onSubscriptionLogin = { p ->
                showAdd = false
                if (p == Provider.ANTHROPIC) vm.loginClaude() else vm.loginChatGpt()
            },
        )
    }
}
