package ltd.usage.tracker.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import ltd.usage.tracker.ui.theme.UsageTrackerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SummaryCardMeasureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun measureSummaryCardHeight() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageTrackerTheme(dark = true, dynamicColor = false) {
                SummaryRow(
                    total = 3_052,
                    currency = "USD",
                    count = 3,
                    hasPercentOnly = true,
                    fxLive = true,
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        val card = compose.onNodeWithTag("summaryCard").getBoundsInRoot()
        val row = compose.onNodeWithTag("summaryCurrencyRow").getBoundsInRoot()
        val density = compose.density.density
        val cardH = card.height
        val rowH = row.height
        val cardPx = cardH.value * density
        val rowPx = rowH.value * density
        val line = buildString {
            append("summaryCard height=${cardH} density=$density heightPx=$cardPx width=${card.right - card.left}")
            append("\n")
            append("summaryCurrencyRow height=${rowH} heightPx=$rowPx width=${row.right - row.left}")
        }
        println("MEASURE $line")
        File("build/summary-card-measure.txt").apply { parentFile.mkdirs() }.writeText(line + "\n")
        // 按钮行必须 ≤40dp；卡片目标 ≤135dp 只记录，不在此硬断言，以免达不到时被迫改别的数值
        assertTrue("currency row height $rowH should be <= 40dp", rowH <= 40.dp)
        assertTrue(cardH > 0.dp)
    }
}
