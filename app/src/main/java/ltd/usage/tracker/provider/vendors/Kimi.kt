package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.UsageResult

/** Kimi / Moonshot：余额查询。 */
internal object KimiVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.moonshot.cn/v1/users/me/balance", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = obj(body)
        val data = (root["data"] as? JsonObject) ?: root
        val available = data["available_balance"]?.jsonPrimitive?.content?.toDoubleOrNull()
        return UsageResult(
            balanceMinor = available?.let { Math.round(it * 100) },
            currency = "CNY",
        )
    }
}
