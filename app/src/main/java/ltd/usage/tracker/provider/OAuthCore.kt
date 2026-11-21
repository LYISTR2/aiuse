package ltd.usage.tracker.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ltd.usage.tracker.provider.vendors.httpClient
import ltd.usage.tracker.provider.vendors.sharedJson
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** 令牌端点返回的结果。[expiresIn] 单位秒；[idToken] 只有 OpenAI 会给。 */
data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Long,
    val idToken: String? = null,
) {
    override fun toString(): String =
        "OAuthTokens(accessToken=***(${accessToken.length}), refreshToken=${refreshToken?.let { "***(${it.length})" }}, expiresIn=$expiresIn)"
}

/** PKCE 参数。 */
data class Pkce(val verifier: String, val challenge: String, val state: String) {
    companion object {
        private val rnd = SecureRandom()

        private fun b64url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun challengeOf(verifier: String): String =
            b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun generate(): Pkce {
            val verifier = b64url(ByteArray(64).also { rnd.nextBytes(it) })
            val state = b64url(ByteArray(32).also { rnd.nextBytes(it) })
            return Pkce(verifier, challengeOf(verifier), state)
        }
    }

    override fun toString(): String = "Pkce(verifier=***, challenge=$challenge, state=***)"
}

internal object Jwt {
    /** 读 JWT 载荷（不验签），只用于提取账号 id 与过期时间。 */
    fun claims(jwt: String?): JsonObject? = runCatching {
        val part = jwt!!.split('.')[1]
        val padded = part.padEnd((part.length + 3) / 4 * 4, '=')
        sharedJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(padded))).jsonObject
    }.getOrNull()

    fun expSeconds(jwt: String?): Long? = claims(jwt)?.get("exp")?.jsonPrimitive?.longOrNull
}

/** 令牌端点返回非 2xx 时抛出；[message] 已整理成给用户看的中文，不含令牌。 */
class OAuthException(message: String, val status: Int = 0) : Exception(message)

internal object OAuthHttp {
    val json: Json get() = sharedJson

    data class Raw(val code: Int, val body: JsonObject, val text: String) {
        val ok get() = code in 200..299
    }

    fun postForm(url: String, fields: Map<String, String>): Raw =
        post(url, FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build())

    fun postJson(url: String, fields: Map<String, String>): Raw =
        post(
            url,
            JsonObject(fields.mapValues { JsonPrimitive(it.value) }).toString()
                .toRequestBody("application/json".toMediaType()),
        )

    private fun post(url: String, body: RequestBody): Raw {
        val req = Request.Builder().url(url).post(body).header("Accept", "application/json").build()
        httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching { sharedJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
                ?: JsonObject(emptyMap())
            return Raw(resp.code, parsed, text)
        }
    }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun JsonObject.requireStr(key: String): String = str(key) ?: error("缺少字段: $key")

    fun parseTokens(r: Raw, defaultExpiresIn: (accessToken: String) -> Long): OAuthTokens {
        if (!r.ok) throw error(r)
        val access = r.body.str("access_token") ?: throw OAuthException("令牌响应缺少 access_token", r.code)
        return OAuthTokens(
            accessToken = access,
            refreshToken = r.body.str("refresh_token"),
            expiresIn = r.body.str("expires_in")?.toDoubleOrNull()?.toLong() ?: defaultExpiresIn(access),
            idToken = r.body.str("id_token"),
        )
    }

    fun error(r: Raw): OAuthException {
        val e = r.body["error"]
        val code = (e as? JsonPrimitive)?.contentOrNull ?: (e as? JsonObject)?.str("type")
        val desc = r.body.str("error_description") ?: (e as? JsonObject)?.str("message")
        val human = when (code) {
            "invalid_grant" -> "授权已失效，请重新登录"
            "invalid_request" -> "授权请求无效（invalid_request）"
            else -> null
        }
        return OAuthException(
            human ?: buildString {
                append("登录失败 (HTTP ${r.code})")
                code?.let { append(" · ").append(it) }
                desc?.let { append(" · ").append(it.take(100)) }
            },
            r.code,
        )
    }
}

/** 通用设备码轮询步进结果，供 [XaiLoginFlow] 与 [CodexLoginFlow] 共用。 */
internal sealed interface DevicePollStep<out R> {
    data class Done<R>(val result: R) : DevicePollStep<R>
    data object Pending : DevicePollStep<Nothing>
    data object SlowDown : DevicePollStep<Nothing>
    data class Failed(val message: String) : DevicePollStep<Nothing>
}

/**
 * 共享设备码登录引擎：申请设备码 → 发 CodeReady → 按 interval 轮询 → 完成 / 失败 / 超时。
 * 统一处理 CancellationException 透传与单次网络异常忽略。
 */
internal fun <D, R, E> deviceCodeFlow(
    now: () -> Long,
    requestDeviceCode: () -> D,
    expiresInSeconds: (D) -> Int,
    intervalSeconds: (D) -> Int,
    onCodeReady: (D) -> E,
    onAuthorized: (R) -> E,
    onFailed: (String) -> E,
    formatError: (prefix: String, Throwable) -> String = { p, e ->
        if (e is OAuthException) e.message.orEmpty() else "$p: ${e.message ?: e.javaClass.simpleName}"
    },
    poll: (D) -> DevicePollStep<R>,
): Flow<E> = flow {
    val device = withContext(Dispatchers.IO) { runCatching { requestDeviceCode() } }
        .getOrElse { e ->
            if (e is CancellationException) throw e
            emit(onFailed(formatError("获取设备码失败", e)))
            return@flow
        }
    emit(onCodeReady(device))

    val deadline = now() + expiresInSeconds(device) * 1000L
    var interval = intervalSeconds(device) * 1000L
    while (now() < deadline) {
        delay(interval)
        val step = withContext(Dispatchers.IO) {
            runCatching { poll(device) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }
        when (step) {
            is DevicePollStep.Done -> {
                emit(onAuthorized(step.result))
                return@flow
            }
            is DevicePollStep.Pending, null -> Unit
            is DevicePollStep.SlowDown -> interval += 5000
            is DevicePollStep.Failed -> {
                emit(onFailed(step.message))
                return@flow
            }
        }
    }
    emit(onFailed("登录超时，请重试"))
}
