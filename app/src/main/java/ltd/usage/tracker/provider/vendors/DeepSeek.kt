package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.UsageResult

/** DeepSeek：/user/balance，余额以「元」返回。 */
internal object DeepSeekVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.deepseek.com/user/balance", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = obj(body)
        val first = (root["balance_infos"] as? JsonArray)?.firstOrNull()?.jsonObject
        val total = first?.get("total_balance")?.jsonPrimitive?.content?.toDoubleOrNull()
        val currency = first?.get("currency")?.jsonPrimitive?.content
        val available = root["is_available"]?.jsonPrimitive?.content
        return UsageResult(
            balanceMinor = total?.let { Math.round(it * 100) },
            currency = currency ?: "CNY",
            note = available?.let { "可用: $it" },
        )
    }
}
