package ltd.usage.tracker.data

import kotlinx.serialization.Serializable

/**
 * 支持的 AI 服务商。
 *
 * [pickable] 为 false 的条目不出现在「服务商」下拉里：订阅登录账号（CLAUDE_SUB / CHATGPT_SUB）
 * 从 Anthropic / OpenAI 的「登录订阅」入口创建，存储上用独立的 id，
 * 这样老账号（anthropic / openai + API Key）的数据和行为一点不变。
 */
enum class Provider(val id: String, val displayName: String, val pickable: Boolean = true) {
    DEEPSEEK("deepseek", "DeepSeek"),
    ANTHROPIC("anthropic", "Claude / Anthropic"),
    OPENAI("openai", "OpenAI"),
    KIMI("kimi", "Kimi / Moonshot"),
    ZHIPU("zhipu", "智谱 GLM"),
    XAI("xai", "xAI Grok"),
    MISTRAL("mistral", "Mistral AI"),
    OPENROUTER("openrouter", "OpenRouter"),
    SILICONFLOW("siliconflow", "硅基流动"),
    NOVITA("novita", "Novita AI"),
    DEEPINFRA("deepinfra", "DeepInfra"),
    STEPFUN("stepfun", "阶跃星辰"),
    // 1.8.0 新增。新条目只能追加在末尾：VendorDispatchTest 的 1.6.1 金样按顺序比对前 12 家
    /** Claude Pro / Max 订阅（OAuth 登录），凭据含 refresh token。 */
    CLAUDE_SUB("claude_sub", "Claude 订阅", pickable = false),
    /** ChatGPT Plus / Pro 订阅（Codex OAuth 登录），accountId 存 chatgpt_account_id。 */
    CHATGPT_SUB("chatgpt_sub", "ChatGPT 订阅", pickable = false),
    /** OpenCode Go 订阅，API Key。 */
    OPENCODE_GO("opencode_go", "OpenCode Go"),
    /** Command Code（GOAT 等套餐），API Key。 */
    COMMAND_CODE("command_code", "Command Code"),
    ;

    companion object {
        fun from(id: String): Provider? = entries.firstOrNull { it.id == id }
    }
}

/**
 * 一条凭据。
 *
 * 注意：[toString] 已脱敏，绝不输出 token 原文。
 * 参考实现直接打印 token，属于缺陷，这里修复。
 */
data class Credential(
    val token: String,
    val refreshToken: String? = null,
    val accountId: String? = null,
    val expiresAt: Long? = null,
) {
    override fun toString(): String {
        val t = if (token.isEmpty()) "<empty>" else "***(${token.length})"
        val r = if (refreshToken.isNullOrEmpty()) "null" else "***(${refreshToken.length})"
        return "Credential(token=$t, refreshToken=$r, accountId=$accountId, expiresAt=$expiresAt)"
    }
}

/** 账号元数据，明文存储，不含任何密钥。 */
@Serializable
data class StoredAccount(
    val id: String,
    val providerId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 一条独立指标。
 *
 * 有些服务商同时提供多个额度（例如 Mistral 的 API 预算与 Vibe 预算），
 * 用 [label] 区分，[balanceMinor] 为该指标的剩余额度。
 */
@Serializable
data class Metric(
    val label: String,
    val balanceMinor: Long? = null,
    val currency: String? = null,
    /** 已用百分比（0-100），部分服务商只给百分比不给金额。 */
    val usedPercent: Double? = null,
    /**
     * 总额度（与 [balanceMinor] 同币种同单位）。
     *
     * 有它才能画「已用 / 未用」进度条：已用 = quota - balance。
     * 只给余额不给上限的服务商（如 DeepSeek、硅基流动）留空，UI 不画进度条，
     * 而不是拿余额瞎猜一个分母。
     */
    val quotaMinor: Long? = null,
    val note: String? = null,
)

/** 一次用量快照。 */
@Serializable
data class UsageSnapshot(
    val accountId: String,
    val capturedAt: Long,
    val balanceMinor: Long? = null,
    val currency: String? = null,
    val usedTokens: Long? = null,
    val quotaTokens: Long? = null,
    val note: String? = null,
    val error: String? = null,
    /** 多条指标；为空时回退到上面的单值字段。 */
    val metrics: List<Metric> = emptyList(),
)

/** 服务商返回的统一结果。 */
data class UsageResult(
    val balanceMinor: Long? = null,
    val currency: String? = null,
    val usedTokens: Long? = null,
    val quotaTokens: Long? = null,
    val note: String? = null,
    val error: String? = null,
    /** 多条指标；非空时 UI 优先展示它。 */
    val metrics: List<Metric> = emptyList(),
)

/**
 * 结果 → 快照的唯一映射。
 *
 * 不把 [UsageResult] 嵌进 [UsageSnapshot]：那样 snapshots.json 会多一层嵌套，老数据读不出来。
 * 两边字段保持平铺，只在这里一处对齐；新增字段时两个类和这里一起改。
 */
fun UsageResult.toSnapshot(accountId: String, capturedAt: Long) = UsageSnapshot(
    accountId = accountId,
    capturedAt = capturedAt,
    balanceMinor = balanceMinor,
    currency = currency,
    usedTokens = usedTokens,
    quotaTokens = quotaTokens,
    note = note,
    error = error,
    metrics = metrics,
)
