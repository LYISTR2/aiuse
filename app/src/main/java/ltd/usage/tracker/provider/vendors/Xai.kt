package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * xAI Grok：通过 CLI 聊天代理读取订阅用量。
 *
 * 端点：GET https://cli-chat-proxy.grok.com/v1/billing?format=credits
 * 认证：Bearer <OAuth access token> + 头 x-xai-token-auth: xai-grok-cli
 *
 * 这是「浏览器授权登录后看用量」的路径。OAuth 用设备码流程拿到（见 XaiOAuth），
 * 该 client 的 scope 不含 billing:read，因此不能走 Management API，
 * 只能走 CLI 代理 —— 这也是 Grok CLI 自己用的接口。
 */
internal object XaiVendor : Vendor {

    // 头顺序与 1.6.1 一致：Authorization → x-xai-token-auth → Accept
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

    /**
     * 解析 xAI billing 响应。抽成纯函数以便单元测试。
     * 输入为空字符串或结构不符时返回 error，绝不编造数字。
     */
    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse {
            return UsageResult(error = "响应不是合法 JSON")
        }

        // 真实响应把数据包在 config 里（裸 fetch 只返回 config，tier 由 /v1/settings 补）
        val config = (root["config"] as? JsonObject) ?: root

        val metrics = mutableListOf<Metric>()

        // 1) 总池：creditUsagePercent 是「已用」百分比，剩余 = 100 - used
        val usedPercent = config["creditUsagePercent"]
            ?.jsonPrimitive?.content?.toDoubleOrNull()
        val period = (config["currentPeriod"] as? JsonObject)
            ?.get("type")?.jsonPrimitive?.content
        val resetAt = (config["currentPeriod"] as? JsonObject)
            ?.get("end")?.jsonPrimitive?.content
            ?: config["billingPeriodEnd"]?.jsonPrimitive?.content

        if (usedPercent != null && usedPercent in 0.0..100.0) {
            val periodLabel = when {
                period?.contains("WEEKLY", true) == true -> "SuperGrok 周额度"
                period?.contains("MONTHLY", true) == true -> "SuperGrok 月额度"
                else -> "SuperGrok 额度"
            }
            metrics += Metric(
                label = periodLabel,
                balanceMinor = null,
                currency = null,
                usedPercent = usedPercent,
                note = buildString {
                    append("已用 ${usedPercent.toInt()}%")
                    resetAt?.let { append(" · ").append(shortDate(it)).append(" 重置") }
                },
            )
        }

        // 2) 分产品用量：只展示接口返回的 productUsage[]，label 按代号驼峰拆词，不补缺失项
        (config["productUsage"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val product = o["product"]?.jsonPrimitive?.content ?: return@forEach
            val pct = o["usagePercent"]?.jsonPrimitive?.content?.toDoubleOrNull()
            if (pct != null) {
                metrics += Metric(
                    label = productLabel(product),
                    balanceMinor = null,
                    usedPercent = pct,
                    note = "已用 ${pct.toInt()}%",
                )
            }
        }

        // 3) 预付余额与按量额度（{val: ...} 结构，单位 USD 分）
        val prepaid = (config["prepaidBalance"] as? JsonObject)
            ?.get("val")?.jsonPrimitive?.content?.toDoubleOrNull()
        val onDemandCap = (config["onDemandCap"] as? JsonObject)
            ?.get("val")?.jsonPrimitive?.content?.toDoubleOrNull()
        val onDemandUsed = (config["onDemandUsed"] as? JsonObject)
            ?.get("val")?.jsonPrimitive?.content?.toDoubleOrNull()

        if (prepaid != null && prepaid > 0) {
            metrics += Metric(
                label = "预付",
                balanceMinor = Math.round(prepaid),
                currency = "USD",
            )
        }
        if (onDemandCap != null && onDemandCap > 0) {
            val remain = onDemandCap - (onDemandUsed ?: 0.0)
            metrics += Metric(
                label = "按量",
                balanceMinor = Math.round(remain),
                currency = "USD",
                quotaMinor = Math.round(onDemandCap),
            )
        }

        if (metrics.isEmpty()) {
            // 账号未返回任何用量字段（已知情况：部分账号无 creditUsagePercent）
            return UsageResult(error = "该账号未返回用量数据")
        }

        return UsageResult(metrics = metrics)
    }

    /**
     * 接口没有文档，label 只把 product 代号按驼峰拆词，不另起名字。
     * 例如 GrokBuild → Grok Build，GrokChat → Grok Chat；未知代号同一规则。
     */
    private fun productLabel(product: String): String =
        product.replace(Regex("""(?<=\p{Ll})(?=\p{Lu})"""), " ")

    /** ISO8601（如 2026-08-15T01:53:09+00:00）→ MM-dd。 */
    private fun shortDate(iso: String): String =
        if (iso.length >= 10) iso.substring(5, 10) else iso
}
