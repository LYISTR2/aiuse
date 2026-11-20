package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.UsageResult

/** Anthropic：OAuth usage，返回 5 小时窗口利用率。 */
internal object AnthropicVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.anthropic.com/api/oauth/usage", bearer(token, "anthropic-beta" to "oauth-2025-04-20")))

    override fun parse(body: String): UsageResult {
        val root = obj(body)
        val utilization = root["five_hour"]?.jsonObject
            ?.get("utilization")?.jsonPrimitive?.doubleOrNull
        return UsageResult(
            // utilization 已是 0–100，不能再乘 100
            note = utilization?.let { "5h 窗口已用 ${it.toInt()}%" },
        )
    }
}
