package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * Command Code（commandcode.ai，含 GOAT 套餐；API Key）：5 小时、每周用量窗口与当月剩余额度。
 *
 * 端点：GET https://api.commandcode.ai/alpha/billing/credits，Authorization: Bearer <user_… API Key>
 * 来源：
 *  - 官方 CLI npm command-code@1.89.1 dist/cli.mjs：fetchUsageCredits() 调 /alpha/billing/credits，
 *    projectUsageView() 读 credits.monthlyCredits / purchasedCredits / freeCredits 与 credits.windowLimits
 *  - steipete/CodexBar 621082f Sources/CodexBarCore/Providers/CommandCode/CommandCodeUsageFetcher.swift
 *    及测试样例 Tests/CodexBarTests/Fixtures/Providers/CommandCode/window-limits-{root,nested}.json
 * windowLimits 可能在顶层，也可能嵌在 credits 里；cap / used 是美元，可能是数字也可能是字符串；
 * resetAt 可能是 epoch 秒、epoch 毫秒或 ISO。
 */
internal object CommandCodeVendor : Vendor {

    const val URL = "https://api.commandcode.ai/alpha/billing/credits"

    override fun fetch(token: String): UsageResult = parse(get(URL, bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse { return UsageResult(error = "响应不是合法 JSON") }
        val credits = root["credits"] as? JsonObject
        val limits = (root["windowLimits"] as? JsonObject) ?: (credits?.get("windowLimits") as? JsonObject)

        val metrics = mutableListOf<Metric>()
        for ((key, label) in listOf("fiveHour" to "5 小时", "weekly" to "每周")) {
            val w = limits?.get(key) as? JsonObject ?: continue
            val cap = w.num("cap") ?: continue
            val used = w.num("used") ?: continue
            if (cap <= 0) continue
            metrics += windowMetric(label, used / cap * 100.0, parseResetAt((w["resetAt"] as? JsonPrimitive)?.contentOrNull))
        }

        // 剩余额度：月度 + 购买 + 赠送，与官方 CLI 的 totalRemaining 口径一致（美元 → 分）
        if (credits != null) {
            val parts = listOf("monthlyCredits", "purchasedCredits", "freeCredits").mapNotNull { credits.num(it) }
            if (parts.isNotEmpty()) {
                metrics += Metric(
                    label = "剩余额度",
                    balanceMinor = Math.round(parts.sumOf { it.coerceAtLeast(0.0) } * 100),
                    currency = "USD",
                )
            }
        }
        if (metrics.isEmpty()) return UsageResult(error = "该账号未返回用量数据")
        return UsageResult(metrics = metrics)
    }
}
