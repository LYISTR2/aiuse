package ltd.usage.tracker.provider

import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.UsageResult
import ltd.usage.tracker.provider.vendors.httpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拆分 UsageFetcher 后的护栏。
 *
 * fixtures/vendor-requests-1.6.1.txt 是用 1.6.1 原版 UsageFetcher 跑同一段 dump 导出的：
 * 每家服务商 × 4 种响应，记录请求方法、URL、请求头（按顺序）和解析结果。
 * 1.7.0 的输出必须与它逐字节一致。
 *
 * 1.8.0 在 Provider 末尾追加了 4 家；1.6.1 金样只覆盖前 [LEGACY_COUNT] 家，仍要求逐字节一致，
 * 新增的 4 家另有金样，见 [VendorDispatch180Test]。
 */
class VendorDispatchTest {
    private val token = "sk-test-token-1234567890"

    /** 每家各喂几种响应：真实样例、空对象、非 JSON、401 回显 token。 */
    private val samples: Map<String, String> = mapOf(
        "api.deepseek.com" to """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.25"}]}""",
        "api.anthropic.com" to """{"five_hour":{"utilization":0.37}}""",
        "api.openai.com" to """{"orgs":[{"title":"Acme"}]}""",
        "api.moonshot.cn" to """{"data":{"available_balance":"12.5"}}""",
        "open.bigmodel.cn" to """{"msg":"ok"}""",
        "cli-chat-proxy.grok.com" to """{"config":{"creditUsagePercent":"12.5","currentPeriod":{"type":"WEEKLY","end":"2026-11-20T00:00:00+00:00"},"productUsage":[{"product":"GrokBuild","usagePercent":"20"}],"prepaidBalance":{"val":"500"},"onDemandCap":{"val":"1000"},"onDemandUsed":{"val":"250"}}}""",
        "api.mistral.ai" to """{"api_budget":{"usage_percentage":16,"initial_budget":50,"currency":"EUR"},"vibe_budget":{"usage_percentage":42.5,"initial_budget":0,"payg_enabled":true}}""",
        "openrouter.ai" to """{"data":{"limit":20,"usage":5.5,"limit_remaining":14.5,"budgets":[{"remaining_usd":3,"limit_usd":5,"reset_interval":"daily"}]}}""",
        "api.siliconflow.cn" to """{"data":{"totalBalance":"9.8","chargeBalance":"5","balance":"4.8","status":"normal"}}""",
        "api.novita.ai" to """{"availableBalance":"123456","cashBalance":"100000","creditLimit":"50000","outstandingInvoices":"0"}""",
        "api.deepinfra.com" to """{"stripe_balance":-12.34,"limit":100,"billing_type":"prepaid","scoped_credits":[{"name":"活动","remaining_cents":250}]}""",
        "api.stepfun.com" to """{"type":"prepaid","balance":7.7,"total_cash_balance":10,"total_voucher_balance":2}""",
    )

    private fun dump(
        fetch: (Provider, String) -> UsageResult,
        providers: List<Provider> = Provider.entries.take(LEGACY_COUNT),
        install: (OkHttpClient) -> Unit,
    ): String {
        val out = StringBuilder()
        var mode = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            out.append("  ").append(req.method).append(' ').append(req.url).append('\n')
            req.headers.forEach { (k, v) -> out.append("    ").append(k).append(": ").append(v).append('\n') }
            val (code, body) = when (mode) {
                "sample" -> 200 to samples.getValue(req.url.host)
                "empty" -> 200 to "{}"
                "garbage" -> 200 to "oops<html>"
                else -> 401 to """{"error":"invalid key $token"}"""
            }
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        install(client)
        for (p in providers) {
            out.append(p.id).append('\n')
            for (m in listOf("sample", "empty", "garbage", "401")) {
                mode = m
                out.append(" [").append(m).append("]\n")
                val r = fetch(p, token)
                out.append("  => ").append(r).append('\n')
            }
        }
        return out.toString()
    }

    companion object {
        /** 1.6.1 时的服务商数量；新服务商只能追加在其后。 */
        const val LEGACY_COUNT = 12
    }

    @Test
    fun legacyProvidersKeepTheirOrder() {
        assertEquals(
            listOf("deepseek", "anthropic", "openai", "kimi", "zhipu", "xai", "mistral", "openrouter",
                "siliconflow", "novita", "deepinfra", "stepfun"),
            Provider.entries.take(LEGACY_COUNT).map { it.id },
        )
    }

    @Test
    fun everyProviderHasVendor() {
        assertEquals(Provider.entries.toSet(), UsageFetcher.vendors.keys)
    }

    @Test
    fun requestsAndParseResultsMatch161() {
        val golden = javaClass.classLoader!!.getResource("fixtures/vendor-requests-1.6.1.txt")!!.readText()
        val original = httpClient
        try {
            assertEquals(golden, dump(UsageFetcher::fetch) { httpClient = it })
        } finally {
            httpClient = original
        }
    }
}
