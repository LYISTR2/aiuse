package ltd.usage.tracker.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import ltd.usage.tracker.provider.ClaudeOAuth
import ltd.usage.tracker.provider.CodexOAuth
import ltd.usage.tracker.provider.OAuthTokens
import ltd.usage.tracker.provider.UsageFetcher
import ltd.usage.tracker.provider.XaiOAuth
import java.util.concurrent.ConcurrentHashMap
import ltd.usage.tracker.provider.vendors.redact

/** 一个服务商的续期函数：refresh token → 新令牌。 */
internal typealias Refresher = (String) -> OAuthTokens

/**
 * 用量同步。
 *
 * 前台（[ltd.usage.tracker.ui.MainViewModel]）与后台（[ltd.usage.tracker.work.RefreshWorker]）
 * 共用这一份逻辑，避免两处各写一遍导致行为漂移。
 *
 * 各账号并发拉取，最多同时 [MAX_PARALLEL] 个；网络与磁盘调用都在 Dispatchers.IO 上。
 */
object UsageSync {

    /** 提前 5 分钟刷新 OAuth token，避免请求途中过期。 */
    private const val REFRESH_SKEW_MS = 5 * 60 * 1000L

    /** 历史快照上限，防止文件无限增长。 */
    private const val MAX_SNAPSHOTS = 2000

    internal const val MISSING_CREDENTIAL_ERROR = "缺少凭据，请重新添加"

    internal fun unknownProviderError(providerId: String) = "不支持的服务商（$providerId），请删除后重新添加"

    /** 并发上限：账号多时不至于同时开一堆连接，也不至于串行等太久。 */
    internal const val MAX_PARALLEL = 4

    /**
     * 凭据写锁。凭据写入都是「读全部凭据 → 改一条 → 写全部」，
     * 两处同时写时不加锁会互相覆盖，后写的把先写的改动冲掉。
     * 刷新换 token 写回、添加账号、删除账号都必须持有这把锁（见 [withCredentialsLock]）。
     * 放在 object 上，前台与后台同进程同时刷新时也互斥。
     */
    private val credentialsLock = Mutex()

    /** 持有凭据写锁执行 [block]；块内应重新 load 再 save，不能用锁外读到的旧值。 */
    internal suspend fun <T> withCredentialsLock(block: suspend () -> T): T =
        credentialsLock.withLock { block() }

    /** 同步用到的持久化操作；抽成接口是为了测试能换成内存实现（Robolectric 下没有 KeyStore）。 */
    internal interface Storage {
        fun loadAccounts(): List<StoredAccount>
        fun loadCredentials(): Map<String, Credential>
        fun saveCredentials(creds: Map<String, Credential>)
        fun loadSnapshots(): List<UsageSnapshot>
        fun saveSnapshots(list: List<UsageSnapshot>)
    }

    /** ViewModel 还要读写账号与设置，在同步所需之外再加三项；测试同样换成内存实现。 */
    internal interface AppStorage : Storage {
        fun saveAccounts(accounts: List<StoredAccount>)
        fun loadSettings(): Map<String, String>
        fun saveSettings(settings: Map<String, String>)
    }

    internal fun Store.asStorage(): AppStorage = object : AppStorage {
        override fun loadAccounts() = this@asStorage.loadAccounts()
        override fun saveAccounts(accounts: List<StoredAccount>) { this@asStorage.saveAccounts(accounts) }
        override fun loadCredentials() = this@asStorage.loadCredentials()
        override fun saveCredentials(creds: Map<String, Credential>) { this@asStorage.saveCredentials(creds) }
        override fun loadSnapshots() = this@asStorage.loadSnapshots()
        override fun saveSnapshots(list: List<UsageSnapshot>) { this@asStorage.saveSnapshots(list) }
        override fun loadSettings() = this@asStorage.loadSettings()
        override fun saveSettings(settings: Map<String, String>) { this@asStorage.saveSettings(settings) }
    }

    /** 支持自动续期的服务商（1.8.0 起加了 Claude / ChatGPT 订阅）。 */
    internal val defaultRefreshers: Map<Provider, Refresher> = mapOf(
        Provider.XAI to { r -> XaiOAuth.refresh(r).toOAuthTokens() },
        Provider.CLAUDE_SUB to ClaudeOAuth::refresh,
        Provider.CHATGPT_SUB to CodexOAuth::refresh,
    )

    private fun XaiOAuth.Tokens.toOAuthTokens() = OAuthTokens(accessToken, refreshToken, expiresIn.toLong())

    /**
     * 刷新所有账号并落库，返回本次产生的快照（顺序与账号列表一致）。
     *
     * 单个账号失败不影响其他账号：失败原因写进该账号快照的 error 字段。
     */
    suspend fun refreshAll(store: Store): List<UsageSnapshot> = refreshAll(store.asStorage())

