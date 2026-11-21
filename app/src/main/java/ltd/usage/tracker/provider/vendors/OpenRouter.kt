package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/** OpenRouter：GET /api/v1/key 返回当前 key 的额度与用量。 */
internal object OpenRouterVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://openrouter.ai/api/v1/key", bearer(token)))

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val data = root.obj("data") ?: root
        val limit = data.num("limit")
        val usage = data.num("usage")
        val remaining = data.num("limit_remaining")
        val label = data.str("label")
        val isFreeTier = data.bool("is_free_tier")

        val metrics = mutableListOf<Metric>()

        if (limit != null && limit > 0) {
            val remain = remaining ?: (limit - (usage ?: 0.0))
            metrics += Metric(
                label = "额度",
                balanceMinor = remain.toMinor(),
                currency = "USD",
                usedPercent = usage?.let { (it / limit * 100).coerceIn(0.0, 100.0) },
                quotaMinor = limit.toMinor(),
                note = usage?.let { "已用 $${"%.2f".format(it)}" },
            )
        } else if (usage != null) {
            metrics += Metric(
                label = "已用",
                currency = "USD",
                note = "不限额度 · 已用 $${"%.2f".format(usage)}",
            )
        }

        data.arr("budgets")?.forEach { el ->
            val o = el.asObj() ?: return@forEach
            val remain = o.num("remaining_usd") ?: return@forEach
            val cap = o.num("limit_usd")
            metrics += Metric(
                label = budgetLabel(o.str("reset_interval")),
                balanceMinor = remain.toMinor(),
                currency = "USD",
                note = cap?.let { "上限 $${"%.2f".format(it)}" },
            )
        }

        if (metrics.isEmpty()) {
            UsageResult(
                note = buildString {
                    append(label?.let { "Key: $it" } ?: "已认证")
                    if (isFreeTier == true) append(" · 免费层")
                }
            )
        } else {
            val primary = metrics.first()
            UsageResult(balanceMinor = primary.balanceMinor, currency = primary.currency, metrics = metrics)
        }
    }

    private fun budgetLabel(interval: String?): String = when {
        interval == null -> "预算"
        interval.contains("daily", true) -> "日预算"
        interval.contains("weekly", true) -> "周预算"
        interval.contains("monthly", true) -> "月预算"
        else -> "预算"
    }
}
