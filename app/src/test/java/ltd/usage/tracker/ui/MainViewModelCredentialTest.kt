package ltd.usage.tracker.ui

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageResult
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.data.UsageSync
import ltd.usage.tracker.provider.XaiLoginFlow
import ltd.usage.tracker.provider.XaiOAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.7.1 修复的两个问题：
 *  1. 取消 xAI 登录后轮询立即停止，之后再授权也不会添加账号、不会写凭据；
 *  2. 添加 / 删除账号写凭据与刷新换 token 写回共用一把锁，互不覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainViewModelCredentialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** 内存存储；saveCredentials 可放慢，放大「读改写」交错窗口。 */
    private class FakeStorage(
        accounts: List<StoredAccount> = emptyList(),
        creds: Map<String, Credential> = emptyMap(),
        private val slowSaveMs: Long = 0,
    ) : UsageSync.AppStorage {
        @Volatile var accounts = accounts
        @Volatile var creds = creds
        @Volatile var snapshots: List<UsageSnapshot> = emptyList()
        val credentialWrites = AtomicInteger()
        val accountWrites = AtomicInteger()
        override fun loadAccounts() = accounts
        override fun saveAccounts(accounts: List<StoredAccount>) { accountWrites.incrementAndGet(); this.accounts = accounts }
        override fun loadCredentials() = creds
        override fun saveCredentials(creds: Map<String, Credential>) {
            credentialWrites.incrementAndGet()
            if (slowSaveMs > 0) Thread.sleep(slowSaveMs)
            this.creds = creds
        }
        override fun loadSnapshots() = snapshots
        override fun saveSnapshots(list: List<UsageSnapshot>) { snapshots = list }
        override fun loadSettings() = emptyMap<String, String>()
        override fun saveSettings(settings: Map<String, String>) = Unit
    }

    private fun device(interval: Int) = XaiOAuth.DeviceCode(
        deviceCode = "dc", userCode = "ABCD-EFGH",
        verificationUri = "https://x/device", verificationUriComplete = null,
        expiresIn = 600, interval = interval,
    )

    private val tokens = XaiOAuth.Tokens("at-new", "rt-new", 3600)

    /** 推进主线程（含 delay 的虚拟时间），直到条件成立。 */
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

    /** 给后台线程一段时间，同时推进主线程虚拟时间 [virtualMs]。 */
    private fun settle(realMs: Long = 400, virtualMs: Long = 3000) {
        val looper = shadowOf(Looper.getMainLooper())
        val end = System.currentTimeMillis() + realMs
        while (System.currentTimeMillis() < end) {
            looper.idleFor(Duration.ofMillis(virtualMs / 10))
            Thread.sleep(realMs / 10)
        }
    }

    @Test
    fun loginStillAddsAccountWhenNotCancelled() {
        val storage = FakeStorage()
        val vm = MainViewModel(app, storage) {
            XaiLoginFlow(requestDeviceCode = { device(0) }, pollOnce = { XaiOAuth.PollResult.Authorized(tokens) }).run()
        }
        vm.loginXai()
        idleUntil { vm.state.value.message == "Grok 登录成功" }
        val acc = storage.accounts.single()
        assertEquals("xai", acc.providerId)
        assertEquals("at-new", storage.creds.getValue(acc.id).token)
    }

    /** 取消时正有一次轮询在途，用户随后在浏览器授权：在途结果被丢弃，不再轮询。 */
    @Test
    fun cancelDuringInFlightPoll_thenAuthorize_addsNothing() {
        val storage = FakeStorage()
        val polls = AtomicInteger()
        val pollStarted = CountDownLatch(1)
        val userAuthorized = CountDownLatch(1)
        val vm = MainViewModel(app, storage) {
            XaiLoginFlow(
                requestDeviceCode = { device(0) },
                pollOnce = {
                    polls.incrementAndGet()
                    pollStarted.countDown()
                    userAuthorized.await(5, TimeUnit.SECONDS)
                    XaiOAuth.PollResult.Authorized(tokens)
                },
            ).run()
        }
        vm.loginXai()
        idleUntil { vm.state.value.xaiLogin?.userCode == "ABCD-EFGH" }
        assertTrue(pollStarted.await(5, TimeUnit.SECONDS))

        vm.cancelXaiLogin()
        userAuthorized.countDown()
        settle()

        assertEquals(1, polls.get())
        assertTrue(storage.accounts.isEmpty())
        assertEquals(0, storage.accountWrites.get())
        assertEquals(0, storage.credentialWrites.get())
        assertNull(vm.state.value.xaiLogin)
        assertNotEquals("Grok 登录成功", vm.state.value.message)
    }

    /** 取消时正在两次轮询之间等待：之后授权，轮询已停，不会再请求。 */
    @Test
    fun cancelWhileWaiting_thenAuthorize_stopsPolling() {
        val storage = FakeStorage()
        val polls = AtomicInteger()
        val authorized = java.util.concurrent.atomic.AtomicBoolean(false)
        val vm = MainViewModel(app, storage) {
            XaiLoginFlow(
                requestDeviceCode = { device(1) },
                pollOnce = {
                    polls.incrementAndGet()
                    if (authorized.get()) XaiOAuth.PollResult.Authorized(tokens) else XaiOAuth.PollResult.Pending
                },
            ).run()
        }
        vm.loginXai()
        idleUntil { vm.state.value.xaiLogin?.userCode == "ABCD-EFGH" }

        vm.cancelXaiLogin()
        authorized.set(true)
        settle(virtualMs = 10_000)

        assertEquals(0, polls.get())
        assertTrue(storage.accounts.isEmpty())
        assertEquals(0, storage.credentialWrites.get())
        assertNull(vm.state.value.xaiLogin)
    }

    /** 刷新换 token 写回的同时添加、删除账号：三方改动都保留。 */
    @Test
    fun addAndRemoveDuringRefresh_doNotClobberCredentials() {
        val storage = FakeStorage(
            accounts = listOf(
                StoredAccount("x1", Provider.XAI.id, "Grok", 0, 0),
                StoredAccount("k", Provider.KIMI.id, "Kimi", 0, 0),
            ),
            creds = mapOf(
                "x1" to Credential("old-x1", refreshToken = "r-x1", expiresAt = 1L),
                "k" to Credential("tok-k"),
            ),
            slowSaveMs = 150,
        )
        val vm = MainViewModel(app, storage) { error("不应登录") }
        settle(realMs = 100, virtualMs = 100) // 先让 init 里的 reload 跑完

        val refresh = CompletableFuture.supplyAsync {
            runBlocking {
                UsageSync.refreshAll(
                    storage,
                    fetch = { _, _ -> UsageResult(balanceMinor = 1, currency = "USD") },
                    refreshToken = { Thread.sleep(60); XaiOAuth.Tokens("new-x1", "r-x1-2", 3600) },
                )
            }
        }
        vm.addAccount(Provider.DEEPSEEK, "新账号", "tok-new")
        vm.removeAccount("k")

        refresh.get(10, TimeUnit.SECONDS)
        idleUntil { storage.accounts.map { it.id }.let { "k" !in it && it.size == 2 } && storage.creds.size == 2 }
        settle(realMs = 300, virtualMs = 300)

        val added = storage.accounts.single { it.providerId == Provider.DEEPSEEK.id }
        val creds = storage.creds
        assertEquals(setOf("x1", added.id), creds.keys)
        assertEquals("new-x1", creds.getValue("x1").token)
        assertEquals("r-x1-2", creds.getValue("x1").refreshToken)
        assertEquals("tok-new", creds.getValue(added.id).token)
    }
}