    /**
     * 可注入版本。默认全部走真实网络。
     *
     * [fetch] / [refreshToken] 是 1.7.x 的测试注入点，保留原签名：传了 [fetch] 就只把 token 交给它；
     * 传了 [refreshToken] 就替换 xAI 的续期。新代码用 [fetchCredential] / [refreshers]。
     */
    internal suspend fun refreshAll(
        storage: Storage,
        fetch: ((Provider, String) -> UsageResult)? = null,
        refreshToken: ((String) -> XaiOAuth.Tokens)? = null,
        refreshers: Map<Provider, Refresher> = defaultRefreshers,
        fetchCredential: (Provider, Credential) -> UsageResult = UsageFetcher::fetch,
    ): List<UsageSnapshot> = coroutineScope {
        val accounts = runIo { storage.loadAccounts() }
        if (accounts.isEmpty()) return@coroutineScope emptyList()

        val doFetch: (Provider, Credential) -> UsageResult =
            if (fetch != null) { p, c -> fetch(p, c.token) } else fetchCredential
        val allRefreshers =
            if (refreshToken != null) refreshers + (Provider.XAI to { r: String -> refreshToken(r).toOAuthTokens() })
            else refreshers

        val creds = runIo { storage.loadCredentials() }
        val permits = Semaphore(MAX_PARALLEL)
        val results = accounts.map { acc ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    val provider = Provider.from(acc.providerId)
                    val cred = resolveCredential(storage, allRefreshers, provider, acc.id, creds[acc.id])
                    // 缺凭据 / 未知服务商以前产出一条全空快照，界面上只是一行空白；现在给出原因
                    val result = when {
                        provider == null -> UsageResult(error = unknownProviderError(acc.providerId))
                        cred == null || cred.token.isBlank() -> UsageResult(error = MISSING_CREDENTIAL_ERROR)
                        else -> safeFetch(doFetch, provider, cred)
                    }
                    result.toSnapshot(acc.id, System.currentTimeMillis())
                }
            }
        }.awaitAll()

        runIo { storage.saveSnapshots((storage.loadSnapshots() + results).takeLast(MAX_SNAPSHOTS)) }
        results
    }

    private suspend fun <T> runIo(block: () -> T): T =
        kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    /**
     * 并发后，某个账号抛出的异常会取消同一批的其他账号；这里兜住，变成该账号自己的 error。
     * 生产的 [UsageFetcher.fetch] 本身已经捕获异常，这一层只防注入实现或未来改动漏抓。
     */
    private fun safeFetch(fetch: (Provider, Credential) -> UsageResult, provider: Provider, cred: Credential): UsageResult =
        try {
            fetch(provider, cred)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UsageResult(error = redact(e.message ?: e.javaClass.simpleName, cred.token))
        }

    /**
     * 每个账号一把续期锁：同一账号的两次刷新（前台 + 后台 Worker 同时跑）不能都拿旧 refresh token 去换 ——
     * Claude / OpenAI 的 refresh token 一次性轮换，第二次会 invalid_grant，OpenAI 还可能因此作废整条令牌链。
     * 只锁同一账号，不同账号的续期仍然并发；凭据写入仍走全局 [credentialsLock]。
     */
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    private fun needsRefresh(cred: Credential): Boolean {
        val expiresAt = cred.expiresAt ?: return false
        return System.currentTimeMillis() > expiresAt - REFRESH_SKEW_MS && !cred.refreshToken.isNullOrBlank()
    }

    /**
     * 取可用凭据；OAuth 令牌（xAI 约 6 小时、Claude 约 8 小时、ChatGPT 看 JWT exp）快过期时
     * 用 refresh token 换新，并把新令牌写回加密存储。
     *
     * 拿到该账号的续期锁后先从存储重读：等锁期间别的刷新可能已经换过令牌，直接用新的，不再重复换。
     * 网络请求在全局凭据锁之外，全局锁内只做「重读 → 改一条 → 写」，避免一个慢请求拖住其他账号。
     * 写回前确认账号还在：刷新途中被删掉的账号不会被写回来。
     */
    private suspend fun resolveCredential(
        storage: Storage,
        refreshers: Map<Provider, Refresher>,
        provider: Provider?,
        accountId: String,
        cred: Credential?,
    ): Credential? {
        if (cred == null) return null
        val refresher = provider?.let { refreshers[it] } ?: return cred
        if (!needsRefresh(cred)) return cred

        return refreshLocks.getOrPut(accountId) { Mutex() }.withLock {
            // 账号已被删除：不续期，用手上的旧值走完这一轮
            val current = runIo { storage.loadCredentials()[accountId] } ?: return@withLock cred
            if (!needsRefresh(current)) return@withLock current
            val refresh = current.refreshToken!!
            try {
                val fresh = refresher(refresh)
                val updated = current.copy(
                    token = fresh.accessToken,
                    refreshToken = fresh.refreshToken ?: refresh,
                    expiresAt = System.currentTimeMillis() + fresh.expiresIn * 1000L,
                )
                withCredentialsLock {
                    val all = storage.loadCredentials()
                    if (accountId in all) storage.saveCredentials(all + (accountId to updated))
                }
                updated
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 与 1.7.x 一致：续期失败就退回旧 token，让本次请求自己报错（通常是 401，提示重新登录）
                current
            }
        }
    }
}
