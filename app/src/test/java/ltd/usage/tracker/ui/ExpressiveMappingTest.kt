package ltd.usage.tracker.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.ui.account.groupItemShape
import ltd.usage.tracker.ui.theme.ProviderMark
import ltd.usage.tracker.ui.theme.UsageLevel
import ltd.usage.tracker.ui.theme.providerLetter
import ltd.usage.tracker.ui.theme.providerMark
import ltd.usage.tracker.ui.theme.usageLevel
import org.junit.Assert.assertEquals
import org.junit.Test

class ExpressiveMappingTest {

    @Test
    fun usageLevelThresholds() {
        assertEquals(UsageLevel.NORMAL, usageLevel(0f))
        assertEquals(UsageLevel.NORMAL, usageLevel(0.799f))
        assertEquals(UsageLevel.WARNING, usageLevel(0.80f))
        assertEquals(UsageLevel.WARNING, usageLevel(0.949f))
        assertEquals(UsageLevel.CRITICAL, usageLevel(0.95f))
        assertEquals(UsageLevel.CRITICAL, usageLevel(1f))
    }

    @Test
    fun providerMarkIsStableAndCoversAll() {
        val marks = Provider.entries.associate { it.id to providerMark(it.id) }
        assertEquals(Provider.entries.size, marks.size)
        assertEquals(ProviderMark.COOKIE, providerMark(Provider.DEEPSEEK.id))
        assertEquals(ProviderMark.CLOVER, providerMark(Provider.ANTHROPIC.id))
        assertEquals(ProviderMark.CLOVER, providerMark(Provider.CLAUDE_SUB.id))
        assertEquals(ProviderMark.PILL, providerMark(Provider.OPENAI.id))
        assertEquals(ProviderMark.PILL, providerMark(Provider.CHATGPT_SUB.id))
        assertEquals(ProviderMark.SUNNY, providerMark(Provider.KIMI.id))
        assertEquals(ProviderMark.COOKIE, providerMark("unknown-vendor"))
        assertEquals(ProviderMark.entries.toSet(), marks.values.toSet())
    }

    @Test
    fun providerLetterUsesFirstCharacter() {
        assertEquals("D", providerLetter("DeepSeek 主账号", "DeepSeek"))
        assertEquals("智", providerLetter("智谱", "智谱 GLM"))
        assertEquals("C", providerLetter("  ", "Claude / Anthropic"))
    }

    @Test
    fun groupItemShapeUses24And6And28WhenLifted() {
        val first = groupItemShape(0, 3, large = 24.dp)
        val mid = groupItemShape(1, 3, large = 24.dp)
        val last = groupItemShape(2, 3, large = 24.dp)
        val one = groupItemShape(0, 1, large = 24.dp)
        val lifted = groupItemShape(1, 3, large = 24.dp, lifted = true)
        assertEquals(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp), first)
        assertEquals(RoundedCornerShape(6.dp), mid)
        assertEquals(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp), last)
        assertEquals(RoundedCornerShape(24.dp), one)
        assertEquals(RoundedCornerShape(28.dp), lifted)
    }
}
