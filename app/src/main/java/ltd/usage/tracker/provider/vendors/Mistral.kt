package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonObject
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/** Mistral AI：GET /v1/billing/budget 返回本计费周期的 API 与 Vibe 预算使用率。 */
internal object MistralVendor : Vendor {

    override fun fetch(token: String): UsageResult = parse(
        get("https://api.mistral.ai/v1/billing/budget", listOf("x-api-key" to token, ACCEPT_JSON))
    )

    override fun parse(body: String): UsageResult {
        val root = obj(body)
        val metrics = listOfNotNull(
            parseBudget(root.obj("api_budget"), "API"),
            parseBudget(root.obj("vibe_budget"), "Vibe"),
        )
        if (metrics.isEmpty()) return UsageResult(error = "未返回预算信息")
        val primary = metrics.first()
        return UsageResult(balanceMinor = primary.balanceMinor, currency = primary.currency, metrics = metrics)
    }

    private fun parseBudget(o: JsonObject?, label: String): Metric? {
        if (o == null) return null
        val percent = o.num("usage_percentage")
        val initial = o.num("initial_budget")
        val currency = o.str("currency")
        val payg = o.bool("payg_enabled")

        val remainingMinor = if (initial != null && percent != null && initial > 0) {
            (initial * (1.0 - percent / 100.0)).toMinor()
        } else null

        val note = buildList {
            percent?.let { add("已用 ${it.toInt()}%") }
            if (payg == true) add("按量超支已开启")
        }.joinToString(" · ").ifBlank { null }

        return Metric(
            label = label,
            balanceMinor = remainingMinor,
            currency = currency,
            usedPercent = percent,
            quotaMinor = initial?.takeIf { it > 0 }?.toMinor(),
            note = note,
        )
    }
}
