package ltd.usage.tracker.provider

import ltd.usage.tracker.provider.vendors.AnthropicVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Anthropic API key 用量的 utilization 已是 0–100，不能再乘 100。 */
class AnthropicUtilizationTest {

    @Test
    fun utilizationIsAlreadyPercent() {
        assertEquals("5h 窗口已用 37%", AnthropicVendor.parse("""{"five_hour":{"utilization":37.0}}""").note)
        assertEquals("5h 窗口已用 0%", AnthropicVendor.parse("""{"five_hour":{"utilization":0.4}}""").note)
        assertEquals("5h 窗口已用 100%", AnthropicVendor.parse("""{"five_hour":{"utilization":100.0}}""").note)
        assertNull(AnthropicVendor.parse("{}").note)
    }
}
