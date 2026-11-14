package ltd.usage.tracker.provider

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** ChatGPT 设备码流程：成功、各类失败。取消见 MainViewModelOAuthTest。 */
class CodexLoginFlowTest {
    private val device = CodexOAuth.DeviceCode("da", "WXYZ-0001", CodexOAuth.VERIFICATION_URL, interval = 0, expiresIn = 60)
    private val login = CodexOAuth.Login(OAuthTokens("at", "rt", 3600), "acct-1")

    @Test
    fun pendingThenAuthorized_exchangesWithServerVerifier() = runBlocking {
        var polls = 0
        var exchanged: Pair<String, String>? = null
        val events = CodexLoginFlow(
            requestDeviceCode = { device },
            pollOnce = { if (++polls < 3) CodexOAuth.PollResult.Pending else CodexOAuth.PollResult.Authorized("ac", "cv") },
            exchange = { c, v -> exchanged = c to v; login },
        ).run().toList()
        assertEquals(
            listOf(CodexLoginFlow.Event.CodeReady("WXYZ-0001", CodexOAuth.VERIFICATION_URL), CodexLoginFlow.Event.Authorized(login)),
            events,
        )
        assertEquals("ac" to "cv", exchanged)
    }

    @Test
    fun failures() = runBlocking {
        val notEnabled = CodexLoginFlow(requestDeviceCode = { throw OAuthException("ChatGPT 未开启设备码登录") }).run().toList()
        assertEquals(listOf(CodexLoginFlow.Event.Failed("ChatGPT 未开启设备码登录")), notEnabled)

        val badExchange = CodexLoginFlow(
            requestDeviceCode = { device },
            pollOnce = { CodexOAuth.PollResult.Authorized("ac", "cv") },
            exchange = { _, _ -> error("boom") },
        ).run().toList()
        assertEquals(CodexLoginFlow.Event.Failed("换取令牌失败: boom"), badExchange.last())

        var t = 0L
        val timeout = CodexLoginFlow(
            requestDeviceCode = { device },
            pollOnce = { CodexOAuth.PollResult.Pending },
            now = { t.also { t += 30_000 } },
        ).run().toList()
        assertEquals(CodexLoginFlow.Event.Failed("登录超时，请重试"), timeout.last())
    }
}
