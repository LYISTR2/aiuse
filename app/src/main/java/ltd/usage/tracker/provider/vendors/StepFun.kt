package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * 阶跃星辰：GET /v1/accounts 返回账户余额。
 *
 * 字段：type（prepaid / postpaid）、balance（可用余额）、
 * total_cash_balance（总充值）、total_voucher_balance（总赠送）。
 */
internal object StepFunVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.stepfun.com/v1/accounts", bearer(token)))

    override fun parse(body: String): UsageResult {
        val root = runCatching { obj(body) }.getOrElse {
            return UsageResult(error = "响应不是合法 JSON")
        }

        val balance = root.num("balance")
        val cash = root.num("total_cash_balance")
        val voucher = root.num("total_voucher_balance")
        val type = root["type"]?.jsonPrimitive?.content

        if (balance == null) return UsageResult(error = "未返回余额字段")

        val metrics = mutableListOf<Metric>()
        metrics += Metric(
            label = "余额",
            balanceMinor = Math.round(balance * 100),
            currency = "CNY",
            note = when (type) {
                "prepaid" -> "预付费"
                "postpaid" -> "后付费"
                else -> null
            },
        )
        if (voucher != null && voucher > 0) {
            metrics += Metric(label = "赠金", balanceMinor = Math.round(voucher * 100), currency = "CNY")
        }
        if (cash != null && cash > 0) {
            metrics += Metric(label = "累计充值", balanceMinor = Math.round(cash * 100), currency = "CNY")
        }

        return UsageResult(
            balanceMinor = metrics.first().balanceMinor,
            currency = "CNY",
            metrics = metrics,
        )
    }
}
