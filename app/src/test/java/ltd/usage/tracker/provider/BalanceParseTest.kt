package ltd.usage.tracker.provider

import ltd.usage.tracker.provider.vendors.DeepInfraVendor
import ltd.usage.tracker.provider.vendors.NovitaVendor
import ltd.usage.tracker.provider.vendors.OpenRouterVendor
import ltd.usage.tracker.provider.vendors.SiliconFlowVendor
import ltd.usage.tracker.provider.vendors.StepFunVendor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 新增五家服务商的余额解析测试。
 *
 * 响应结构取自各家的公开 API 文档与实际 401 响应探测结果：
 * - OpenRouter: https://openrouter.ai/openapi.json（/key）
 * - SiliconFlow: /v1/user/info（data.totalBalance 等）
 * - Novita: /openapi/v1/billing/balance/detail（单位 1/10000 USD）
 * - DeepInfra: /payment/checklist（stripe_balance 负值表示可用）
 * - StepFun: /v1/accounts
 *
 * 这些测试锁住字段名与单位换算，防止对方改字段后静默显示错误金额。
 */
class BalanceParseTest {

    // ------------------------------------------------------------ OpenRouter

    @Test
    fun `openrouter with limit reports remaining in cents`() {
        val body = """
        {"data":{"label":"sk-or-v1-abc","limit":10.0,"usage":2.5,
                 "limit_remaining":7.5,"is_free_tier":false}}
        """.trimIndent()
        val r = OpenRouterVendor.parse(body)
        assertNull(r.error)
        // 7.5 USD = 750 分
        assertEquals(750L, r.balanceMinor)
        assertEquals("USD", r.currency)
        val quota = r.metrics.first { it.label == "额度" }
        assertEquals(25.0, quota.usedPercent!!, 0.001)
        assertEquals("已用 $2.50", quota.note)
    }

    @Test
    fun `openrouter without limit reports usage only and does not invent remaining`() {
        val body = """{"data":{"label":"unlimited","limit":null,"usage":12.34}}"""
        val r = OpenRouterVendor.parse(body)
        assertNull(r.error)
        // 不限额度时不能编造剩余金额
        assertNull(r.balanceMinor)
        val used = r.metrics.first { it.label == "已用" }
        assertNull(used.balanceMinor)
        assertTrue(used.note!!.contains("12.34"))
    }

    @Test
    fun `openrouter budgets become separate metrics`() {
        val body = """
        {"data":{"limit":100.0,"usage":10.0,"limit_remaining":90.0,
                 "budgets":[{"limit_usd":5.0,"remaining_usd":3.5,"reset_interval":"daily"},
                            {"limit_usd":20.0,"remaining_usd":18.0,"reset_interval":"monthly"}]}}
        """.trimIndent()
        val r = OpenRouterVendor.parse(body)
        assertEquals(350L, r.metrics.first { it.label == "日预算" }.balanceMinor)
        assertEquals(1800L, r.metrics.first { it.label == "月预算" }.balanceMinor)
    }

    @Test
    fun `openrouter invalid json reports error`() {
        assertNotNull(OpenRouterVendor.parse("<html>").error)
    }

    // ---------------------------------------------------------- SiliconFlow

    @Test
    fun `siliconflow reads total balance in yuan`() {
        val body = """
        {"code":20000,"message":"OK","status":true,
         "data":{"id":"u1","balance":"12.34","chargeBalance":"10.00",
                 "totalBalance":"22.34","status":"normal"}}
        """.trimIndent()
        val r = SiliconFlowVendor.parse(body)
        assertNull(r.error)
        // 22.34 元 = 2234 分
        assertEquals(2234L, r.balanceMinor)
        assertEquals("CNY", r.currency)
        // 赠金 12.34 元单独列出
        assertEquals(1234L, r.metrics.first { it.label == "赠金" }.balanceMinor)
    }

    @Test
    fun `siliconflow arrears status is surfaced`() {
        val body = """{"data":{"totalBalance":"0.00","chargeBalance":"0.00","status":"arrears"}}"""
        val r = SiliconFlowVendor.parse(body)
        assertEquals("状态: arrears", r.metrics.first().note)
    }

