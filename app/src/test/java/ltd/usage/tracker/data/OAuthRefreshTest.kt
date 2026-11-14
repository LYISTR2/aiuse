package ltd.usage.tracker.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import ltd.usage.tracker.provider.OAuthTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 1.8.0 订阅账号的令牌续期：走 UsageSync 原有的 resolveToken / withCredentialsLock 机制，
 * 新令牌写回存储，accountId 保留并带给请求。
 */
class OAuthRefreshTest {

    private class FakeStorage(
        @Volatile var accounts: List<StoredAccount>,
        creds: Map<String, Credential>,
        private val slowSaveMs: Long = 0,
    ) : UsageSync.Storage {
        @Volatile var creds: Map<String, Credential> = creds
        @Volatile var snapshots: List<UsageSnapshot> = emptyList()
        override fun loadAccounts() = accounts
        override fun loadCredentials() = creds
        override fun saveCredentials(creds: Map<String, Credential>) {
            if (slowSaveMs > 0) Thread.sleep(slowSaveMs)
            this.creds = creds
        }
        override fun loadSnapshots() = snapshots
        override fun saveSnapshots(list: List<UsageSnapshot>) { snapshots = list }
    }

    private fun acc(id: String, p: Provider) = StoredAccount(id, p.id, id, 0, 0)

    @Test
    fun expiredSubscriptionTokens_areRefreshedAndWrittenBack() = runBlocking {
        val now = System.currentTimeMillis()
        val storage = FakeStorage(
            listOf(acc("c", Provider.CLAUDE_SUB), acc("g", Provider.CHATGPT_SUB), acc("fresh", Provider.CLAUDE_SUB)),
            mapOf(
                "c" to Credential("old-c", refreshToken = "r-c", expiresAt = now - 1),
                // 还有 4 分钟过期：在 5 分钟提前量内，也要续
                "g" to Credential("old-g", refreshToken = "r-g", accountId = "acct-1", expiresAt = now + 4 * 60_000),
                "fresh" to Credential("ok-f", refreshToken = "r-f", expiresAt = now + 3_600_000),
            ),
            slowSaveMs = 30,
        )
        val refreshed = Collections.synchronizedList(mutableListOf<String>())
        val seen = Collections.synchronizedMap(mutableMapOf<String, Credential>())
        val results = UsageSync.refreshAll(
            storage,
            refreshers = mapOf(
                Provider.CLAUDE_SUB to { r -> refreshed += r; OAuthTokens("new-c", "r-c-2", 28800) },
                // OpenAI 没轮换 refresh token 时客户端已补回旧值（见 CodexOAuth.refresh），这里模拟轮换
                Provider.CHATGPT_SUB to { r -> refreshed += r; OAuthTokens("new-g", "r-g-2", 864000) },
            ),
            fetchCredential = { p, c -> seen[p.id + ":" + c.token] = c; UsageResult(note = "ok") },
        )

        assertEquals(setOf("r-c", "r-g"), refreshed.toSet())
        assertEquals(3, results.size)
        assertTrue(results.all { it.error == null })

        val c = storage.creds.getValue("c")
        assertEquals("new-c", c.token)
        assertEquals("r-c-2", c.refreshToken)
        assertTrue(c.expiresAt!! > now + 28_000_000)
        val g = storage.creds.getValue("g")
        assertEquals("new-g", g.token)
        assertEquals("r-g-2", g.refreshToken)
        assertEquals("acct-1", g.accountId) // chatgpt_account_id 不丢
        assertEquals("ok-f", storage.creds.getValue("fresh").token)

        // 请求用的是新令牌，并带着 accountId
        assertEquals("acct-1", seen.getValue("chatgpt_sub:new-g").accountId)
        assertTrue(seen.containsKey("claude_sub:new-c"))
        assertTrue(seen.containsKey("claude_sub:ok-f"))
    }

    /** 前台、后台同时刷新同一账号：只换一次（refresh token 一次性，第二次会被拒）。 */
    @Test
    fun concurrentRefreshAll_refreshesSameAccountOnce() = runBlocking {
        val storage = FakeStorage(
            listOf(acc("c", Provider.CLAUDE_SUB)),
            mapOf("c" to Credential("old", refreshToken = "r1", expiresAt = 1L)),
        )
        val calls = AtomicInteger()
        val refresher: (String) -> OAuthTokens = { r ->
            calls.incrementAndGet()
            Thread.sleep(100)
            if (r != "r1") error("refresh token 已被使用")
            OAuthTokens("new", "r2", 3600)
        }
        val tokensUsed = Collections.synchronizedList(mutableListOf<String>())
        (1..2).map {
            async(kotlinx.coroutines.Dispatchers.IO) {
                UsageSync.refreshAll(
                    storage,
                    refreshers = mapOf(Provider.CLAUDE_SUB to refresher),
                    fetchCredential = { _, c -> tokensUsed += c.token; UsageResult() },
                )
            }
        }.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(listOf("new", "new"), tokensUsed.toList())
        assertEquals("r2", storage.creds.getValue("c").refreshToken)
    }

    /** 续期途中账号被删：不把凭据写回去。续期失败：退回旧令牌，存储不变。 */
    @Test
    fun deletedDuringRefresh_notResurrected_andFailureFallsBack() = runBlocking {
        val storage = FakeStorage(
            listOf(acc("c", Provider.CLAUDE_SUB), acc("bad", Provider.CHATGPT_SUB)),
            mapOf(
                "c" to Credential("old", refreshToken = "r1", expiresAt = 1L),
                "bad" to Credential("old-bad", refreshToken = "rb", expiresAt = 1L),
            ),
        )
        val used = Collections.synchronizedMap(mutableMapOf<String, String>())
        UsageSync.refreshAll(
            storage,
            refreshers = mapOf(
                Provider.CLAUDE_SUB to { _ ->
                    // 模拟用户此刻删除了账号 c
                    storage.creds = storage.creds - "c"
                    OAuthTokens("new", "r2", 3600)
                },
                Provider.CHATGPT_SUB to { _ -> error("invalid_grant") },
            ),
            fetchCredential = { p, c -> used[p.id] = c.token; UsageResult() },
        )
        assertNull(storage.creds["c"])
        assertEquals("old-bad", storage.creds.getValue("bad").token)
        assertEquals("old-bad", used["chatgpt_sub"])
    }

    /** xAI 仍走原来的注入点（1.7.1 测试的签名），默认续期表里也有它。 */
    @Test
    fun xaiStillRefreshes_andApiKeyProvidersNever() = runBlocking {
        assertEquals(setOf(Provider.XAI, Provider.CLAUDE_SUB, Provider.CHATGPT_SUB), UsageSync.defaultRefreshers.keys)
        val storage = FakeStorage(
            listOf(acc("x", Provider.XAI), acc("o", Provider.OPENCODE_GO)),
            mapOf(
                "x" to Credential("old-x", refreshToken = "rx", expiresAt = 1L),
                // API Key 账号即便带了过期时间也不续
                "o" to Credential("key-o", refreshToken = "ro", expiresAt = 1L),
            ),
        )
        UsageSync.refreshAll(
            storage,
            fetch = { _, _ -> UsageResult() },
            refreshToken = { ltd.usage.tracker.provider.XaiOAuth.Tokens("new-x", "rx2", 3600) },
        )
        assertEquals("new-x", storage.creds.getValue("x").token)
        assertEquals("key-o", storage.creds.getValue("o").token)
    }
}
