package ltd.usage.tracker.ui.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.graphics.shapes.RoundedPolygon
import ltd.usage.tracker.data.Provider

/**
 * 服务商标识形状：四种 MaterialShapes 按服务商固定映射。
 * 订阅账号跟对应的 API 服务商用同一形状，看起来是一家。
 */
enum class ProviderMark { COOKIE, CLOVER, PILL, SUNNY }

fun providerMark(providerId: String): ProviderMark = when (Provider.from(providerId)) {
    Provider.DEEPSEEK, Provider.ZHIPU, Provider.SILICONFLOW, Provider.OPENCODE_GO -> ProviderMark.COOKIE
    Provider.ANTHROPIC, Provider.CLAUDE_SUB, Provider.XAI, Provider.NOVITA, Provider.COMMAND_CODE -> ProviderMark.CLOVER
    Provider.OPENAI, Provider.CHATGPT_SUB, Provider.MISTRAL, Provider.DEEPINFRA -> ProviderMark.PILL
    Provider.KIMI, Provider.OPENROUTER, Provider.STEPFUN -> ProviderMark.SUNNY
    null -> ProviderMark.COOKIE
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun providerPolygon(providerId: String): RoundedPolygon = when (providerMark(providerId)) {
    ProviderMark.COOKIE -> MaterialShapes.Cookie9Sided
    ProviderMark.CLOVER -> MaterialShapes.Clover4Leaf
    ProviderMark.PILL -> MaterialShapes.Pill
    ProviderMark.SUNNY -> MaterialShapes.Sunny
}

/** 取账号名（或服务商名）的第一个字，用作形状里的标识。 */
fun providerLetter(accountName: String, providerName: String): String {
    val s = accountName.trim().ifEmpty { providerName.trim() }
    return s.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}
