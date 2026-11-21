package ltd.usage.tracker.provider.vendors

import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/** 阶跃星辰：GET /v1/accounts 返回账户余额。 */
internal object StepFunVendor : Vendor {

    override fun fetch(token: String): UsageResult =
        parse(get("https://api.stepfun.com/v1/accounts", bearer(token)))

    override fun parse(body: String): UsageResult = parseObj(body) { root ->
        val balance = root.num("balance") ?: return@parseObj UsageResult(error = "未返回余额字段")
        val cash = root.num("total_cash_balance")
        val voucher = root.num("total_voucher_balance")
        val note = when (root.str("type")) {
            "prepaid" -> "预付费"
            "postpaid" -> "后付费"
            else -> null
        }

        val metrics = mutableListOf(Metric(label = "余额", balanceMinor = balance.toMinor(), currency = "CNY", note = note))
        if (voucher != null && voucher > 0) {
            metrics += Metric(label = "赠金", balanceMinor = voucher.toMinor(), currency = "CNY")
        }
        if (cash != null && cash > 0) {
            metrics += Metric(label = "累计充值", balanceMinor = cash.toMinor(), currency = "CNY")
        }
        UsageResult(balanceMinor = metrics.first().balanceMinor, currency = "CNY", metrics = metrics)
    }
}
