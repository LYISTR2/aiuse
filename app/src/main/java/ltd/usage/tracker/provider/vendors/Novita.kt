package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/** Novita AI：GET /openapi/v1/billing/balance/detail（单位 1/10000 USD）。 */
internal object NovitaVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.novita.ai/openapi/v1/billing/balance/detail", bearer(token)))

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        fun Double.toCents(): Long = Math.round(this / 100.0)

        val available = root.num("availableBalance")
        val cash = root.num("cashBalance")
        val creditLimit = root.num("creditLimit")
        val owed = root.num("outstandingInvoices")

        if (available == null && cash == null) return@parseObj UsageResult(error = "未返回余额字段")

        val metrics = mutableListOf<Metric>()
        available?.let {
            metrics += Metric(
                label = "余额",
                balanceMinor = it.toCents(),
                currency = "USD",
                note = creditLimit?.takeIf { c -> c > 0 }?.let { c -> "信用额度 $${"%.2f".format(c / 10000.0)}" },
            )
        }
        if (cash != null && available != null && cash != available) {
            metrics += Metric(label = "充值余额", balanceMinor = cash.toCents(), currency = "USD")
        }
        if (owed != null && owed > 0) {
            metrics += Metric(label = "欠费", balanceMinor = owed.toCents(), currency = "USD", note = "待结算")
        }

        UsageResult(balanceMinor = metrics.first().balanceMinor, currency = "USD", metrics = metrics)
    }
}
