package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.UsageResult

/** ChatGPT 订阅（Plus / Pro，Codex OAuth 登录）：5 小时窗口与每周额度。 */
internal object ChatGptSubscriptionVendor : Vendor {

    const val URL = "https://chatgpt.com/backend-api/wham/usage"

    override fun fetch(token: String): UsageResult = fetch(Credential(token = token))

    override fun fetch(credential: Credential): UsageResult {
        val headers = bearer(credential.token, "originator" to "codex_cli_rs", "User-Agent" to "codex_cli_rs") +
            listOfNotNull(credential.accountId?.takeIf { it.isNotBlank() }?.let { "ChatGPT-Account-Id" to it })
        return parse(oauthGet { get(URL, headers) })
    }

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val rl = root.obj("rate_limit")
        val metrics = listOfNotNull(rl?.obj("primary_window"), rl?.obj("secondary_window"))
            .mapNotNull { w ->
                val pct = w.strictDouble("used_percent") ?: return@mapNotNull null
                val secs = w.num("limit_window_seconds")?.toLong()
                val reset = parseResetAt(w.str("reset_at"))
                    ?: w.num("reset_after_seconds")?.let { System.currentTimeMillis() + (it * 1000).toLong() }
                windowMetric(label(secs), pct, reset)
            }
        if (metrics.isEmpty()) return@parseObj UsageResult(error = "该账号未返回订阅额度")
        val plan = root.str("plan_type")?.takeIf { it.isNotBlank() }
        UsageResult(metrics = metrics, note = plan?.let { "套餐: $it" })
    }

    private fun label(seconds: Long?): String = when {
        seconds == null -> "额度"
        seconds == 18000L -> "5 小时"
        seconds == 604800L -> "每周"
        seconds % 86400L == 0L -> "${seconds / 86400L} 天"
        seconds % 3600L == 0L -> "${seconds / 3600L} 小时"
        else -> "额度"
    }
}
