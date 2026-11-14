package ltd.usage.tracker.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.data.UsageSync
import ltd.usage.tracker.provider.ClaudeOAuth
import ltd.usage.tracker.provider.CodexLoginFlow
import ltd.usage.tracker.provider.CodexOAuth
import ltd.usage.tracker.provider.OAuthException
import ltd.usage.tracker.provider.OAuthTokens
import ltd.usage.tracker.provider.Pkce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.8.0 订阅登录：成功落库、取消语义（与 1.7.1 xAI 修复一致：取消即停，之后授权也什么都不写）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelOAuthTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private class FakeStorage : UsageSync.AppStorage {
        @Volatile var accounts: List<StoredAccount> = emptyList()
        @Volatile var creds: Map<String, Credential> = emptyMap()
        @Volatile var snapshots: List<UsageSnapshot> = emptyList()
        val writes = AtomicInteger()
        override fun loadAccounts() = accounts
        override fun saveAccounts(accounts: List<StoredAccount>) { writes.incrementAndGet(); this.accounts = accounts }
        override fun loadCredentials() = creds
        override fun saveCredentials(creds: Map<String, Credential>) { writes.incrementAndGet(); this.creds = creds }
        override fun loadSnapshots() = snapshots
        override fun saveSnapshots(list: List<UsageSnapshot>) { snapshots = list }
        override fun loadSettings() = emptyMap<String, String>()
        override fun saveSettings(settings: Map<String, String>) = Unit
    }

    private val pkce = Pkce("verifier", "challenge", "state-1")
    private val pending = ClaudeOAuth.Pending(pkce, "https://claude.com/cai/oauth/authorize?x=1")
    private val tokens = OAuthTokens("at-claude", "rt-claude", 28800)

    private fun idleUntil(timeoutMs: Long = 5000, cond: () -> Boolean) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            looper.idleFor(Duration.ofMillis(100))
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时")
    }

    private fun settle(realMs: Long = 400, virtualMs: Long = 3000) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + realMs
        while (System.currentTimeMillis() < end) {
            looper.idleFor(Duration.ofMillis(virtualMs / 10))
            Thread.sleep(realMs / 10)
        }
    }

    private fun vm(storage: FakeStorage, oauth: OAuthLogins) =
        MainViewModel(app, storage, oauth) { error("不应走 xAI 登录") }

    // ---- Claude（粘贴授权码）

    @Test
    fun claude_pasteCode_savesSubscriptionAccount() {
        val storage = FakeStorage()
        var got: Pair<String, Pkce>? = null
        val vm = vm(storage, OAuthLogins(claudeBegin = { pending }, claudeExchange = { c, p -> got = c to p; tokens }))
        vm.loginClaude()
        assertEquals(pending.authorizeUrl, vm.state.value.oauthLogin?.authorizeUrl)
        assertTrue(vm.state.value.oauthLogin!!.pasteMode)

        vm.submitClaudeCode("  code-xyz#state-1 ")
        idleUntil { vm.state.value.oauthLogin == null && storage.accounts.isNotEmpty() }

        assertEquals("code-xyz" to pkce, got)
        val acc = storage.accounts.single()
        assertEquals(Provider.CLAUDE_SUB.id, acc.providerId)
        val cred = storage.creds.getValue(acc.id)
        assertEquals("at-claude", cred.token)
        assertEquals("rt-claude", cred.refreshToken)
        assertTrue(cred.expiresAt!! > System.currentTimeMillis() + 28_000_000)
        assertEquals("Claude 登录成功", vm.state.value.message)
    }

    @Test
    fun claude_wrongState_isRejectedWithoutNetwork() {
        val storage = FakeStorage()
        val calls = AtomicInteger()
        val vm = vm(storage, OAuthLogins(claudeBegin = { pending }, claudeExchange = { _, _ -> calls.incrementAndGet(); tokens }))
        vm.loginClaude()
        vm.submitClaudeCode("code#other-state")
        settle()
        assertEquals(0, calls.get())
        assertEquals("授权码不属于本次登录，请重新打开授权页面", vm.state.value.oauthLogin?.error)
        assertEquals(0, storage.writes.get())
    }

    @Test
    fun claude_exchangeFailure_staysInDialogWithError() {
        val storage = FakeStorage()
        val vm = vm(storage, OAuthLogins(claudeBegin = { pending }, claudeExchange = { _, _ -> throw OAuthException("授权已失效，请重新登录") }))
        vm.loginClaude()
        vm.submitClaudeCode("code#state-1")
        idleUntil { vm.state.value.oauthLogin?.error != null }
        assertEquals("授权已失效，请重新登录", vm.state.value.oauthLogin?.error)
        assertEquals(false, vm.state.value.oauthLogin?.submitting)
        assertEquals(0, storage.writes.get())
    }

    /** 换令牌途中取消：请求回来后也不写库，对话框保持关闭。 */
    @Test
    fun claude_cancelDuringExchange_addsNothing() {
        val storage = FakeStorage()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicBoolean(false)
        val vm = vm(storage, OAuthLogins(claudeBegin = { pending }, claudeExchange = { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            returned.set(true)
            tokens
        }))
        vm.loginClaude()
        vm.submitClaudeCode("code#state-1")
        assertTrue(started.await(5, TimeUnit.SECONDS))

        vm.cancelOAuthLogin()
        release.countDown()
        settle()

        assertTrue(returned.get())
        assertTrue(storage.accounts.isEmpty())
        assertEquals(0, storage.writes.get())
        assertNull(vm.state.value.oauthLogin)
        // 取消后 PKCE 已丢弃：再提交同一授权码什么都不做
        vm.submitClaudeCode("code#state-1")
        settle()
        assertEquals(0, storage.writes.get())
    }

    // ---- ChatGPT（设备码）

    private val device = CodexOAuth.DeviceCode("da", "WXYZ-0001", CodexOAuth.VERIFICATION_URL, interval = 1, expiresIn = 600)
    private val codexLogin = CodexOAuth.Login(OAuthTokens("at-gpt", "rt-gpt", 864000), "acct-42")

    @Test
    fun chatgpt_deviceCode_savesAccountId() {
        val storage = FakeStorage()
        val vm = vm(storage, OAuthLogins(codexEvents = {
            CodexLoginFlow(
                requestDeviceCode = { device.copy(interval = 0) },
                pollOnce = { CodexOAuth.PollResult.Authorized("ac", "cv") },
                exchange = { _, _ -> codexLogin },
            ).run()
        }))
        vm.loginChatGpt()
        idleUntil { storage.accounts.isNotEmpty() && vm.state.value.oauthLogin == null }
        val acc = storage.accounts.single()
        assertEquals(Provider.CHATGPT_SUB.id, acc.providerId)
        val cred = storage.creds.getValue(acc.id)
        assertEquals("at-gpt", cred.token)
        assertEquals("rt-gpt", cred.refreshToken)
        assertEquals("acct-42", cred.accountId)
        assertEquals("ChatGPT 登录成功", vm.state.value.message)
    }

    /** 取消时正在两次轮询之间等待：轮询立即停止，之后浏览器里授权也不会添加账号。 */
    @Test
    fun chatgpt_cancelWhileWaiting_stopsPolling() {
        val storage = FakeStorage()
        val polls = AtomicInteger()
        val authorized = AtomicBoolean(false)
        val vm = vm(storage, OAuthLogins(codexEvents = {
            CodexLoginFlow(
                requestDeviceCode = { device },
                pollOnce = {
                    polls.incrementAndGet()
                    if (authorized.get()) CodexOAuth.PollResult.Authorized("ac", "cv") else CodexOAuth.PollResult.Pending
                },
                exchange = { _, _ -> codexLogin },
            ).run()
        }))
        vm.loginChatGpt()
        idleUntil { vm.state.value.oauthLogin?.userCode == "WXYZ-0001" }

        vm.cancelOAuthLogin()
        authorized.set(true)
        settle(virtualMs = 10_000)

        assertEquals(0, polls.get())
        assertTrue(storage.accounts.isEmpty())
        assertEquals(0, storage.writes.get())
        assertNull(vm.state.value.oauthLogin)
    }

    /** 取消时换令牌请求正在进行：请求返回后不写库。 */
    @Test
    fun chatgpt_cancelDuringExchange_addsNothing() {
        val storage = FakeStorage()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val vm = vm(storage, OAuthLogins(codexEvents = {
            CodexLoginFlow(
                requestDeviceCode = { device.copy(interval = 0) },
                pollOnce = { CodexOAuth.PollResult.Authorized("ac", "cv") },
                exchange = { _, _ -> started.countDown(); release.await(5, TimeUnit.SECONDS); codexLogin },
            ).run()
        }))
        vm.loginChatGpt()
        // 设备码请求在 IO 线程返回后要回主线程继续，推进主 Looper 直到换令牌开始
        idleUntil { started.count == 0L }
        vm.cancelOAuthLogin()
        release.countDown()
        settle()

        assertTrue(storage.accounts.isEmpty())
        assertEquals(0, storage.writes.get())
        assertNull(vm.state.value.oauthLogin)
    }

    /** 两种登录互斥：Claude 进行中点 ChatGPT 不会覆盖。 */
    @Test
    fun loginsAreExclusive() {
        val storage = FakeStorage()
        val codexStarted = AtomicBoolean(false)
        val vm = vm(storage, OAuthLogins(claudeBegin = { pending }, codexEvents = { codexStarted.set(true); error("x") }))
        vm.loginClaude()
        vm.loginChatGpt()
        vm.loginXai()
        settle(realMs = 100)
        assertEquals(Provider.CLAUDE_SUB, vm.state.value.oauthLogin?.provider)
        assertNull(vm.state.value.xaiLogin)
        assertTrue(!codexStarted.get())
    }
}
