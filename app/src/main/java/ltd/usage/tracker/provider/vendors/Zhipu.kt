package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.UsageResult

/** 智谱 GLM：额度查询。 */
internal object ZhipuVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://open.bigmodel.cn/api/paas/v4/usage", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = obj(body)
        val note = root["msg"]?.jsonPrimitive?.content
            ?: root["message"]?.jsonPrimitive?.content
        return UsageResult(note = note ?: "已认证")
    }
}
