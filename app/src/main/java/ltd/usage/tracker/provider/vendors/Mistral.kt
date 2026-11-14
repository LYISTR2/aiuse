package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult

/**
 * Mistral AI：GET /v1/billing/budget 返回本计费周期的 API 与 Vibe 预算使用率。
 *
 * 该端点使用 `DashboardContextAuth`（即 `x-api-key` 头），普通 API Key 即可访问，
 * 不需要 Enterprise 的 Admin Key —— 后者只有 /v1/admin 前缀的端点才需要。
 */
internal object MistralVendor : Vendor {

    // Mistral 使用 x-api-key 头而非 Bearer（原 getMistral，已并入通用 get）
    override fun fetch(token: String): UsageResult = parse(
        get("https://api.mistral.ai/v1/billing/budget", listOf("x-api-key" to token, ACCEPT_JSON))
    )

    override fun parse(body: String): UsageResult {
        val root = obj(body)

        // 响应同时含 api_budget 与 vibe_budget 两个独立额度，两者都要展示
        val api = parseBudget(root["api_budget"] as? JsonObject, "API")
        val vibe = parseBudget(root["vibe_budget"] as? JsonObject, "Vibe")

        val metrics = listOfNotNull(api, vibe)
        if (metrics.isEmpty()) {
            return UsageResult(error = "未返回预算信息")
        }

        // 主数值取 API 预算（若缺失则取 Vibe），其余在 metrics 里逐条展示
        val primary = metrics.first()
        return UsageResult(
            balanceMinor = primary.balanceMinor,
            currency = primary.currency,
            metrics = metrics,
        )
    }

    /**
     * 解析单个预算对象。
     *
     * 字段（来自官方 OpenAPI 的 BudgetDetail）：
     *   usage_percentage  已用百分比 0-100
     *   initial_budget    本周期预算额度
     *   currency          ISO 4217
     *   reset_at          周期重置时间
     *   payg_enabled      是否开启按量超支（仅 Vibe 有意义）
     *
     * 剩余额度 = initial_budget × (1 - usage_percentage / 100)
     */
    private fun parseBudget(o: JsonObject?, label: String): Metric? {
        if (o == null) return null
        val percent = o["usage_percentage"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val initial = o["initial_budget"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val currency = o["currency"]?.jsonPrimitive?.content
        val payg = o["payg_enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()

        // 预算为 0 或未配置时，不展示金额，只展示百分比
        val remainingMinor = if (initial != null && percent != null && initial > 0) {
            Math.round(initial * (1.0 - percent / 100.0) * 100)
        } else null

        val note = buildList {
            percent?.let { add("已用 ${it.toInt()}%") }
            if (payg == true) add("按量超支已开启")
        }.joinToString(" · ").ifBlank { null }

        return Metric(
            label = label,
            balanceMinor = remainingMinor,
            currency = currency,
            usedPercent = percent,
            quotaMinor = if (initial != null && initial > 0) Math.round(initial * 100) else null,
            note = note,
        )
    }
}
