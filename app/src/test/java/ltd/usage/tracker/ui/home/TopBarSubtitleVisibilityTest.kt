package ltd.usage.tracker.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarSubtitleVisibilityTest {

    @Test
    fun hiddenWhenExpandedAndSummaryCardVisible() {
        assertFalse(shouldShowTopBarSubtitle(collapsedFraction = 0f, summaryCardVisible = true))
        assertFalse(shouldShowTopBarSubtitle(collapsedFraction = 0.49f, summaryCardVisible = true))
    }

    @Test
    fun shownWhenBarSufficientlyCollapsed() {
        assertTrue(shouldShowTopBarSubtitle(collapsedFraction = 0.5f, summaryCardVisible = true))
        assertTrue(shouldShowTopBarSubtitle(collapsedFraction = 1f, summaryCardVisible = true))
    }

    @Test
    fun shownWhenSummaryCardScrolledOffEvenIfBarStillExpanded() {
        // 汇总卡已滚出时，即使顶栏尚未收起也要露出总额，避免完全不可见
        assertTrue(shouldShowTopBarSubtitle(collapsedFraction = 0f, summaryCardVisible = false))
        assertTrue(shouldShowTopBarSubtitle(collapsedFraction = 0.3f, summaryCardVisible = false))
    }
}
