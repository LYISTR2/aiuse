package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.UsageResult

/** OpenAI：/v1/me 验证凭据有效，并给出组织信息。 */
internal object OpenAiVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.openai.com/v1/me", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = obj(body)
        val orgName = (root["orgs"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?.get("title")?.jsonPrimitive?.content
        return UsageResult(note = orgName?.let { "组织: $it" } ?: "已认证")
    }
}
