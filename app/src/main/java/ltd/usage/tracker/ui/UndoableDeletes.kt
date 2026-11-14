package ltd.usage.tracker.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ltd.usage.tracker.data.StoredAccount

/**
 * 删除的撤销：在 UI 层延迟提交。
 *
 * removeAccount 会连同加密凭据一起删掉，删了再恢复会丢 Key；所以先只在界面上隐藏，
 * 4 秒内没点撤销才真正调用 [commit]。ViewModel 的 moveAccount / sortAccounts 完全不动。
 * 左滑删除和 TalkBack 的「删除」自定义操作都走 [request]，行为完全一致。
 */
@Stable
internal class UndoableDeletes(
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val commit: State<(String) -> Unit>,
) {
    /** 已隐藏的 id：撤销窗口内的，以及已提交但 reload 还没回来的（避免闪回一帧）。 */
    var pending by mutableStateOf<Set<String>>(emptySet())
        private set

    fun visible(state: UiState): UiState =
        if (pending.isEmpty()) state
        else state.copy(accounts = state.accounts.filterNot { it.id in pending })

    /** 账号真正从存储消失后，把它从隐藏集合里清掉。 */
    fun prune(accounts: List<StoredAccount>) {
        val alive = accounts.map { it.id }.toSet()
        if (pending.any { it !in alive }) pending = pending.filter { it in alive }.toSet()
    }

    fun request(id: String, accounts: List<StoredAccount>) {
        if (id in pending) return
        val name = accounts.firstOrNull { it.id == id }?.name ?: return
        pending = pending + id
        // 连续删除时，新提示顶掉旧提示：旧的按「未撤销」处理，立即提交
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            // 用 Indefinite + 自己计时，保证恰好 4 秒；Short 在无障碍模式下会被系统延长
            val result = withTimeoutOrNull(UNDO_WINDOW_MS) {
                snackbar.showSnackbar(
                    message = "已删除 $name",
                    actionLabel = "撤销",
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == SnackbarResult.ActionPerformed) {
                pending = pending - id
            } else {
                commit.value(id)
            }
        }
    }

    companion object {
        const val UNDO_WINDOW_MS = 4_000L
    }
}

@Composable
internal fun rememberUndoableDeletes(
    snackbar: SnackbarHostState,
    commit: (String) -> Unit,
): UndoableDeletes {
    val scope = rememberCoroutineScope()
    val latestCommit = rememberUpdatedState(commit)
    return remember(snackbar) { UndoableDeletes(snackbar, scope, latestCommit) }
}
