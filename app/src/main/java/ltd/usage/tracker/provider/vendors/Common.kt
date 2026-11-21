package ltd.usage.tracker.provider.vendors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.UsageResult
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    /** 需要凭据里其他字段的服务商（如 ChatGPT 订阅要 accountId）重写这个；默认只用 token。 */
    fun fetch(credential: Credential): UsageResult = fetch(credential.token)
}

/** 全局共享的 OkHttpClient，测试可替换以拦截核对 URL 与请求头。 */
internal var httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

internal val sharedJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal val ACCEPT_JSON = "Accept" to "application/json"

/** 绝大多数服务商的请求头：Bearer + Accept，再追加 [extra]。 */
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

internal fun obj(body: String): JsonObject = sharedJson.parseToJsonElement(body).jsonObject

internal inline fun parseObj(body: String, block: (JsonObject) -> UsageResult): UsageResult {
    val root = runCatching { obj(body) }.getOrElse { return UsageResult(error = "响应不是合法 JSON") }
    return block(root)
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.num(key: String): Double? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

internal fun JsonObject.strictDouble(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? =
    str(key)?.toBooleanStrictOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonElement?.asObj(): JsonObject? = this as? JsonObject

internal fun Double.toMinor(): Long = Math.round(this * 100.0)

private const val MIN_REDACT_LEN = 8

/**
 * 从错误消息里抹掉 token（含截断残片，下限 8 字符避免误伤公共前缀）。
 */
internal fun redact(msg: String, vararg secrets: String?): String {
    var out = msg
    for (token in secrets) {
        if (token.isNullOrEmpty()) continue
        for (len in token.length downTo MIN_REDACT_LEN) {
            out = out.replace(token.take(len), "***")
        }
    }
    return out
}

// ---------------------------------------------------------------- 订阅窗口

/** 解析重置时间：ISO8601（带偏移或 Z）、epoch 秒或 epoch 毫秒（> 1e11 视为毫秒）。 */
internal fun parseResetAt(raw: String?): Long? {
    val t = raw?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    t.toDoubleOrNull()?.let { n -> return if (n > 1e11) n.toLong() else (n * 1000).toLong() }
    return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { Instant.parse(t).toEpochMilli() }.getOrNull()
}

private val RESET_CLOCK_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 重置时间显示为本机时区的「MM-dd HH:mm」。 */
internal fun resetClock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    RESET_CLOCK_FMT.withZone(zone).format(Instant.ofEpochMilli(ms))

/** 订阅窗口指标：只给百分比，note 沿用「已用 N% · … 重置」格式。 */
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

/** 订阅登录账号的 401 提示替换为「登录已失效」。 */
internal inline fun <T> oauthGet(block: () -> T): T = try {
    block()
} catch (e: IllegalStateException) {
    if (e.message?.startsWith("HTTP 401") == true) error("登录已失效，请删除后重新登录（401）") else throw e
}
