package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * 硅基流动：GET /v1/user/info 返回账户余额。
 *
 * 字段在 data 下：totalBalance（总余额）、chargeBalance（充值余额）、
 * balance（赠金余额）、status（normal / arrears 等）。
 * 金额是字符串形式的「元」。
 */
internal object SiliconFlowVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.siliconflow.cn/v1/user/info", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse {
            return UsageResult(error = "响应不是合法 JSON")
        }
        val data = (root["data"] as? JsonObject)
            ?: return UsageResult(error = root["message"]?.jsonPrimitive?.content ?: "响应缺少 data")

        val total = data.num("totalBalance")
        val charge = data.num("chargeBalance")
        val gift = data.num("balance")
        val status = data["status"]?.jsonPrimitive?.content

        if (total == null && charge == null) {
            return UsageResult(error = "未返回余额字段")
        }

        val metrics = mutableListOf<Metric>()
        (total ?: charge)?.let {
            metrics += Metric(
                label = "余额",
                balanceMinor = Math.round(it * 100),
                currency = "CNY",
                note = status?.takeIf { s -> s != "normal" }?.let { "状态: $it" },
            )
        }
        // 充值余额与赠金分开报，便于判断哪部分先用完
        if (charge != null && gift != null && gift > 0) {
            metrics += Metric(label = "赠金", balanceMinor = Math.round(gift * 100), currency = "CNY")
        }

        return UsageResult(
            balanceMinor = metrics.first().balanceMinor,
            currency = "CNY",
            metrics = metrics,
        )
    }
}
