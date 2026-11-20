package ltd.usage.tracker.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * xAI OAuth 设备码登录（RFC 8628）。
 *
 * 流程：
 *   1. POST /oauth2/device/code 拿到 user_code 与 verification_uri
 *   2. 用户在浏览器打开链接、输入 user_code 并授权
 *   3. App 轮询 /oauth2/token 直到拿到 access_token
 *   4. 用 access_token 调 cli-chat-proxy 读用量
 *
 * 用的是 Grok CLI 的公开 client_id（xAI 官方客户端，token_endpoint_auth_methods 含 none）。
 *
 * 注意 scope 限制：该 client 只允许
 *   openid profile email offline_access grok-cli:access api:access
 * 不允许 billing:read / teams:read 等管理类 scope（实测会返回 invalid_scope）。
 * 因此用量走 CLI 代理的 /v1/billing，而不是 Management API。
 */
object XaiOAuth {

    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val SCOPE = "openid profile email offline_access grok-cli:access api:access"

    private const val DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
    private const val TOKEN_URL = "https://auth.x.ai/oauth2/token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresIn: Int,
        val interval: Int,
    ) {
        // device_code 是轮询凭证，不能出现在日志里；userCode 本来就要展示给用户
        override fun toString(): String =
            "DeviceCode(deviceCode=***(${deviceCode.length}), userCode=$userCode, verificationUri=$verificationUri, verificationUriComplete=$verificationUriComplete, expiresIn=$expiresIn, interval=$interval)"
    }

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Int,
    ) {
        override fun toString(): String =
            "Tokens(accessToken=***(${accessToken.length}), refreshToken=${refreshToken?.let { "***(${it.length})" }}, expiresIn=$expiresIn)"
    }

    /** 第一步：申请设备码。 */
    fun requestDeviceCode(): DeviceCode {
        val body = post(
            DEVICE_CODE_URL,
            mapOf("client_id" to CLIENT_ID, "scope" to SCOPE),
        )
        return DeviceCode(
            deviceCode = body.str("device_code"),
            userCode = body.str("user_code"),
            verificationUri = body.str("verification_uri"),
            verificationUriComplete = body["verification_uri_complete"]?.jsonPrimitive?.content,
            expiresIn = body["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1800,
            interval = body["interval"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5,
        )
    }

    sealed interface PollResult {
        data class Authorized(val tokens: Tokens) : PollResult
        data object Pending : PollResult
        data object SlowDown : PollResult
        data class Failed(val message: String) : PollResult
    }

    /** 第三步：轮询一次。调用方负责按 interval 间隔重复调用。 */
    fun pollOnce(deviceCode: String): PollResult {
        val resp = postRaw(
            TOKEN_URL,
            mapOf(
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                "client_id" to CLIENT_ID,
                "device_code" to deviceCode,
            ),
        )
        if (resp.ok) {
            return PollResult.Authorized(
                Tokens(
                    accessToken = resp.body.str("access_token"),
                    refreshToken = resp.body["refresh_token"]?.jsonPrimitive?.content,
                    expiresIn = resp.body["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3600,
                )
            )
        }
        return when (resp.body["error"]?.jsonPrimitive?.content) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied", "authorization_denied" -> PollResult.Failed("授权被拒绝")
            "expired_token" -> PollResult.Failed("设备码已过期，请重新登录")
            else -> PollResult.Failed(
                resp.body["error_description"]?.jsonPrimitive?.content
                    ?.take(100)
                    ?: "登录失败 (HTTP ${resp.code})"
            )
        }
    }

    /** 用 refresh token 换新的 access token。 */
    fun refresh(refreshToken: String): Tokens {
        val body = post(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "refresh_token" to refreshToken,
            ),
        )
        return Tokens(
            accessToken = body.str("access_token"),
            // xAI 轮换 refresh token；未返回时沿用旧的
            refreshToken = body["refresh_token"]?.jsonPrimitive?.content ?: refreshToken,
            expiresIn = body["expires_in"]?.jsonPrimitive?.content?.toIntOrNull() ?: 3600,
        )
    }

    // ---- HTTP ----

    private data class RawResponse(val ok: Boolean, val code: Int, val body: JsonObject)

    private fun postRaw(url: String, fields: Map<String, String>): RawResponse {
        val form = FormBody.Builder().apply {
            fields.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .post(form)
            .header("Accept", "application/json")
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val parsed = runCatching {
                json.parseToJsonElement(text).let { it as? JsonObject }
            }.getOrNull() ?: JsonObject(emptyMap())
            return RawResponse(resp.isSuccessful, resp.code, parsed)
        }
    }

    private fun post(url: String, fields: Map<String, String>): JsonObject {
        val r = postRaw(url, fields)
        if (!r.ok) {
            val detail = r.body["error_description"]?.jsonPrimitive?.content
                ?: r.body["error"]?.jsonPrimitive?.content
                ?: "HTTP ${r.code}"
            error(detail)
        }
        return r.body
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content ?: error("缺少字段: $key")
}