    @Test
    fun `siliconflow missing data reports upstream message`() {
        val r = SiliconFlowVendor.parse("""{"code":30014,"message":"Token is invalid."}""")
        assertEquals("Token is invalid.", r.error)
    }

    // -------------------------------------------------------------- Novita

    @Test
    fun `novita converts ten-thousandth dollars to cents`() {
        // availableBalance 10000 = $1.00 = 100 分
        val body = """{"availableBalance":"250000","cashBalance":"250000","outstandingInvoices":"0"}"""
        val r = NovitaVendor.parse(body)
        assertNull(r.error)
        assertEquals(2500L, r.balanceMinor)
        assertEquals("USD", r.currency)
    }

    @Test
    fun `novita outstanding invoices shown as debt`() {
        val body = """{"availableBalance":"0","cashBalance":"0","outstandingInvoices":"500000"}"""
        val r = NovitaVendor.parse(body)
        val owed = r.metrics.first { it.label == "欠费" }
        assertEquals(5000L, owed.balanceMinor)
    }

    @Test
    fun `novita credit limit is reported in dollars`() {
        val body = """{"availableBalance":"1000000","cashBalance":"1000000","creditLimit":"2000000"}"""
        val r = NovitaVendor.parse(body)
        assertEquals("信用额度 $200.00", r.metrics.first().note)
    }

    // ----------------------------------------------------------- DeepInfra

    @Test
    fun `deepinfra negative stripe balance means available funds`() {
        val body = """{"stripe_balance":-15.5,"billing_type":"stripe","suspended":false}"""
        val r = DeepInfraVendor.parse(body)
        assertNull(r.error)
        // -15.5 表示可用 $15.50 = 1550 分
        assertEquals(1550L, r.balanceMinor)
        assertEquals("余额", r.metrics.first().label)
    }

    @Test
    fun `deepinfra positive stripe balance means money owed`() {
        val body = """{"stripe_balance":8.25,"billing_type":"stripe"}"""
        val r = DeepInfraVendor.parse(body)
        val m = r.metrics.first()
        assertEquals("欠款", m.label)
        assertEquals(0L, m.balanceMinor)  // 可用额度为 0
        assertTrue(m.note!!.contains("8.25"))
    }

    @Test
    fun `deepinfra scoped credits listed separately and zero ones skipped`() {
        val body = """
        {"stripe_balance":-1.0,
         "scoped_credits":[{"name":"Image credit","remaining_cents":500},
                           {"name":"Expired","remaining_cents":0}]}
        """.trimIndent()
        val r = DeepInfraVendor.parse(body)
        assertEquals(500L, r.metrics.first { it.label == "Image credit" }.balanceMinor)
        assertNull(r.metrics.firstOrNull { it.label == "Expired" })
    }

    @Test
    fun `deepinfra empty checklist reports error`() {
        assertNotNull(DeepInfraVendor.parse("{}").error)
    }

    // ------------------------------------------------------------- StepFun

    @Test
    fun `stepfun reads balance and splits voucher from cash`() {
        val body = """
        {"type":"prepaid","balance":88.5,
         "total_cash_balance":100.0,"total_voucher_balance":20.0}
        """.trimIndent()
        val r = StepFunVendor.parse(body)
        assertNull(r.error)
        assertEquals(8850L, r.balanceMinor)
        assertEquals("CNY", r.currency)
        assertEquals("预付费", r.metrics.first().note)
        assertEquals(2000L, r.metrics.first { it.label == "赠金" }.balanceMinor)
        assertEquals(10000L, r.metrics.first { it.label == "累计充值" }.balanceMinor)
    }

    @Test
    fun `stepfun postpaid account is labelled`() {
        val body = """{"type":"postpaid","balance":0.0}"""
        val r = StepFunVendor.parse(body)
        assertEquals("后付费", r.metrics.first().note)
    }

    @Test
    fun `stepfun missing balance reports error`() {
        assertNotNull(StepFunVendor.parse("""{"type":"prepaid"}""").error)
    }
}
