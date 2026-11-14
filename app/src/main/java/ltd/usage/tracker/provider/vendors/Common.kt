package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 各服务商共用的 HTTP / JSON / 脱敏工具。
 *
 * 每个服务商一个文件，实现 [Vendor]：fetch 负责请求，parse 是纯函数便于单测。
 */
internal interface Vendor {
    /** 发请求并解析；异常向上抛，由 [ltd.usage.tracker.provider.UsageFetcher.fetch] 统一转成 error。 */
    fun fetch(token: String): UsageResult

    /** 解析响应体，不碰网络。 */
    fun parse(body: String): UsageResult

    /**
     * 需要凭据里其他字段的服务商（如 ChatGPT 订阅要 accountId）重写这个；默认只用 token。
     * 1.8.0 新增，老服务商不受影响。
     */
    fun fetch(credential: Credential): UsageResult = fetch(credential.token)
}

/** 测试可替换（加拦截器核对 URL 与请求头），生产不改。 */
internal var httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

private val json = Json { ignoreUnknownKeys = true; isLenient = true }

internal val ACCEPT_JSON = "Accept" to "application/json"

/** 绝大多数服务商的请求头：Bearer + Accept，再追加 [extra]（顺序与 1.6.1 一致）。 */
internal fun bearer(token: String, vararg extra: Pair<String, String>): List<Pair<String, String>> =
    listOf("Authorization" to "Bearer $token", ACCEPT_JSON) + extra

/** GET，按给定顺序设置请求头；非 2xx 抛出「HTTP 码: 响应体前 200 字符」。 */
internal fun get(url: String, headers: List<Pair<String, String>>): String {
    val builder = Request.Builder().url(url).get()
    headers.forEach { (k, v) -> builder.header(k, v) }
    httpClient.newCall(builder.build()).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) error("HTTP ${resp.code}: ${body.take(200)}")
        return body
    }
}

internal fun obj(body: String): JsonObject = json.parseToJsonElement(body).jsonObject

/**
 * 取数值字段，兼容字符串与数字两种 JSON 表示。
 *
 * 各家 API 对金额的编码并不统一：Novita 用字符串（"10000"），
 * Mistral 用数字（100.0），SiliconFlow 用字符串。统一在这里收口，
 * 避免每个解析函数各写一份 toDoubleOrNull。
 */
internal fun JsonObject.num(key: String): Double? =
    this[key]?.jsonPrimitive?.content?.toDoubleOrNull()

private const val MIN_REDACT_LEN = 8

/**
 * 从错误消息里抹掉 token。
 *
 * 错误消息会带响应体前 200 字符，而部分服务商在鉴权失败时会把
 * 收到的 key 回显在响应体里。这条路径会把 token 一路带到 UI 和
 * 快照文件，所以必须在出口处脱敏。
 */
internal fun redact(msg: String, token: String): String {
    if (token.isEmpty()) return msg
    var out = msg
    // 从完整 token 到较短的截断片段逐级替换：响应体只取前 200 字符时，
    // token 可能被切成任意长度的一段，只替换完整串会漏掉残片。
    // 下限 8 字符，避免把 "sk-live-" 这类公共前缀误伤进无关文本。
    for (len in token.length downTo MIN_REDACT_LEN) {
        out = out.replace(token.take(len), "***")
    }
    return out
}

// ---------------------------------------------------------------- 订阅窗口（1.8.0）

/** 解析重置时间：ISO8601（带偏移或 Z）、epoch 秒或 epoch 毫秒（> 1e11 视为毫秒）。解析不了返回 null。 */
internal fun parseResetAt(raw: String?): Long? {
    val t = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    t.toDoubleOrNull()?.let { n -> return if (n > 1e11) n.toLong() else (n * 1000).toLong() }
    return runCatching { java.time.OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(t).toEpochMilli() }.getOrNull()
}

/** 重置时间显示为本机时区的「MM-dd HH:mm」：5 小时窗口只写日期不够用。 */
internal fun resetClock(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(java.time.Instant.ofEpochMilli(ms))

/**
 * 订阅窗口指标：只给百分比，note 沿用 xAI 的「已用 N% · … 重置」格式，
 * 这样右上角「剩 x%」取最紧的一项、条下说明的去重逻辑都不用改。
 */
internal fun windowMetric(label: String, usedPercent: Double, resetAtMs: Long?): Metric {
    val pct = usedPercent.coerceIn(0.0, 100.0)
    return Metric(
        label = label,
        usedPercent = pct,
        note = buildString {
            append("已用 ${pct.toInt()}%")
            resetAtMs?.let { append(" · ").append(resetClock(it)).append(" 重置") }
        },
    )
}

/**
 * 订阅登录账号的 401 不是「API Key 无效」，而是登录失效（refresh token 也被拒或已撤销）。
 * 换成对应提示，避免界面上出现「API Key 无效」误导用户去找 Key。
 */
internal inline fun <T> oauthGet(block: () -> T): T = try {
    block()
} catch (e: IllegalStateException) {
    if (e.message?.startsWith("HTTP 401") == true) error("登录已失效，请删除后重新登录（401）") else throw e
}
