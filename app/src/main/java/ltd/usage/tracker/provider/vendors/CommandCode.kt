package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/** Command Code（API Key）：5 小时、每周用量窗口与当月剩余额度。 */
internal object CommandCodeVendor : Vendor {

    const val URL = "https://api.commandcode.ai/alpha/billing/credits"

    override fun fetch(token: String): UsageResult = parse(get(URL, bearer(token)))

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val credits = root.obj("credits")
        val limits = root.obj("windowLimits") ?: credits?.obj("windowLimits")

        val metrics = mutableListOf<Metric>()
        for ((key, label) in listOf("fiveHour" to "5 小时", "weekly" to "每周")) {
            val w = limits?.obj(key) ?: continue
            val cap = w.num("cap")?.takeIf { it > 0 } ?: continue
            val used = w.num("used") ?: continue
            metrics += windowMetric(label, used / cap * 100.0, parseResetAt(w.str("resetAt")))
        }

        if (credits != null) {
            val parts = listOf("monthlyCredits", "purchasedCredits", "freeCredits").mapNotNull { credits.num(it) }
            if (parts.isNotEmpty()) {
                metrics += Metric(
                    label = "剩余额度",
                    balanceMinor = parts.sumOf { it.coerceAtLeast(0.0) }.toMinor(),
                    currency = "USD",
                )
            }
        }
        if (metrics.isEmpty()) UsageResult(error = "该账号未返回用量数据")
        else UsageResult(metrics = metrics)
    }
}
