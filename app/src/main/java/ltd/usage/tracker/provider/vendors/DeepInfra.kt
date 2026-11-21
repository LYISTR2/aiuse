package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/** DeepInfra：GET /payment/checklist（stripe_balance 负值表示可用余额，正值表示欠款）。 */
internal object DeepInfraVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.deepinfra.com/payment/checklist", bearer(token)))

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val stripe = root.num("stripe_balance")
        val limit = root.num("limit")
        val billingType = root.str("billing_type")
        val suspended = root.bool("suspended")

        val metrics = mutableListOf<Metric>()

        if (stripe != null) {
            val available = if (stripe < 0) -stripe else 0.0
            val note = buildList {
                if (stripe > 0) add("欠 $${"%.2f".format(stripe)}")
                billingType?.let { add(it) }
                if (suspended == true) add("已暂停")
            }.joinToString(" · ").ifBlank { null }
            metrics += Metric(
                label = if (stripe < 0) "余额" else "欠款",
                balanceMinor = available.toMinor(),
                currency = "USD",
                note = note,
            )
        }

        root.arr("scoped_credits")?.forEach { el ->
            val o = el.asObj() ?: return@forEach
            val remainCents = o.num("remaining_cents")?.takeIf { it > 0 } ?: return@forEach
            metrics += Metric(
                label = o.str("name") ?: "赠金",
                balanceMinor = Math.round(remainCents),
                currency = "USD",
                note = "限定用途",
            )
        }

        if (limit != null && limit > 0) {
            metrics += Metric(label = "消费上限", currency = "USD", note = "$${"%.2f".format(limit)}")
        }

        if (metrics.isEmpty()) UsageResult(error = "未返回计费信息")
        else UsageResult(balanceMinor = metrics.first().balanceMinor, currency = "USD", metrics = metrics)
    }
}
