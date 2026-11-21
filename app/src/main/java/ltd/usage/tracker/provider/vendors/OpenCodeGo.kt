package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.UsageResult

/** OpenCode Go 订阅（API Key）：5 小时、每周、每月三个额度窗口。 */
internal object OpenCodeGoVendor : Vendor {

    const val URL = "https://opencode.ai/zen/go/v1/usage"

    override fun fetch(token: String): UsageResult = parse(get(URL, bearer(token)))

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val usage = root.obj("usage") ?: return@parseObj UsageResult(error = "该账号未返回 Go 额度")
        val metrics = listOf("rolling" to "5 小时", "weekly" to "每周", "monthly" to "每月")
            .mapNotNull { (key, label) ->
                val o = usage.obj(key) ?: return@mapNotNull null
                val pct = o.strictDouble("percent") ?: return@mapNotNull null
                windowMetric(label, pct, parseResetAt(o.str("resetsAt")))
            }
        if (metrics.isEmpty()) UsageResult(error = "该账号未返回 Go 额度")
        else UsageResult(metrics = metrics)
    }
}
