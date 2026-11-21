package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/** xAI Grok：通过 CLI 聊天代理读取订阅用量。 */
internal object XaiVendor : Vendor {

    override fun fetch(token: String): UsageResult = parse(
        get(
            "https://cli-chat-proxy.grok.com/v1/billing?format=credits",
            listOf(
                "Authorization" to "Bearer $token",
                "x-xai-token-auth" to "xai-grok-cli",
                ACCEPT_JSON,
            ),
        )
    )

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val config = root.obj("config") ?: root
        val metrics = mutableListOf<Metric>()

        val usedPercent = config.num("creditUsagePercent")
        val currentPeriod = config.obj("currentPeriod")
        val period = currentPeriod?.str("type")
        val resetAt = currentPeriod?.str("end") ?: config.str("billingPeriodEnd")

        if (usedPercent != null && usedPercent in 0.0..100.0) {
            val periodLabel = when {
                period?.contains("WEEKLY", true) == true -> "SuperGrok 周额度"
                period?.contains("MONTHLY", true) == true -> "SuperGrok 月额度"
                else -> "SuperGrok 额度"
            }
            metrics += Metric(
                label = periodLabel,
                usedPercent = usedPercent,
                note = buildString {
                    append("已用 ${usedPercent.toInt()}%")
                    resetAt?.let { append(" · ").append(shortDate(it)).append(" 重置") }
                },
            )
        }

        config.arr("productUsage")?.forEach { el ->
            val o = el.asObj() ?: return@forEach
            val product = o.str("product") ?: return@forEach
            val pct = o.num("usagePercent") ?: return@forEach
            metrics += Metric(
                label = productLabel(product),
                usedPercent = pct,
                note = "已用 ${pct.toInt()}%",
            )
        }

        val prepaid = config.obj("prepaidBalance")?.num("val")
        val onDemandCap = config.obj("onDemandCap")?.num("val")
        val onDemandUsed = config.obj("onDemandUsed")?.num("val")

        if (prepaid != null && prepaid > 0) {
            metrics += Metric(label = "预付", balanceMinor = Math.round(prepaid), currency = "USD")
        }
        if (onDemandCap != null && onDemandCap > 0) {
            metrics += Metric(
                label = "按量",
                balanceMinor = Math.round(onDemandCap - (onDemandUsed ?: 0.0)),
                currency = "USD",
                quotaMinor = Math.round(onDemandCap),
            )
        }

        if (metrics.isEmpty()) UsageResult(error = "该账号未返回用量数据")
        else UsageResult(metrics = metrics)
    }

    private fun productLabel(product: String): String =
        product.replace(Regex("""(?<=\p{Ll})(?=\p{Lu})"""), " ")

    private fun shortDate(iso: String): String =
        if (iso.length >= 10) iso.substring(5, 10) else iso
}
