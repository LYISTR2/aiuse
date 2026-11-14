package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * ChatGPT 订阅（Plus / Pro，Codex OAuth 登录）：Codex 的 5 小时窗口与每周额度。
 *
 * 端点：GET https://chatgpt.com/backend-api/wham/usage
 *   （openai/codex b1573f1 codex-rs/backend-client/src/client/rate_limit_resets.rs）
 * 头：Authorization: Bearer <access token>、ChatGPT-Account-Id（codex-rs/http-client/src/openai_headers.rs）
 * 响应：rate_limit.primary_window / secondary_window，各有 used_percent、limit_window_seconds、reset_at（epoch 秒）。
 * 按 limit_window_seconds 判断哪个是 5 小时（18000）、哪个是每周（604800），不按 primary/secondary 的位置猜：
 * 团队版等账号可能只有每周窗口，放在 primary 里。
 * 未公开文档，随时可能变。
 */
internal object ChatGptSubscriptionVendor : Vendor {

    const val URL = "https://chatgpt.com/backend-api/wham/usage"

    override fun fetch(token: String): UsageResult = fetch(Credential(token = token))

    override fun fetch(credential: Credential): UsageResult {
        val headers = bearer(credential.token, "originator" to "codex_cli_rs", "User-Agent" to "codex_cli_rs") +
            listOfNotNull(credential.accountId?.takeIf { it.isNotBlank() }?.let { "ChatGPT-Account-Id" to it })
        return parse(oauthGet { get(URL, headers) })
    }

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse { return UsageResult(error = "响应不是合法 JSON") }
        val rl = root["rate_limit"] as? JsonObject
        val windows = listOfNotNull(rl?.get("primary_window") as? JsonObject, rl?.get("secondary_window") as? JsonObject)
            .mapNotNull { w ->
                val pct = (w["used_percent"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return@mapNotNull null
                val secs = (w["limit_window_seconds"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()
                val reset = parseResetAt((w["reset_at"] as? JsonPrimitive)?.contentOrNull)
                    ?: (w["reset_after_seconds"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
                        ?.let { System.currentTimeMillis() + (it * 1000).toLong() }
                Triple(label(secs), pct, reset)
            }
        if (windows.isEmpty()) return UsageResult(error = "该账号未返回订阅额度")
        val metrics: List<Metric> = windows.map { (label, pct, reset) -> windowMetric(label, pct, reset) }
        val plan = (root["plan_type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        return UsageResult(metrics = metrics, note = plan?.let { "套餐: $it" })
    }

    /** 窗口长度 → 标签。18000 秒 = 5 小时，604800 秒 = 每周；其他按小时 / 天写出来。 */
    private fun label(seconds: Long?): String = when {
        seconds == null -> "额度"
        seconds == 18000L -> "5 小时"
        seconds == 604800L -> "每周"
        seconds % 86400L == 0L -> "${seconds / 86400L} 天"
        seconds % 3600L == 0L -> "${seconds / 3600L} 小时"
        else -> "额度"
    }
}
