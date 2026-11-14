package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * Claude 订阅（Pro / Max，OAuth 登录）：5 小时窗口与每周额度。
 *
 * 端点：GET https://api.anthropic.com/api/oauth/usage
 * 头：Authorization: Bearer <OAuth access token>、anthropic-beta: oauth-2025-04-20（Claude Code 2.1.325 同款）
 * 响应：five_hour / seven_day 各有 utilization（0-100）与 resets_at（ISO8601）。
 * 未公开文档，Claude Code 的 /usage 面板用的就是它；字段随时可能变。
 *
 * 与老的 [AnthropicVendor] 是同一个端点，但那边把 utilization 当 0-1 小数处理、只出一句 note，
 * 为了不改变老账号的显示（以及 1.6.1 金样），这里另起一个实现，不去动它。
 */
internal object ClaudeSubscriptionVendor : Vendor {

    const val URL = "https://api.anthropic.com/api/oauth/usage"
    const val USER_AGENT = "claude-code/2.1.325"

    override fun fetch(token: String): UsageResult = parse(
        oauthGet { get(URL, bearer(token, "anthropic-beta" to "oauth-2025-04-20", "User-Agent" to USER_AGENT)) }
    )

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse { return UsageResult(error = "响应不是合法 JSON") }
        val metrics = mutableListOf<Metric>()
        window(root["five_hour"])?.let { (p, r) -> metrics += windowMetric("5 小时", p, r) }
        // 部分账号 seven_day 为空、只有 seven_day_oauth_apps（CodexBar 的兜底顺序）
        (window(root["seven_day"]) ?: window(root["seven_day_oauth_apps"]))
            ?.let { (p, r) -> metrics += windowMetric("每周", p, r) }
        if (metrics.isEmpty()) {
            // Claude Free 账号两个窗口都是 null
            return UsageResult(error = "该账号未返回订阅额度（需要 Pro / Max）")
        }
        return UsageResult(metrics = metrics)
    }

    /** {utilization, resets_at} → (已用百分比, 重置毫秒)；窗口为 null 或没有 utilization 返回 null。 */
    private fun window(el: kotlinx.serialization.json.JsonElement?): Pair<Double, Long?>? {
        val o = el as? JsonObject ?: return null
        val pct = (o["utilization"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
        return pct to parseResetAt((o["resets_at"] as? JsonPrimitive)?.contentOrNull)
    }
}
