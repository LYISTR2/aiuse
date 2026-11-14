package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * OpenCode Go 订阅（API Key）：5 小时、每周、每月三个额度窗口。
 *
 * 端点：GET https://opencode.ai/zen/go/v1/usage，Authorization: Bearer <OpenCode API Key>
 * 来源：
 *  - anomalyco/opencode（原 sst/opencode）d759c54 packages/core/src/usage-limits.ts readGo()
 *  - steipete/CodexBar 621082f Sources/CodexBarCore/Providers/OpenCodeGo/OpenCodeGoUsageFetcher.swift
 * 响应：{usage:{rolling|weekly|monthly:{status, percent(0-100), resetsAt(ISO)}}}；rolling 即 5 小时窗口。
 * 403 表示该工作区没有 Go 订阅。opencode 自己还会带 x-opencode-org-id，
 * CodexBar 只用 API Key 不带；API Key 本身属于某个工作区，这里同样不带（真机确认项）。
 */
internal object OpenCodeGoVendor : Vendor {

    const val URL = "https://opencode.ai/zen/go/v1/usage"

    override fun fetch(token: String): UsageResult = parse(get(URL, bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse { return UsageResult(error = "响应不是合法 JSON") }
        val usage = root["usage"] as? JsonObject ?: return UsageResult(error = "该账号未返回 Go 额度")
        val metrics = mutableListOf<Metric>()
        for ((key, label) in listOf("rolling" to "5 小时", "weekly" to "每周", "monthly" to "每月")) {
            val o = usage[key] as? JsonObject ?: continue
            val pct = (o["percent"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: continue
            metrics += windowMetric(label, pct, parseResetAt((o["resetsAt"] as? JsonPrimitive)?.contentOrNull))
        }
        if (metrics.isEmpty()) return UsageResult(error = "该账号未返回 Go 额度")
        return UsageResult(metrics = metrics)
    }
}
