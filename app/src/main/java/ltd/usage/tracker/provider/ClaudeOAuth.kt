package ltd.usage.tracker.provider

import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Claude Pro / Max 订阅登录：OAuth 2.0 授权码 + PKCE，「粘贴授权码」模式。
 *
 * 参数取自 Claude Code 2.1.325（npm @anthropic-ai/claude-code-linux-x64）内置的生产配置：
 *   CLIENT_ID                 9d1c250a-e61b-44d9-88ed-5944d1962f5e
 *   CLAUDE_AI_AUTHORIZE_URL   https://claude.com/cai/oauth/authorize
 *   TOKEN_URL                 https://platform.claude.com/v1/oauth/token
 *   MANUAL_REDIRECT_URL       https://platform.claude.com/oauth/code/callback
 * Claude Code 在本机起不了回调服务器时就用 MANUAL_REDIRECT_URL：授权后页面显示「code#state」，
 * 用户复制回终端。这里照搬这条路径 —— 不需要在手机上起 loopback 服务器
 * （App 切到浏览器后会被系统冻结，服务器收不到回调，除非再加前台服务）。
 *
 * 风险：这是借用官方 CLI 的 client_id，并非 Anthropic 给第三方开放的接入方式，
 * 可能违反服务条款，也可能随时被收紧；详见 CHANGES-1.8.0.md。
 */
object ClaudeOAuth {
    const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    const val AUTHORIZE_URL = "https://claude.com/cai/oauth/authorize"
    const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    const val REDIRECT_URI = "https://platform.claude.com/oauth/code/callback"

    /**
     * 只申请读用量需要的最小范围。Claude Code 默认还会要 org:create_api_key、user:sessions:claude_code、
     * user:mcp_servers、user:file_upload、user:plugins 等，这里都不需要；
     * Claude Code 自己的 setup-token 只申请 user:inference，说明该 client 接受子集。
     * /api/oauth/usage 需要 user:profile（CodexBar 文档：只有 user:inference 的令牌读不了用量）。
     */
    const val SCOPE = "user:profile user:inference"

    /** 一次登录的本地状态；只存在内存里，取消或完成即丢弃。 */
    data class Pending(val pkce: Pkce, val authorizeUrl: String)

    fun begin(pkce: Pkce = Pkce.generate()): Pending = Pending(pkce, authorizeUrl(pkce))

    /** 参数顺序与 Claude Code 一致：code=true 在最前，随后 client_id、response_type、redirect_uri、scope、PKCE、state。 */
    fun authorizeUrl(pkce: Pkce): String = AUTHORIZE_URL.toHttpUrl().newBuilder()
        .addQueryParameter("code", "true")
        .addQueryParameter("client_id", CLIENT_ID)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", REDIRECT_URI)
        .addQueryParameter("scope", SCOPE)
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", pkce.state)
        .build().toString()

    /**
     * 解析用户粘贴的内容，返回 (code, state)。
     * 页面显示的是「code#state」；也兼容整段回调地址（?code=…&state=…）和只粘了 code 的情况。
     */
    fun splitPasted(input: String): Pair<String, String?>? {
        val t = input.trim().trim('"', '\'')
        if (t.isEmpty()) return null
        if (t.startsWith("http://") || t.startsWith("https://")) {
            val url = runCatching { t.toHttpUrl() }.getOrNull() ?: return null
            val code = url.queryParameter("code")?.takeIf { it.isNotBlank() } ?: return null
            return code to url.queryParameter("state")
        }
        val code = t.substringBefore('#').trim()
        val state = t.substringAfter('#', "").trim().takeIf { it.isNotEmpty() }
        return code.takeIf { it.isNotEmpty() }?.let { it to state }
    }

    /** 授权码换令牌。JSON 请求体，字段与 Claude Code 相同（含 state）。 */
    fun exchange(code: String, pkce: Pkce): OAuthTokens = OAuthHttp.parseTokens(
        OAuthHttp.postJson(
            TOKEN_URL,
            linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to REDIRECT_URI,
                "client_id" to CLIENT_ID,
                "code_verifier" to pkce.verifier,
                "state" to pkce.state,
            ),
        ),
        defaultExpiresIn = { DEFAULT_EXPIRES_IN },
    )

    /** 刷新。JSON 请求体，带上原 scope（Claude Code 同样带 scope）；没返回新 refresh token 时沿用旧的。 */
    fun refresh(refreshToken: String): OAuthTokens = OAuthHttp.parseTokens(
        OAuthHttp.postJson(
            TOKEN_URL,
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to CLIENT_ID,
                "scope" to SCOPE,
            ),
        ),
        defaultExpiresIn = { DEFAULT_EXPIRES_IN },
    ).let { if (it.refreshToken.isNullOrBlank()) it.copy(refreshToken = refreshToken) else it }

    /** 响应缺 expires_in 时的保守值（8 小时，Claude Code 实际下发的量级）。 */
    private const val DEFAULT_EXPIRES_IN = 8 * 3600L
}
