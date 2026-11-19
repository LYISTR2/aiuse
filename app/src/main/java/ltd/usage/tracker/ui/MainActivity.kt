package ltd.usage.tracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.ui.add.AddAccountSheet
import ltd.usage.tracker.ui.home.UsageHome
import ltd.usage.tracker.ui.login.SubscriptionLoginDialog
import ltd.usage.tracker.ui.login.XaiLoginDialog
import ltd.usage.tracker.ui.theme.UsageTrackerTheme
import ltd.usage.tracker.ui.theme.supportsDynamicColor

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsState()
            UsageTrackerTheme(dark = state.dark, dynamicColor = state.dynamicColor) {
                Surface(Modifier.fillMaxSize()) {
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
    val deletes = rememberUndoableDeletes(snackbar) { id -> vm.removeAccount(id) }
    LaunchedEffect(state.accounts) { deletes.prune(state.accounts) }
    val visibleState = deletes.visible(state)
    val dynamicColorAvailable = LocalContext.current.supportsDynamicColor()

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    UsageHome(
        state = visibleState,
        latestFor = { id -> vm.latestFor(id) },
        onRefresh = { vm.refreshAll() },
        onAdd = { showAdd = true },
        onToggleTheme = { vm.setDark(!state.dark) },
        onSortModeChange = { vm.setSortMode(it) },
        onDynamicColorChange = { vm.setDynamicColor(it) },
        onDelete = { id -> deletes.request(id, state.accounts) },
        onCurrencyChange = { vm.setDisplayCurrency(it) },
        onMove = { from, to ->
            val shown = visibleState.accounts
            if (shown.size == state.accounts.size) {
                vm.moveAccount(from, to)
            } else if (from in shown.indices && to in shown.indices) {
                val realFrom = state.accounts.indexOfFirst { it.id == shown[from].id }
                val realTo = state.accounts.indexOfFirst { it.id == shown[to].id }
                vm.moveAccount(realFrom, realTo)
            }
        },
        snackbar = snackbar,
        dynamicColorAvailable = dynamicColorAvailable,
    )

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
