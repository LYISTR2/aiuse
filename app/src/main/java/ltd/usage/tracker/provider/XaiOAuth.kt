package ltd.usage.tracker.provider

import kotlinx.serialization.json.JsonObject
import ltd.usage.tracker.provider.OAuthHttp.requireStr
import ltd.usage.tracker.provider.OAuthHttp.str

/**
 * xAI OAuth 设备码登录（RFC 8628）。
 * 复用 [OAuthHttp] 与全局 OkHttpClient 连接池，不再单独维护 HTTP 客户端。
 */
object XaiOAuth {

    const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
    const val SCOPE = "openid profile email offline_access grok-cli:access api:access"

    private const val DEVICE_CODE_URL = "https://auth.x.ai/oauth2/device/code"
    private const val TOKEN_URL = "https://auth.x.ai/oauth2/token"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresIn: Int,
        val interval: Int,
    ) {
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

    fun requestDeviceCode(): DeviceCode {
        val body = postChecked(
            DEVICE_CODE_URL,
            mapOf("client_id" to CLIENT_ID, "scope" to SCOPE),
        )
        return DeviceCode(
            deviceCode = body.requireStr("device_code"),
            userCode = body.requireStr("user_code"),
            verificationUri = body.requireStr("verification_uri"),
            verificationUriComplete = body.str("verification_uri_complete"),
            expiresIn = body.str("expires_in")?.toIntOrNull() ?: 1800,
            interval = body.str("interval")?.toIntOrNull() ?: 5,
        )
    }

    sealed interface PollResult {
        data class Authorized(val tokens: Tokens) : PollResult
        data object Pending : PollResult
        data object SlowDown : PollResult
        data class Failed(val message: String) : PollResult
    }

    fun pollOnce(deviceCode: String): PollResult {
        val resp = OAuthHttp.postForm(
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
                    accessToken = resp.body.requireStr("access_token"),
                    refreshToken = resp.body.str("refresh_token"),
                    expiresIn = resp.body.str("expires_in")?.toIntOrNull() ?: 3600,
                )
            )
        }
        return when (resp.body.str("error")) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied", "authorization_denied" -> PollResult.Failed("授权被拒绝")
            "expired_token" -> PollResult.Failed("设备码已过期，请重新登录")
            else -> PollResult.Failed(
                resp.body.str("error_description")?.take(100) ?: "登录失败 (HTTP ${resp.code})"
            )
        }
    }

    fun refresh(refreshToken: String): Tokens {
        val body = postChecked(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to CLIENT_ID,
                "refresh_token" to refreshToken,
            ),
        )
        return Tokens(
            accessToken = body.requireStr("access_token"),
            refreshToken = body.str("refresh_token") ?: refreshToken,
            expiresIn = body.str("expires_in")?.toIntOrNull() ?: 3600,
        )
    }

    private fun postChecked(url: String, fields: Map<String, String>): JsonObject {
        val r = OAuthHttp.postForm(url, fields)
        if (!r.ok) {
            error(r.body.str("error_description") ?: r.body.str("error") ?: "HTTP ${r.code}")
        }
        return r.body
    }
}
