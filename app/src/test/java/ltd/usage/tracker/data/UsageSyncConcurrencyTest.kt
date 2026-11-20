package ltd.usage.tracker.data

import kotlinx.coroutines.runBlocking
import ltd.usage.tracker.provider.XaiOAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * 并发刷新护栏：
 *  1. 单个账号抛异常 / 返回错误不影响其他账号，结果顺序与账号列表一致；
 *  2. 同时并发不超过 4 个，且确实并发（不是退化成串行）；
 *  3. 两个 xAI 账号同时换 token，写回凭据不互相覆盖。
 */
class UsageSyncConcurrencyTest {

    /** 内存存储；saveCredentials 故意放慢，放大「读改写」交错窗口。 */
    private class FakeStorage(
        private val accounts: List<StoredAccount>,
        creds: Map<String, Credential>,
    ) : UsageSync.Storage {
        @Volatile var creds: Map<String, Credential> = creds
        @Volatile var snapshots: List<UsageSnapshot> = emptyList()
        override fun loadAccounts() = accounts
        override fun loadCredentials() = creds
        override fun saveCredentials(creds: Map<String, Credential>) {
            Thread.sleep(50)
            this.creds = creds
        }
        override fun loadSnapshots() = snapshots
        override fun saveSnapshots(list: List<UsageSnapshot>) { snapshots = list }
    }

    private fun acc(id: String, provider: Provider) = StoredAccount(id, provider.id, id, 0, 0)

    @Test
    fun oneFailureDoesNotAffectOthers_andCredentialsDoNotClobber() = runBlocking {
        val accounts = listOf(
            acc("x1", Provider.XAI),
            acc("boom", Provider.DEEPSEEK),
            acc("x2", Provider.XAI),
            acc("bad", Provider.OPENAI),
            acc("k", Provider.KIMI),
            acc("m", Provider.MISTRAL),
            acc("nocred", Provider.ZHIPU),
        )
        val expired = 1L // 早已过期，必然触发刷新
        val storage = FakeStorage(
            accounts,
            mapOf(
                "x1" to Credential("old-x1", refreshToken = "r-x1", expiresAt = expired),
                "x2" to Credential("old-x2", refreshToken = "r-x2", expiresAt = expired),
                "boom" to Credential("tok-boom-123456"),
                "bad" to Credential("tok-bad"),
                "k" to Credential("tok-k"),
                "m" to Credential("tok-m"),
            ),
        )

        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val results = UsageSync.refreshAll(
            storage,
            fetch = { provider, token ->
                val n = inFlight.incrementAndGet()
                peak.accumulateAndGet(n) { a, b -> maxOf(a, b) }
                try {
                    Thread.sleep(100)
                    when (token) {
                        "tok-boom-123456" -> throw IllegalStateException("连接被重置 tok-boom-123456")
                        "tok-bad" -> UsageResult(error = "HTTP 401: unauthorized")
                        else -> UsageResult(balanceMinor = 100, currency = "USD", note = "${provider.id}:$token")
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            },
            refreshToken = { refresh ->
                Thread.sleep(30)
                XaiOAuth.Tokens(accessToken = "new-" + refresh.removePrefix("r-"), refreshToken = "$refresh-2", expiresIn = 3600)
            },
        )

        // 顺序与账号列表一致
        assertEquals(accounts.map { it.id }, results.map { it.accountId })
        val byId = results.associateBy { it.accountId }

        // 正常账号照常出结果，且用的是换过的新 token
        assertEquals("xai:new-x1", byId.getValue("x1").note)
        assertEquals("xai:new-x2", byId.getValue("x2").note)
        assertEquals(100L, byId.getValue("k").balanceMinor)
        assertEquals(100L, byId.getValue("m").balanceMinor)

        // 抛异常的账号只影响自己，错误里 token 已脱敏
        val boom = byId.getValue("boom")
        assertNull(boom.balanceMinor)
        assertTrue(boom.error!!.startsWith("连接被重置"))
        assertTrue("token 不应出现在错误里: ${boom.error}", "tok-boom-123456" !in boom.error!!)
        assertEquals("HTTP 401: unauthorized", byId.getValue("bad").error)

        // 并发但不超过上限
        assertTrue("应当并发，peak=${peak.get()}", peak.get() > 1)
        assertTrue("并发不应超过 ${UsageSync.MAX_PARALLEL}，peak=${peak.get()}", peak.get() <= UsageSync.MAX_PARALLEL)

        // 两个 xAI 账号的新凭据都在，没有互相覆盖；其他账号凭据原样保留
        val saved = storage.creds
        assertEquals("new-x1", saved.getValue("x1").token)
        assertEquals("r-x1-2", saved.getValue("x1").refreshToken)
        assertEquals("new-x2", saved.getValue("x2").token)
        assertEquals("r-x2-2", saved.getValue("x2").refreshToken)
        assertEquals("tok-k", saved.getValue("k").token)
        assertEquals(6, saved.size)

        // 快照已落库
        assertEquals(results, storage.snapshots)
    }

    @Test
    fun refreshAllSkipsFetchAndWritesWhenUndecryptable() = runBlocking {
        var snapshotsWritten = false
        var credsWritten = false
        val storage = object : UsageSync.Storage {
            override fun loadAccounts() = listOf(acc("a1", Provider.DEEPSEEK))
            override fun loadCredentials() = emptyMap<String, Credential>()
            override fun loadCredentialState() = CredentialState.Undecryptable
            override fun saveCredentials(creds: Map<String, Credential>) { credsWritten = true }
            override fun loadSnapshots() = emptyList<UsageSnapshot>()
            override fun saveSnapshots(list: List<UsageSnapshot>) { snapshotsWritten = true }
        }
        val results = UsageSync.refreshAll(
            storage,
            fetch = { _, _ -> error("解密失败时不应发起请求") },
        )
        assertTrue(results.isEmpty())
        assertTrue("不应写快照", !snapshotsWritten)
        assertTrue("不应写凭据", !credsWritten)
    }
}
