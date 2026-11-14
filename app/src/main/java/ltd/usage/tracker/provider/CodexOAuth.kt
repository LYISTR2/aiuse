package ltd.usage.tracker.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ltd.usage.tracker.provider.OAuthHttp.str

/**
 * ChatGPT Plus / Pro 订阅登录：Codex CLI 的设备码流程（auth.openai.com）。
 *
 * 参数取自 openai/codex（commit b1573f102413e96a6481087922bf425231222dee）：
 *   CLIENT_ID      codex-rs/login/src/auth/manager.rs      app_EMoamEEZ73f0CkXaXp7hrann
 *   设备码          codex-rs/login/src/device_code_auth.rs  POST {issuer}/api/accounts/deviceauth/usercode
 *   轮询            同上                                     POST {issuer}/api/accounts/deviceauth/token（403/404 = 未授权）
 *   授权页          同上                                     {issuer}/codex/device
 *   换令牌          codex-rs/login/src/server.rs            POST {issuer}/oauth/token，表单，redirect_uri={issuer}/deviceauth/callback
 *   刷新            codex-rs/login/src/auth/manager.rs      POST https://auth.openai.com/oauth/token，JSON
 *   账号 id         codex-rs/login/src/token_data.rs        id_token 声明 https://api.openai.com/auth → chatgpt_account_id
 *
 * 为什么选设备码而不是 127.0.0.1:1455 回调：手机上切到浏览器后 App 会被冻结，
 * 本地回调服务器收不到请求；1455 是固定端口，还可能被占。设备码只需要轮询，
 * App 回到前台后接着轮询即可。代价是用户要先在 ChatGPT 设置里开启 Codex 设备码登录（未开启时 usercode 返回 404）。
 *
 * 风险：借用官方 Codex CLI 的 client_id，非 OpenAI 公开给第三方的接入方式；详见 CHANGES-1.8.0.md。
 */
object CodexOAuth {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val ISSUER = "https://auth.openai.com"
    const val TOKEN_URL = "$ISSUER/oauth/token"
    const val USERCODE_URL = "$ISSUER/api/accounts/deviceauth/usercode"
    const val POLL_URL = "$ISSUER/api/accounts/deviceauth/token"
    const val VERIFICATION_URL = "$ISSUER/codex/device"
    const val DEVICE_REDIRECT_URI = "$ISSUER/deviceauth/callback"

    /** Codex 设备码最长等 15 分钟。 */
    const val MAX_WAIT_SECONDS = 15 * 60

    data class DeviceCode(
        val deviceAuthId: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int = MAX_WAIT_SECONDS,
    )

    sealed interface PollResult {
        /** 用户已授权，服务端直接给出授权码和本次的 PKCE verifier。 */
        data class Authorized(val authorizationCode: String, val codeVerifier: String) : PollResult
        data object Pending : PollResult
        data class Failed(val message: String) : PollResult
    }

    /** 登录结果：令牌 + 从 id_token 里取出的 chatgpt_account_id。 */
    data class Login(val tokens: OAuthTokens, val accountId: String?)

    fun requestDeviceCode(): DeviceCode {
        val r = OAuthHttp.postJson(USERCODE_URL, mapOf("client_id" to CLIENT_ID))
        if (r.code == 404) {
            throw OAuthException("ChatGPT 未开启设备码登录：请在 ChatGPT 设置 → 安全 里开启 Codex 设备码授权后重试", 404)
        }
        if (!r.ok) throw OAuthException("获取设备码失败 (HTTP ${r.code})", r.code)
        return parseDeviceCode(r.body)
    }

    /** 解析 usercode 响应；字段名兼容 user_code / usercode，interval 是字符串。 */
    internal fun parseDeviceCode(body: JsonObject): DeviceCode = DeviceCode(
        deviceAuthId = body.str("device_auth_id") ?: throw OAuthException("设备码响应缺少 device_auth_id"),
        userCode = body.str("user_code") ?: body.str("usercode") ?: throw OAuthException("设备码响应缺少 user_code"),
        verificationUri = VERIFICATION_URL,
        interval = body.str("interval")?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 5,
    )

    fun pollOnce(device: DeviceCode): PollResult {
        val r = OAuthHttp.postJson(
            POLL_URL,
            linkedMapOf("device_auth_id" to device.deviceAuthId, "user_code" to device.userCode),
        )
        if (r.ok) {
            val code = r.body.str("authorization_code")
            val verifier = r.body.str("code_verifier")
            return if (code != null && verifier != null) PollResult.Authorized(code, verifier)
            else PollResult.Failed("授权响应不完整，请重试")
        }
        if (r.code == 403 || r.code == 404) return PollResult.Pending
        return PollResult.Failed("登录失败 (HTTP ${r.code})")
    }

    /** 授权码换令牌（表单编码，与 codex-rs server.rs 一致）。 */
    fun exchange(authorizationCode: String, codeVerifier: String): Login {
        val tokens = OAuthHttp.parseTokens(
            OAuthHttp.postForm(
                TOKEN_URL,
                linkedMapOf(
                    "grant_type" to "authorization_code",
                    "code" to authorizationCode,
                    "redirect_uri" to DEVICE_REDIRECT_URI,
                    "client_id" to CLIENT_ID,
                    "code_verifier" to codeVerifier,
                ),
            ),
            defaultExpiresIn = ::expiresInFromJwt,
        )
        return Login(tokens, accountId(tokens.idToken) ?: accountId(tokens.accessToken))
    }

    /** 刷新（JSON 编码，与 codex-rs manager.rs 一致）；没返回新 refresh token 时沿用旧的。 */
    fun refresh(refreshToken: String): OAuthTokens = OAuthHttp.parseTokens(
        OAuthHttp.postJson(
            TOKEN_URL,
            linkedMapOf(
                "client_id" to CLIENT_ID,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
            ),
        ),
        defaultExpiresIn = ::expiresInFromJwt,
    ).let { if (it.refreshToken.isNullOrBlank()) it.copy(refreshToken = refreshToken) else it }

    /** 从 JWT 里取 chatgpt_account_id（声明 https://api.openai.com/auth 下）。 */
    fun accountId(jwt: String?): String? {
        val auth = Jwt.claims(jwt)?.get("https://api.openai.com/auth") as? JsonObject ?: return null
        return (auth["chatgpt_account_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /**
     * OpenAI 的刷新响应不一定带 expires_in（codex-rs 的 RefreshResponse 里就没有），
     * Codex 自己按 access token 的 exp 判断过期；这里同样从 JWT 取，取不到按 1 小时保守处理。
     */
    internal fun expiresInFromJwt(accessToken: String, nowMs: Long = System.currentTimeMillis()): Long {
        val exp = Jwt.expSeconds(accessToken) ?: return 3600L
        return (exp - nowMs / 1000).coerceAtLeast(0)
    }
}
