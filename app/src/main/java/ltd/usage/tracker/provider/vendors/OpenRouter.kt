package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * OpenRouter：GET /api/v1/key 返回当前 key 的额度与用量。
 *
 * 字段：limit（总额度，null 表示不限）、usage（已用）、limit_remaining（剩余）、
 * budgets[]（细粒度预算，含 limit_usd / remaining_usd / reset_interval）。
 *
 * limit 为 null 时该 key 不限额度，只展示已用金额，不编造剩余额度。
 */
internal object OpenRouterVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://openrouter.ai/api/v1/key", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse {
            return UsageResult(error = "响应不是合法 JSON")
        }
        val data = (root["data"] as? JsonObject) ?: root

        val limit = data.num("limit")
        val usage = data.num("usage")
        val remaining = data.num("limit_remaining")
        val label = data["label"]?.jsonPrimitive?.content
        val isFreeTier = data["is_free_tier"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

        val metrics = mutableListOf<Metric>()

        if (limit != null && limit > 0) {
            val remain = remaining ?: (limit - (usage ?: 0.0))
            metrics += Metric(
                label = "额度",
                balanceMinor = Math.round(remain * 100),
                currency = "USD",
                usedPercent = usage?.let { (it / limit * 100).coerceIn(0.0, 100.0) },
                quotaMinor = Math.round(limit * 100),
                note = usage?.let { "已用 $${"%.2f".format(it)}" },
            )
        } else if (usage != null) {
            // 不限额度：只报已用，不臆造剩余
            metrics += Metric(
                label = "已用",
                balanceMinor = null,
                currency = "USD",
                note = "不限额度 · 已用 $${"%.2f".format(usage)}",
            )
        }

        // 细粒度预算（key 级 guardrail），逐条展示
        (data["budgets"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val remain = o.num("remaining_usd") ?: return@forEach
            val cap = o.num("limit_usd")
            val interval = o["reset_interval"]?.jsonPrimitive?.content
            metrics += Metric(
                label = budgetLabel(interval),
                balanceMinor = Math.round(remain * 100),
                currency = "USD",
                note = cap?.let { "上限 $${"%.2f".format(it)}" },
            )
        }

        if (metrics.isEmpty()) {
            return UsageResult(
                note = buildString {
                    append(label?.let { "Key: $it" } ?: "已认证")
                    if (isFreeTier == true) append(" · 免费层")
                }
            )
        }

        val primary = metrics.first()
        return UsageResult(
            balanceMinor = primary.balanceMinor,
            currency = primary.currency,
            metrics = metrics,
        )
    }

    private fun budgetLabel(interval: String?): String = when {
        interval == null -> "预算"
        interval.contains("daily", true) -> "日预算"
        interval.contains("weekly", true) -> "周预算"
        interval.contains("monthly", true) -> "月预算"
        else -> "预算"
    }
}
