package ltd.usage.tracker.provider

import ltd.usage.tracker.provider.vendors.ChatGptSubscriptionVendor
import ltd.usage.tracker.provider.vendors.ClaudeSubscriptionVendor
import ltd.usage.tracker.provider.vendors.CommandCodeVendor
import ltd.usage.tracker.provider.vendors.OpenCodeGoVendor
import ltd.usage.tracker.provider.vendors.parseResetAt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/** 1.8.0 新增服务商的解析：样例取自 Claude Code / codex-rs / opencode / CodexBar 的真实响应结构。 */
class SubscriptionParseTest {
    private lateinit var tz: TimeZone

    @Before fun utc() { tz = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restore() { TimeZone.setDefault(tz) }

    // ---- Claude

    @Test
    fun claude_fiveHourAndWeekly() {
        val r = ClaudeSubscriptionVendor.parse(
            """{"five_hour":{"utilization":6.0,"resets_at":"2026-11-14T13:59:59.943648+00:00"},
               "seven_day":{"utilization":35.0,"resets_at":"2026-11-19T03:59:59.943679+00:00"},
               "seven_day_oauth_apps":null,"seven_day_opus":{"utilization":0.0,"resets_at":null},
               "extra_usage":{"is_enabled":false,"monthly_limit":null}}"""
        )
        assertNull(r.error)
        assertEquals(listOf("5 小时", "每周"), r.metrics.map { it.label })
        assertEquals(6.0, r.metrics[0].usedPercent!!, 0.0)
        assertEquals("已用 6% · 11-14 13:59 重置", r.metrics[0].note)
        assertEquals("已用 35% · 11-19 03:59 重置", r.metrics[1].note)
    }

    @Test
    fun claude_weeklyFallsBackToOauthApps_andMissingResetIsOmitted() {
        val r = ClaudeSubscriptionVendor.parse(
            """{"five_hour":null,"seven_day":null,"seven_day_oauth_apps":{"utilization":12.5,"resets_at":null}}"""
        )
        assertEquals(listOf("每周"), r.metrics.map { it.label })
        assertEquals("已用 12%", r.metrics[0].note)
    }

    @Test
    fun claude_freeAccount_isErrorNotZero() {
        val r = ClaudeSubscriptionVendor.parse("""{"five_hour":null,"seven_day":null}""")
        assertEquals("该账号未返回订阅额度（需要 Pro / Max）", r.error)
        // 字符串形式的百分比不认（CodexBar 同样忽略），不编造数字
        val s = ClaudeSubscriptionVendor.parse("""{"five_hour":{"utilization":"50"}}""")
        assertEquals(0, s.metrics.size)
    }

    // ---- ChatGPT / Codex

    @Test
    fun codex_mapsWindowsByLength_notByPosition() {
        // 团队版：只有每周窗口，而且放在 primary 里
        val r = ChatGptSubscriptionVendor.parse(
            """{"plan_type":"team","rate_limit":{"primary_window":{"used_percent":40,"limit_window_seconds":604800,"reset_at":1763424000},"secondary_window":null}}"""
        )
        assertEquals(listOf("每周"), r.metrics.map { it.label })
        assertEquals("已用 40% · 11-18 00:00 重置", r.metrics[0].note)
        assertEquals("套餐: team", r.note)
    }

    @Test
    fun codex_bothWindows_andUnknownLength() {
        val r = ChatGptSubscriptionVendor.parse(
            """{"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":99.6,"limit_window_seconds":18000,"reset_at":1763110800},
               "secondary_window":{"used_percent":3,"limit_window_seconds":2592000,"reset_at":1765000000}}}"""
        )
        assertEquals(listOf("5 小时", "30 天"), r.metrics.map { it.label })
        assertEquals(99.6, r.metrics[0].usedPercent!!, 1e-9)
    }

    @Test
    fun codex_noRateLimit_isError() {
        assertEquals("该账号未返回订阅额度", ChatGptSubscriptionVendor.parse("""{"plan_type":"free","rate_limit":null}""").error)
    }

    // ---- OpenCode Go

    @Test
    fun openCodeGo_threeWindows() {
        val r = OpenCodeGoVendor.parse(
            """{"usage":{"rolling":{"status":"ok","percent":42,"resetsAt":"2026-10-24T17:00:00.000Z"},
               "weekly":{"status":"ok","percent":18,"resetsAt":"2026-10-24T17:00:00.000Z"},
               "monthly":{"status":"rate-limited","percent":100,"resetsAt":"2026-10-24T17:00:00.000Z"}}}"""
        )
        assertEquals(listOf("5 小时", "每周", "每月"), r.metrics.map { it.label })
        assertEquals(listOf(42.0, 18.0, 100.0), r.metrics.map { it.usedPercent })
        assertEquals("已用 100% · 10-24 17:00 重置", r.metrics[2].note)
    }

    @Test
    fun openCodeGo_partialAndEmpty() {
        val r = OpenCodeGoVendor.parse("""{"usage":{"rolling":{"status":"ok","percent":5,"resetsAt":"2026-10-24T17:00:00Z"}}}""")
        assertEquals(listOf("5 小时"), r.metrics.map { it.label })
        assertEquals("该账号未返回 Go 额度", OpenCodeGoVendor.parse("""{"usage":{}}""").error)
    }

    // ---- Command Code

    @Test
    fun commandCode_nestedLimits_stringNumbers_secondsReset() {
        // CodexBar 样例 window-limits-nested.json：windowLimits 在 credits 里，cap/used 是字符串，resetAt 是秒
        val r = CommandCodeVendor.parse(
            """{"credits":{"monthlyCredits":7.25,"purchasedCredits":2,"premiumMonthlyCredits":0,"opensourceMonthlyCredits":0,
               "windowLimits":{"fiveHour":{"cap":"4","used":"1","resetAt":"1780200000"},"weekly":{"cap":20,"used":4,"resetAt":1780300000000}}}}"""
        )
        assertEquals(listOf("5 小时", "每周", "剩余额度"), r.metrics.map { it.label })
        assertEquals(25.0, r.metrics[0].usedPercent!!, 1e-9)
        assertEquals(20.0, r.metrics[1].usedPercent!!, 1e-9)
        assertEquals(parseResetAt("1780200000"), 1_780_200_000_000L)
        assertEquals(925L, r.metrics[2].balanceMinor) // 7.25 + 2 美元
        assertEquals("USD", r.metrics[2].currency)
    }

    @Test
    fun commandCode_zeroCapSkipped_creditsOnly() {
        val r = CommandCodeVendor.parse(
            """{"credits":{"monthlyCredits":-1,"freeCredits":0.5},"windowLimits":{"fiveHour":{"cap":0,"used":0,"resetAt":null}}}"""
        )
        assertEquals(listOf("剩余额度"), r.metrics.map { it.label })
        assertEquals(50L, r.metrics[0].balanceMinor) // 负数按 0 计，与官方 CLI 的 Math.max(0, …) 一致
    }

    @Test
    fun resetAt_formats() {
        assertEquals(1_780_000_000_000L, parseResetAt("1780000000000"))
        assertEquals(1_780_000_000_000L, parseResetAt("1780000000"))
        assertEquals(1_763_100_000_000L, parseResetAt("2025-11-14T06:00:00Z"))
        assertEquals(1_763_100_000_000L, parseResetAt("2025-11-14T14:00:00+08:00"))
        assertNull(parseResetAt("null"))
        assertNull(parseResetAt("soon"))
    }
}
