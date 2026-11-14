package ltd.usage.tracker.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.ui.home.ServicesContent
import ltd.usage.tracker.ui.theme.UsageTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 长按拖动排序的回归测试（1.7.0 把拖动状态收进 ReorderState 后加的护栏）：
 * 慢速逐行拖动时每越过一行只换位一次，不会对同一对 from/to 重复换位，也不会来回横跳。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReorderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = 1_763_100_000_000L

    @Test
    fun dragDownTwoRowsMovesStepByStep() {
        var accounts by mutableStateOf(
            listOf("甲", "乙", "丙", "丁").mapIndexed { i, n ->
                StoredAccount("a$i", "openai", "账号$n", now, now)
            }
        )
        val moves = mutableListOf<Pair<Int, Int>>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageTrackerTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    ServicesContent(
                        state = UiState(accounts = accounts),
                        latestFor = { null },
                        onDelete = {},
                        onMove = { f, t ->
                            moves += f to t
                            accounts = accounts.toMutableList().apply { add(t, removeAt(f)) }
                        },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)

        val first = compose.onNodeWithText("账号甲").getBoundsInRoot()
        val second = compose.onNodeWithText("账号乙").getBoundsInRoot()
        val d = compose.activity.resources.displayMetrics.density
        val rowPx = (second.top.value - first.top.value) * d

        compose.onNodeWithText("账号甲").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        // 慢速下移约 2.2 行，每步 1/10 行，每步之间推进一帧让布局跟上
        repeat(22) {
            compose.onNodeWithText("账号甲").performTouchInput { moveBy(Offset(0f, rowPx / 10f)) }
            compose.mainClock.advanceTimeBy(16)
        }
        compose.onNodeWithText("账号甲").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)

        assertEquals(listOf(0 to 1, 1 to 2), moves)
        assertEquals(listOf("账号乙", "账号丙", "账号甲", "账号丁"), accounts.map { it.name })
    }
}
