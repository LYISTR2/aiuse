package ltd.usage.tracker.ui.home
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ltd.usage.tracker.data.StoredAccount

/**
 * 长按拖动排序的全部状态（1.5.2 修好的逻辑原样搬入，只是从 ServicesContent 的一堆 var 收拢到这里）。
 *
 * 要点：
 *  - 视觉位置按「手指绝对位置」算，而不是累加位移再手动补偿：
 *    换位后 LazyColumn 要到下一帧才重新布局，手动补偿会有一帧「新位移配旧槽位」，被拖行闪一个行高。
 *    记住拿起时该行的顶边（视口坐标）和手指累计位移，每帧减去该行当前槽位，布局哪一帧更新都不影响视觉位置。
 *  - 松手后的回弹单独用 [settlingId] 标记：draggingId 一清，位移若跟着归零就是「啪」一下。
 *  - 手势协程只按 account.id 建一次，换位后不会重启；必须经由 [ordered] / [onMove] 这两个 State 读最新值，
 *    否则 onDrag 里拿到的一直是拿起时那份 ordered，from 会错，连续误换、来回横跳。
 */
@Stable
internal class ReorderState(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val ordered: State<List<StoredAccount>>,
    private val onMove: State<(Int, Int) -> Unit>,
) {
    /** 拖动中的账号 id；只在手动模式下有值。 */
    var draggingId by mutableStateOf<String?>(null)
        private set

    /** 松手回弹中的账号 id。 */
    var settlingId by mutableStateOf<String?>(null)
        private set

    private var dragStartOffset by mutableFloatStateOf(0f)
    private var dragTotal by mutableFloatStateOf(0f)
    private var settleOffset by mutableFloatStateOf(0f)
    private var settleJob: Job? = null

    /** 任何一行在拖动或回弹时为 true；此时左滑和 TalkBack 删除都要关掉。 */
    val isActive: Boolean get() = draggingId != null || settlingId != null

    /** 这一行是否脱离了槽位（拖动或回弹中）：要压在其他行上面，且不叠 placement 动画。 */
    fun isLifted(id: String): Boolean = draggingId == id || settlingId == id

    /**
     * 这一行此刻应有的额外位移。只在 graphicsLayer 的 lambda 里调用：
     * 所有状态都在绘制阶段读取，位移变化只触发重绘，不触发重组和重新布局。
     */
    fun translationOf(id: String): Float = when (id) {
        draggingId -> dragVisualOffset(id) ?: 0f
        settlingId -> settleOffset
        else -> 0f
    }

    // 被拖行此刻相对自身槽位应有的位移；槽位不在可见范围时返回 null
    private fun dragVisualOffset(id: String): Float? =
        listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == id }
            ?.let { dragStartOffset + dragTotal - it.offset }

    internal fun start(id: String) {
        // 上一行还在回弹就直接收尾，同一时刻只有一行脱离槽位
        settleJob?.cancel()
        settlingId = null
        settleOffset = 0f
        val mine = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
        dragStartOffset = mine?.offset?.toFloat() ?: 0f
        dragTotal = 0f
        draggingId = id
    }

    /**
     * 手指移动 [dy]。[pendingOrdered] 是上次发出换位时的那份列表：
     * 已发出 onMove 但重组还没跟上时，ordered 与 layoutInfo 都是旧的，再判定一次会对同一对 from/to 重复换位；
     * 等 ordered 换了新实例再继续。返回值是新的 pendingOrdered。
     */
    internal fun drag(id: String, dy: Float, pendingOrdered: List<StoredAccount>?): List<StoredAccount>? {
        dragTotal += dy
        val list = ordered.value
        if (pendingOrdered != null && list === pendingOrdered) return pendingOrdered
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return null
        val info = listState.layoutInfo
        val mine = info.visibleItemsInfo.firstOrNull { it.key == id } ?: return null
        // 汇总行占了 lazy 的第 0 项，账号 index 要 +1；
        // 布局还没跟上 ordered 时两者对不上，这一帧先不判定
        if (mine.index != from + 1) return null
        val center = dragStartOffset + dragTotal + mine.size / 2f
        val over = info.visibleItemsInfo.firstOrNull {
            it.key != id && center >= it.offset && center <= it.offset + it.size
        } ?: return null
        val to = over.index - 1
        if (to in list.indices && to != from) {
            // 换位涉及首个可见项时，LazyColumn 会按 key 锚定滚动位置，
            // 整列跟着被拖行挪一个行高；按 index 钉住当前滚动位置避免这种跳动
            val first = listState.firstVisibleItemIndex
            if (first == mine.index || first == over.index) {
                listState.requestScrollToItem(first, listState.firstVisibleItemScrollOffset)
            }
            onMove.value(from, to)
            return list
        }
        return null
    }

    /** 正常松手和手势被取消都走这里：从当前视觉位置弹回槽位，而不是瞬间归零。 */
    internal fun release(id: String) {
        val from = dragVisualOffset(id) ?: 0f
        settleJob?.cancel()
        // 同一帧内切换：settleOffset 先接住当前视觉位移，再清 draggingId，画面不跳
        settleOffset = from
        settlingId = id
        draggingId = null
        settleJob = scope.launch {
            try {
                if (from != 0f) {
                    animate(from, 0f, animationSpec = spring(
                        dampingRatio = 0.8f,
                        stiffness = 700f,
                    )) { value, _ -> settleOffset = value }
                }
            } finally {
                // 被新的拖动打断时 settlingId 已经换人，不能误清
                if (settlingId == id) {
                    settlingId = null
                    settleOffset = 0f
                }
            }
        }
    }
}

@Composable
internal fun rememberReorderState(
    ordered: List<StoredAccount>,
    onMove: (Int, Int) -> Unit,
): ReorderState {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val latestOrdered = rememberUpdatedState(ordered)
    val latestOnMove = rememberUpdatedState(onMove)
    return remember(listState, scope) { ReorderState(listState, scope, latestOrdered, latestOnMove) }
}

/**
 * 给一行接上长按拖动手势。
 * key 只留 [id]：带上 ordered.size 时列表长度一变（如刷新）就会重建手势，拖到一半被打断。
 */
internal fun Modifier.reorderable(state: ReorderState, id: String): Modifier = pointerInput(id) {
    var pendingOrdered: List<StoredAccount>? = null
    detectDragGesturesAfterLongPress(
        onDragStart = {
            pendingOrdered = null
            state.start(id)
        },
        onDrag = { change, amount ->
            change.consume()
            pendingOrdered = state.drag(id, amount.y, pendingOrdered)
        },
        onDragEnd = { state.release(id) },
        onDragCancel = { state.release(id) },
    )
}
