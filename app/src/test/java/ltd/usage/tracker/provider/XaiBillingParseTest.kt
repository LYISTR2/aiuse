package ltd.usage.tracker.provider

import ltd.usage.tracker.provider.vendors.XaiVendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * xAI billing 响应解析测试。
 *
 * 用例取自真实的 `cli-chat-proxy.grok.com/v1/billing?format=credits` 响应结构
 * （总额度池 + productUsage 分产品用量）。
 * 接口没有文档，label 只反映接口代号（驼峰拆词），不另起「Grok Bot」这类名字。
 * 这些测试锁住字段名与语义，防止升级时静默解析失败。
 */
class XaiBillingParseTest {

    /** 完整响应：周额度已用 100%，GrokBuild 100%，GrokChat 42%。 */
    private val fullResponse = """
    {"config":{
      "currentPeriod":{"type":"USAGE_PERIOD_TYPE_WEEKLY",
        "start":"2026-08-08T01:53:09.930537+00:00",
        "end":"2026-08-15T01:53:09.930537+00:00"},
      "creditUsagePercent":100.0,
      "onDemandCap":{"val":2000},"onDemandUsed":{"val":0},
      "productUsage":[{"product":"GrokBuild","usagePercent":100.0},
                      {"product":"GrokChat","usagePercent":42.0}],
      "prepaidBalance":{"val":0},
      "isUnifiedBillingUser":true,
      "billingPeriodStart":"2026-08-08T01:53:09.930537+00:00",
      "billingPeriodEnd":"2026-08-15T01:53:09.930537+00:00"
    }}
    """.trimIndent()

    @Test
    fun `parses weekly quota and both products`() {
        val r = XaiVendor.parse(fullResponse)
        assertNull(r.error)
        assertTrue("should expose multiple metrics", r.metrics.size >= 3)

        val weekly = r.metrics.first { it.label == "SuperGrok 周额度" }
        // creditUsagePercent 是「已用」，不是剩余
        assertEquals(100.0, weekly.usedPercent!!, 0.001)
        assertEquals("已用 100% · 08-15 重置", weekly.note)

        // 接口没有文档，label 只反映代号：GrokBuild → Grok Build，不再映射成 SuperGrok
        val build = r.metrics.first { it.label == "Grok Build" }
        assertEquals(100.0, build.usedPercent!!, 0.001)

        // GrokChat 按同一规则显示为 Grok Chat；不要再出现「Grok Bot」
        val chat = r.metrics.first { it.label == "Grok Chat" }
        assertEquals(42.0, chat.usedPercent!!, 0.001)
        assertTrue(r.metrics.none { it.label == "Grok Bot" })
        assertTrue(r.metrics.none { it.label == "SuperGrok" })
    }

    @Test
    fun `on-demand cap becomes remaining balance in cents`() {
        val r = XaiVendor.parse(fullResponse)
        val onDemand = r.metrics.first { it.label == "按量" }
        // cap 2000 分 - used 0 分 = 2000 分
        assertEquals(2000L, onDemand.balanceMinor)
        assertEquals("USD", onDemand.currency)
    }

    @Test
    fun `monthly period is labelled monthly`() {
        val body = fullResponse.replace("USAGE_PERIOD_TYPE_WEEKLY", "USAGE_PERIOD_TYPE_MONTHLY")
        val r = XaiVendor.parse(body)
        assertNotNull(r.metrics.firstOrNull { it.label == "SuperGrok 月额度" })
    }

    @Test
    fun `missing creditUsagePercent still yields product metrics`() {
        // 已知情况：部分账号不返回 creditUsagePercent（上游 issue #15740）
        val body = """
        {"config":{
          "productUsage":[{"product":"GrokBuild","usagePercent":30.0}],
          "isUnifiedBillingUser":true
        }}
        """.trimIndent()
        val r = XaiVendor.parse(body)
        assertNull(r.error)
        assertEquals(1, r.metrics.size)
        assertEquals("Grok Build", r.metrics[0].label)
        assertEquals(30.0, r.metrics[0].usedPercent!!, 0.001)
    }

    @Test
    fun `empty config reports error instead of fabricating numbers`() {
        val r = XaiVendor.parse("""{"config":{}}""")
        assertNotNull(r.error)
        assertTrue(r.metrics.isEmpty())
    }

    @Test
    fun `invalid json reports error`() {
        val r = XaiVendor.parse("not json at all")
        assertNotNull(r.error)
    }

    @Test
    fun `zero on-demand cap is not shown as a metric`() {
        val body = fullResponse.replace("\"onDemandCap\":{\"val\":2000}", "\"onDemandCap\":{\"val\":0}")
        val r = XaiVendor.parse(body)
        assertNull(r.metrics.firstOrNull { it.label == "按量" })
    }

    @Test
    fun `prepaid balance surfaces when positive`() {
        val body = fullResponse.replace("\"prepaidBalance\":{\"val\":0}", "\"prepaidBalance\":{\"val\":1500}")
        val r = XaiVendor.parse(body)
        val prepaid = r.metrics.first { it.label == "预付" }
        assertEquals(1500L, prepaid.balanceMinor)
    }
}
