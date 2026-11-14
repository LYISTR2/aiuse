package ltd.usage.tracker.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.provider.vendors.httpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

/** Claude / Codex OAuth 客户端：授权地址、粘贴解析、请求编码、令牌解析、设备码。 */
class OAuthClientsTest {

    private val requests = mutableListOf<Request>()
    private val bodies = mutableListOf<String>()
    private var reply: (Request) -> Pair<Int, String> = { 200 to "{}" }
    private lateinit var original: OkHttpClient

    @Before fun install() {
        original = httpClient
        httpClient = OkHttpClient.Builder().addInterceptor { chain ->
            val req = chain.request()
            requests += req
            bodies += Buffer().also { req.body?.writeTo(it) }.readUtf8()
            val (code, body) = reply(req)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    }

    @After fun restore() { httpClient = original }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." + enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    // ---- PKCE

    @Test
    fun pkce_rfc7636Vector_andRandomness() {
        // RFC 7636 附录 B 的示例
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val a = Pkce.generate(); val b = Pkce.generate()
        assertTrue(a.verifier != b.verifier && a.state != b.state)
        assertTrue(a.verifier.length in 43..128)
        assertTrue(!a.toString().contains(a.verifier))
    }

    // ---- Claude

    @Test
    fun claude_authorizeUrl_matchesClaudeCode() {
        val pkce = Pkce("v", "chal", "st")
        val url = ClaudeOAuth.authorizeUrl(pkce).toHttpUrl()
        assertEquals("https://claude.com/cai/oauth/authorize", url.newBuilder().query(null).build().toString())
        assertEquals(
            listOf("code", "client_id", "response_type", "redirect_uri", "scope", "code_challenge", "code_challenge_method", "state"),
            url.queryParameterNames.toList(),
        )
        assertEquals("true", url.queryParameter("code"))
        assertEquals("9d1c250a-e61b-44d9-88ed-5944d1962f5e", url.queryParameter("client_id"))
        assertEquals("https://platform.claude.com/oauth/code/callback", url.queryParameter("redirect_uri"))
        assertEquals("user:profile user:inference", url.queryParameter("scope"))
        assertEquals("chal", url.queryParameter("code_challenge"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("st", url.queryParameter("state"))
    }

    @Test
    fun claude_splitPasted() {
        assertEquals("abc" to "st", ClaudeOAuth.splitPasted("  abc#st \n"))
        assertEquals("abc" to null, ClaudeOAuth.splitPasted("abc"))
        assertEquals("abc" to "st", ClaudeOAuth.splitPasted("https://platform.claude.com/oauth/code/callback?code=abc&state=st"))
        assertNull(ClaudeOAuth.splitPasted("   "))
        assertNull(ClaudeOAuth.splitPasted("#st"))
        assertNull(ClaudeOAuth.splitPasted("https://platform.claude.com/oauth/code/callback?state=st"))
    }

    @Test
    fun claude_exchange_isJsonWithState() {
        reply = { 200 to """{"access_token":"at","refresh_token":"rt","expires_in":28800,"token_type":"Bearer"}""" }
        val t = ClaudeOAuth.exchange("the-code", Pkce("ver", "chal", "st"))
        assertEquals(OAuthTokens("at", "rt", 28800), t)
        val req = requests.single()
        assertEquals("https://platform.claude.com/v1/oauth/token", req.url.toString())
        assertEquals("application/json; charset=utf-8", req.body!!.contentType().toString())
        val b = json(bodies.single())
        assertEquals("authorization_code", b.s("grant_type"))
        assertEquals("the-code", b.s("code"))
        assertEquals("ver", b.s("code_verifier"))
        assertEquals("st", b.s("state"))
        assertEquals("https://platform.claude.com/oauth/code/callback", b.s("redirect_uri"))
        assertEquals(ClaudeOAuth.CLIENT_ID, b.s("client_id"))
    }

    @Test
    fun claude_refresh_keepsOldRefreshTokenWhenNotRotated_andMapsInvalidGrant() {
        reply = { 200 to """{"access_token":"at2","expires_in":3600}""" }
        assertEquals(OAuthTokens("at2", "rt-old", 3600), ClaudeOAuth.refresh("rt-old"))
        val b = json(bodies.single())
        assertEquals("refresh_token", b.s("grant_type"))
        assertEquals("rt-old", b.s("refresh_token"))
        assertEquals("user:profile user:inference", b.s("scope"))

        reply = { 400 to """{"error":"invalid_grant","error_description":"Refresh token not found or invalid"}""" }
        try {
            ClaudeOAuth.refresh("rt-old"); fail()
        } catch (e: OAuthException) {
            assertEquals("授权已失效，请重新登录", e.message)
            assertEquals(400, e.status)
        }
    }

    // ---- Codex

    @Test
    fun codex_deviceCode_parse_andNotEnabled() {
        reply = { 200 to """{"device_auth_id":"da-1","usercode":"ABCD-1234","interval":"7"}""" }
        val dc = CodexOAuth.requestDeviceCode()
        assertEquals(CodexOAuth.DeviceCode("da-1", "ABCD-1234", "https://auth.openai.com/codex/device", 7, 900), dc)
        assertEquals("https://auth.openai.com/api/accounts/deviceauth/usercode", requests.last().url.toString())
        assertEquals(CodexOAuth.CLIENT_ID, json(bodies.last()).s("client_id"))

        reply = { 404 to "{}" }
        try { CodexOAuth.requestDeviceCode(); fail() } catch (e: OAuthException) {
            assertTrue(e.message!!.contains("设备码"))
        }
    }

    @Test
    fun codex_poll_pendingThenAuthorized() {
        val dc = CodexOAuth.DeviceCode("da-1", "ABCD-1234", CodexOAuth.VERIFICATION_URL, 5)
        reply = { 403 to """{"error":"pending"}""" }
        assertEquals(CodexOAuth.PollResult.Pending, CodexOAuth.pollOnce(dc))
        reply = { 404 to "" }
        assertEquals(CodexOAuth.PollResult.Pending, CodexOAuth.pollOnce(dc))
        reply = { 200 to """{"authorization_code":"ac","code_challenge":"cc","code_verifier":"cv"}""" }
        assertEquals(CodexOAuth.PollResult.Authorized("ac", "cv"), CodexOAuth.pollOnce(dc))
        val b = json(bodies.last())
        assertEquals("da-1", b.s("device_auth_id"))
        assertEquals("ABCD-1234", b.s("user_code"))
        reply = { 500 to "" }
        assertTrue(CodexOAuth.pollOnce(dc) is CodexOAuth.PollResult.Failed)
    }

    @Test
    fun codex_exchange_isForm_andReadsAccountIdFromIdToken() {
        val now = System.currentTimeMillis() / 1000
        val idToken = jwt("""{"https://api.openai.com/auth":{"chatgpt_account_id":"acct-9","chatgpt_plan_type":"plus"}}""")
        val access = jwt("""{"exp":${now + 864000}}""")
        reply = { 200 to """{"id_token":"$idToken","access_token":"$access","refresh_token":"rt"}""" }
        val login = CodexOAuth.exchange("ac", "cv")
        assertEquals("acct-9", login.accountId)
        assertEquals("rt", login.tokens.refreshToken)
        // 没有 expires_in：按 access token 的 exp 算
        assertTrue(login.tokens.expiresIn in 863990..864000)
        val req = requests.single()
        assertEquals("https://auth.openai.com/oauth/token", req.url.toString())
        assertTrue(req.body!!.contentType().toString().startsWith("application/x-www-form-urlencoded"))
        val form = bodies.single().split('&').associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("ac", form["code"])
        assertEquals("cv", form["code_verifier"])
        assertEquals("https://auth.openai.com/deviceauth/callback", form["redirect_uri"])
        assertEquals(CodexOAuth.CLIENT_ID, form["client_id"])
    }

    @Test
    fun codex_refresh_isJson_andFallsBackWithoutExp() {
        reply = { 200 to """{"access_token":"opaque","refresh_token":"rt2"}""" }
        assertEquals(OAuthTokens("opaque", "rt2", 3600), CodexOAuth.refresh("rt1"))
        val b = json(bodies.single())
        assertEquals("refresh_token", b.s("grant_type"))
        assertEquals("rt1", b.s("refresh_token"))
        assertEquals(CodexOAuth.CLIENT_ID, b.s("client_id"))
        assertTrue(requests.single().body!!.contentType().toString().startsWith("application/json"))
    }

    @Test
    fun tokens_toString_isRedacted() {
        val t = OAuthTokens("secret-access-token", "secret-refresh", 1)
        assertTrue(!t.toString().contains("secret"))
    }
}
