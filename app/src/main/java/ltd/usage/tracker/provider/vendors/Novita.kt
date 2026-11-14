package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * Novita AI：GET /openapi/v1/billing/balance/detail。
 *
 * 单位是 1/10000 USD（10000 = $1.00），字段为字符串。
 * availableBalance 可用额度、cashBalance 充值余额、outstandingInvoices 欠费。
 */
internal object NovitaVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.novita.ai/openapi/v1/billing/balance/detail", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse {
            return UsageResult(error = "响应不是合法 JSON")
        }

        // 1/10000 USD → 分：除以 100 即得「分」
        fun toMinor(v: Double?): Long? = v?.let { Math.round(it / 100.0) }

        val available = root.num("availableBalance")
        val cash = root.num("cashBalance")
        val creditLimit = root.num("creditLimit")
        val owed = root.num("outstandingInvoices")

        if (available == null && cash == null) {
            return UsageResult(error = "未返回余额字段")
        }

        val metrics = mutableListOf<Metric>()
        toMinor(available)?.let {
            metrics += Metric(
                label = "余额",
                balanceMinor = it,
                currency = "USD",
                note = creditLimit?.takeIf { c -> c > 0 }
                    ?.let { c -> "信用额度 $${"%.2f".format(c / 10000.0)}" },
            )
        }
        if (cash != null && available != null && cash != available) {
            toMinor(cash)?.let { metrics += Metric(label = "充值余额", balanceMinor = it, currency = "USD") }
        }
        if (owed != null && owed > 0) {
            metrics += Metric(
                label = "欠费",
                balanceMinor = toMinor(owed),
                currency = "USD",
                note = "待结算",
            )
        }

        return UsageResult(
            balanceMinor = metrics.first().balanceMinor,
            currency = "USD",
            metrics = metrics,
        )
    }
}
