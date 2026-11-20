package ltd.usage.tracker.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarSubtitleVisibilityTest {

    @Test
    fun hiddenWhenSummaryCardStillVisibleEvenIfBarCollapsed() {
        // 卡还在屏内时，即使顶栏已经收起也不显示副标题，避免总额重复
        assertFalse(shouldShowTopBarSubtitle(summaryCardVisible = true))
    }

    @Test
    fun shownWhenSummaryCardScrolledOff() {
        // 汇总卡已滚出后再显示，避免总额完全不可见
        assertTrue(shouldShowTopBarSubtitle(summaryCardVisible = false))
    }
}
