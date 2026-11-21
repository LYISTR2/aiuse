package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonElement
import ltd.usage.tracker.data.UsageResult

/** Claude 订阅（Pro / Max，OAuth 登录）：5 小时窗口与每周额度。 */
internal object ClaudeSubscriptionVendor : Vendor {

    const val URL = "https://api.anthropic.com/api/oauth/usage"
    const val USER_AGENT = "claude-code/2.1.325"

    override fun fetch(token: String): UsageResult = parse(
        oauthGet { get(URL, bearer(token, "anthropic-beta" to "oauth-2025-04-20", "User-Agent" to USER_AGENT)) }
    )

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val metrics = buildList {
            window(root["five_hour"])?.let { (p, r) -> add(windowMetric("5 小时", p, r)) }
            (window(root["seven_day"]) ?: window(root["seven_day_oauth_apps"]))
                ?.let { (p, r) -> add(windowMetric("每周", p, r)) }
        }
        if (metrics.isEmpty()) UsageResult(error = "该账号未返回订阅额度（需要 Pro / Max）")
        else UsageResult(metrics = metrics)
    }

    private fun window(el: JsonElement?): Pair<Double, Long?>? {
        val o = el.asObj() ?: return null
        val pct = o.strictDouble("utilization") ?: return null
        return pct to parseResetAt(o.str("resets_at"))
    }
}
