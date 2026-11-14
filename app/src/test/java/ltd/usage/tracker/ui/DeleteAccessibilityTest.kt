package ltd.usage.tracker.ui

import ltd.usage.tracker.ui.home.ServicesContent
import ltd.usage.tracker.ui.account.singleHeadlineMetric
import ltd.usage.tracker.ui.format.metricCaption
import ltd.usage.tracker.ui.format.isSameText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.ui.theme.UsageTrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TalkBack「删除」自定义操作：必须存在、与左滑走同一套延迟提交 + 4 秒撤销，拖动中不提供。
 * 另含单指标去重规则的纯函数测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DeleteAccessibilityTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val now = System.currentTimeMillis()
    private val accounts = listOf(
        StoredAccount("a1", "openai", "账号甲", now, now),
        StoredAccount("a2", "openai", "账号乙", now, now),
    )
    private val committed = mutableListOf<String>()

    private val hasDeleteAction = SemanticsMatcher("有「删除」自定义操作") { node ->
        node.config.getOrElseNullable(SemanticsActions.CustomActions) { null }
            ?.any { it.label == "删除" } == true
    }

    private fun setUp() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageTrackerTheme(dark = true) {
                val snackbar = remember { SnackbarHostState() }
                val deletes = rememberUndoableDeletes(snackbar) { committed += it }
                val state = UiState(accounts = accounts)
                Box(Modifier.fillMaxSize()) {
                    ServicesContent(
                        state = deletes.visible(state),
                        latestFor = { null },
                        onDelete = { id -> deletes.request(id, accounts) },
                        onMove = { _, _ -> },
                    )
                    SnackbarHost(snackbar)
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
    }

    private fun invokeDelete(name: String) {
        val action = compose.onNodeWithText(name).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions].first { it.label == "删除" }
        compose.runOnUiThread { action.action() }
        compose.mainClock.advanceTimeBy(300)
    }

    @Test
    fun customActionExists() {
        setUp()
        compose.onNodeWithText("账号甲").assert(hasDeleteAction)
        compose.onNodeWithText("账号乙").assert(hasDeleteAction)
    }

    @Test
    fun deleteCommitsAfterUndoWindow() {
        setUp()
        invokeDelete("账号甲")
        // 行立即隐藏，Snackbar 出现，但还没真正删除
        compose.onNodeWithText("已删除 账号甲").assertExists()
        compose.onNodeWithText("账号甲").assertDoesNotExist()
        assertTrue(committed.isEmpty())

        compose.mainClock.advanceTimeBy(UndoableDeletes.UNDO_WINDOW_MS)
        assertEquals(listOf("a1"), committed)
        compose.onNodeWithText("已删除 账号甲").assertDoesNotExist()
    }

    @Test
    fun undoRestoresRow() {
        setUp()
        invokeDelete("账号甲")
        compose.onNodeWithText("撤销").performClick()
        compose.mainClock.advanceTimeBy(UndoableDeletes.UNDO_WINDOW_MS + 1_000)
        assertTrue(committed.isEmpty())
        compose.onNodeWithText("账号甲").assertExists()
    }

    @Test
    fun noDeleteActionWhileDragging() {
        setUp()
        val node = compose.onNodeWithText("账号乙")
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(800)
        node.performTouchInput { moveBy(Offset(0f, 20f)) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("账号甲").assert(!hasDeleteAction)
        compose.onNodeWithText("账号乙").assert(!hasDeleteAction)
        node.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithText("账号甲").assert(hasDeleteAction)
    }

    // ---- 单指标去重规则

    @Test
    fun singleMetricRules() {
        val claude = UsageSnapshot(
            "c", now, note = "5h 窗口已用 42%",
            metrics = listOf(Metric("5h 窗口", usedPercent = 42.0, note = "已用 42%")),
        )
        val m = singleHeadlineMetric(claude)!!
        assertEquals("5h 窗口 · 已用 42%", metricCaption(m))
        assertTrue(isSameText("5h 窗口已用 42%", metricCaption(m)))

        val grok = Metric("周额度", usedPercent = 97.0, note = "已用 97% · 08-15 重置")
        assertEquals("周额度 · 已用 97% · 08-15 重置", metricCaption(grok))

        // 两个指标：维持现状
        val mistral = UsageSnapshot(
            "m", now, balanceMinor = 24_000, currency = "EUR",
            metrics = listOf(
                Metric("API", balanceMinor = 19_500, currency = "EUR", usedPercent = 20.0),
                Metric("Vibe", balanceMinor = 4_500, currency = "EUR", usedPercent = 85.0),
            ),
        )
        assertNull(singleHeadlineMetric(mistral))

        // 单指标但右上角显示的是总余额而不是它：不去重
        val other = UsageSnapshot(
            "o", now, balanceMinor = 10_000, currency = "USD",
            metrics = listOf(Metric("赠金", balanceMinor = 500, currency = "USD")),
        )
        assertNull(singleHeadlineMetric(other))
    }
}
