package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * DeepInfra：GET /payment/checklist 返回计费状态。
 *
 * stripe_balance 的语义是「负值表示可用余额，正值表示欠款」，
 * 所以可用额度 = max(0, -stripe_balance)。
 * scoped_credits[] 是限定用途的赠金，单独列出。
 */
internal object DeepInfraVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.deepinfra.com/payment/checklist", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse {
            return UsageResult(error = "响应不是合法 JSON")
        }

        val stripe = root.num("stripe_balance")
        val limit = root.num("limit")
        val billingType = root["billing_type"]?.jsonPrimitive?.content
        val suspended = root["suspended"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

        val metrics = mutableListOf<Metric>()

        if (stripe != null) {
            val available = if (stripe < 0) -stripe else 0.0
            metrics += Metric(
                label = if (stripe < 0) "余额" else "欠款",
                balanceMinor = Math.round(available * 100),
                currency = "USD",
                note = buildString {
                    if (stripe > 0) append("欠 $${"%.2f".format(stripe)}")
                    billingType?.let { if (isNotEmpty()) append(" · "); append(it) }
                    if (suspended == true) { if (isNotEmpty()) append(" · "); append("已暂停") }
                }.ifBlank { null },
            )
        }

        (root["scoped_credits"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val remainCents = o.num("remaining_cents") ?: return@forEach
            if (remainCents <= 0) return@forEach
            val name = o["name"]?.jsonPrimitive?.content ?: "赠金"
            metrics += Metric(
                label = name,
                balanceMinor = Math.round(remainCents),
                currency = "USD",
                note = "限定用途",
            )
        }

        if (limit != null && limit > 0) {
            metrics += Metric(label = "消费上限", balanceMinor = null, currency = "USD", note = "$${"%.2f".format(limit)}")
        }

        if (metrics.isEmpty()) return UsageResult(error = "未返回计费信息")

        val primary = metrics.first()
        return UsageResult(
            balanceMinor = primary.balanceMinor,
            currency = "USD",
            metrics = metrics,
        )
    }
}
