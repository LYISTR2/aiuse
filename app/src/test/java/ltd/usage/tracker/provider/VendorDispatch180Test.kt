package ltd.usage.tracker.provider

import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.provider.vendors.httpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * 1.8.0 新增 4 家服务商的请求与解析金样：请求方法、URL、请求头（按顺序）、解析结果。
 * 与 [VendorDispatchTest] 同一套 4 种响应；走带 Credential 的入口（ChatGPT 订阅要 accountId 头）。
 * 重置时间按本机时区显示，这里固定 UTC，金样才与机器无关。
 */
class VendorDispatch180Test {
    private val token = "sk-test-token-1234567890"
    private lateinit var tz: TimeZone

    @Before fun utc() { tz = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restore() { TimeZone.setDefault(tz) }

    private val samples: Map<String, String> = mapOf(
        "api.anthropic.com" to """{"five_hour":{"utilization":37.0,"resets_at":"2026-11-14T10:00:00.123456+00:00"},"seven_day":{"utilization":81.5,"resets_at":"2026-11-18T00:00:00Z"},"seven_day_opus":null,"extra_usage":{"is_enabled":false}}""",
        "chatgpt.com" to """{"plan_type":"plus","rate_limit":{"allowed":true,"limit_reached":false,"primary_window":{"used_percent":12,"limit_window_seconds":18000,"reset_after_seconds":3600,"reset_at":1763110800},"secondary_window":{"used_percent":64,"limit_window_seconds":604800,"reset_after_seconds":300000,"reset_at":1763400000}},"credits":null}""",
        "opencode.ai" to """{"usage":{"rolling":{"status":"ok","percent":42,"resetsAt":"2026-11-14T17:00:00.000Z"},"weekly":{"status":"ok","percent":18,"resetsAt":"2026-11-17T00:00:00.000Z"},"monthly":{"status":"rate-limited","percent":100,"resetsAt":"2026-12-01T00:00:00.000Z"}}}""",
        "api.commandcode.ai" to """{"credits":{"monthlyCredits":8.5,"purchasedCredits":0,"premiumMonthlyCredits":0,"opensourceMonthlyCredits":0},"windowLimits":{"fiveHour":{"cap":3,"used":0.75,"resetAt":1780000000000},"weekly":{"cap":15,"used":1.5,"resetAt":1780100000000}}}""",
    )

    private fun dump(): String {
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
        val original = httpClient
        httpClient = client
        try {
            for (p in Provider.entries.drop(VendorDispatchTest.LEGACY_COUNT)) {
                out.append(p.id).append('\n')
                for (m in listOf("sample", "empty", "garbage", "401")) {
                    mode = m
                    out.append(" [").append(m).append("]\n")
                    val r = UsageFetcher.fetch(p, Credential(token = token, accountId = "acct-123"))
                    out.append("  => ").append(r).append('\n')
                }
            }
        } finally {
            httpClient = original
        }
        return out.toString()
    }

    @Test
    fun requestsAndParseResultsMatch180() {
        val actual = dump()
        val golden = javaClass.classLoader!!.getResource("fixtures/vendor-requests-1.8.0.txt")?.readText()
        // 金样缺失时把实际输出写到 build/ 下供人工核对后再放进 fixtures（不会自动生成金样）
        if (golden == null) java.io.File("build/vendor-requests-1.8.0.actual.txt").writeText(actual)
        assertEquals(golden, actual)
    }
}
