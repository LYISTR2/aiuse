package ltd.usage.tracker.provider

import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.UsageResult
import ltd.usage.tracker.provider.vendors.AnthropicVendor
import ltd.usage.tracker.provider.vendors.ChatGptSubscriptionVendor
import ltd.usage.tracker.provider.vendors.ClaudeSubscriptionVendor
import ltd.usage.tracker.provider.vendors.CommandCodeVendor
import ltd.usage.tracker.provider.vendors.OpenCodeGoVendor
import ltd.usage.tracker.provider.vendors.DeepInfraVendor
import ltd.usage.tracker.provider.vendors.DeepSeekVendor
import ltd.usage.tracker.provider.vendors.KimiVendor
import ltd.usage.tracker.provider.vendors.MistralVendor
import ltd.usage.tracker.provider.vendors.NovitaVendor
import ltd.usage.tracker.provider.vendors.OpenAiVendor
import ltd.usage.tracker.provider.vendors.OpenRouterVendor
import ltd.usage.tracker.provider.vendors.SiliconFlowVendor
import ltd.usage.tracker.provider.vendors.StepFunVendor
import ltd.usage.tracker.provider.vendors.Vendor
import ltd.usage.tracker.provider.vendors.XaiVendor
import ltd.usage.tracker.provider.vendors.ZhipuVendor
import ltd.usage.tracker.provider.vendors.redact

/**
 * 各服务商用量查询的入口。每家的请求与解析见 provider/vendors/ 下对应文件。
 *
 * 重要说明：这些接口大多**不是官方公开 API**，是从各家客户端行为推断的。
 * 对方随时可能改动，届时需要同步修改。这是这类应用的根本脆弱点。
 *
 * 所有请求都是「凭据直连服务商」，不经过任何第三方服务器。
 */
object UsageFetcher {

    /** Provider → 实现。新增服务商时在这里登记；VendorDispatchTest 会检查没有漏登。 */
    internal val vendors: Map<Provider, Vendor> = mapOf(
        Provider.DEEPSEEK to DeepSeekVendor,
        Provider.ANTHROPIC to AnthropicVendor,
        Provider.OPENAI to OpenAiVendor,
        Provider.KIMI to KimiVendor,
        Provider.ZHIPU to ZhipuVendor,
        Provider.XAI to XaiVendor,
        Provider.MISTRAL to MistralVendor,
        Provider.OPENROUTER to OpenRouterVendor,
        Provider.SILICONFLOW to SiliconFlowVendor,
        Provider.NOVITA to NovitaVendor,
        Provider.DEEPINFRA to DeepInfraVendor,
        Provider.STEPFUN to StepFunVendor,
        Provider.CLAUDE_SUB to ClaudeSubscriptionVendor,
        Provider.CHATGPT_SUB to ChatGptSubscriptionVendor,
        Provider.OPENCODE_GO to OpenCodeGoVendor,
        Provider.COMMAND_CODE to CommandCodeVendor,
    )

    /** 任何异常都转成该账号的 error，并脱敏 token。 */
    fun fetch(provider: Provider, token: String): UsageResult = try {
        vendors.getValue(provider).fetch(token)
    } catch (e: Exception) {
        UsageResult(error = redact(e.message ?: e.javaClass.simpleName, token))
    }

    /** 带完整凭据的版本（ChatGPT 订阅要 accountId）；同步走这个，异常处理与上面一致。 */
    fun fetch(provider: Provider, credential: Credential): UsageResult = try {
        vendors.getValue(provider).fetch(credential)
    } catch (e: Exception) {
        UsageResult(error = redact(e.message ?: e.javaClass.simpleName, credential.token))
    }
}
