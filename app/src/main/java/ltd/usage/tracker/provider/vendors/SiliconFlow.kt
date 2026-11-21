package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/** 硅基流动：GET /v1/user/info 返回账户余额。 */
internal object SiliconFlowVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.siliconflow.cn/v1/user/info", bearer(token)))

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val data = root.obj("data")
            ?: return@parseObj UsageResult(error = root.str("message") ?: "响应缺少 data")

        val total = data.num("totalBalance")
        val charge = data.num("chargeBalance")
        val gift = data.num("balance")
        val status = data.str("status")

        val main = total ?: charge ?: return@parseObj UsageResult(error = "未返回余额字段")
        val metrics = mutableListOf(
            Metric(
                label = "余额",
                balanceMinor = main.toMinor(),
                currency = "CNY",
                note = status?.takeIf { it != "normal" }?.let { "状态: $it" },
            )
        )
        if (charge != null && gift != null && gift > 0) {
            metrics += Metric(label = "赠金", balanceMinor = gift.toMinor(), currency = "CNY")
        }
        UsageResult(balanceMinor = metrics.first().balanceMinor, currency = "CNY", metrics = metrics)
    }
}
