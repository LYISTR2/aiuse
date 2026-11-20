package ltd.usage.tracker.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class SummaryCaptionTest {

    @Test
    fun defaultCaptionIsServiceCount() {
        assertEquals("3 个服务", summaryCaption(count = 3))
    }

    @Test
    fun captionIncludesUnconvertibleAndFlags() {
        assertEquals(
            "2 个服务 · 未能换算 EUR/JPY · 含按比例计费的额度 · 离线汇率",
            summaryCaption(
                count = 2,
                otherCurrencies = setOf("JPY", "EUR"),
                hasPercentOnly = true,
                fxLive = false,
            ),
        )
    }

    @Test
    fun failedAllCaption() {
        assertEquals(
            "2 个服务 · 全部获取失败",
            summaryCaption(count = 2, failedAll = true, hasPercentOnly = true, fxLive = false),
        )
    }
}
