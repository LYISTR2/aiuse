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
import kotlinx.coroutines.withContext
import ltd.usage.tracker.provider.ClaudeOAuth
import ltd.usage.tracker.provider.CodexOAuth
import ltd.usage.tracker.provider.OAuthTokens
import ltd.usage.tracker.provider.UsageFetcher
import ltd.usage.tracker.provider.XaiOAuth
import ltd.usage.tracker.provider.vendors.redact
import java.util.concurrent.ConcurrentHashMap

internal typealias Refresher = (String) -> OAuthTokens

/**
 * 用量同步：前台 ViewModel 与后台 RefreshWorker 共用。
 * 各账号并发拉取（上限 [MAX_PARALLEL]），统一处理 OAuth 自动续期与凭据写锁。
 */
object UsageSync {

    private const val REFRESH_SKEW_MS = 5 * 60 * 1000L
    private const val MAX_SNAPSHOTS = 2000

    internal const val MISSING_CREDENTIAL_ERROR = "缺少凭据，请重新添加"
    internal const val MAX_PARALLEL = 4

    internal fun unknownProviderError(providerId: String) = "不支持的服务商（$providerId），请删除后重新添加"

    private val credentialsLock = Mutex()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    internal suspend fun <T> withCredentialsLock(block: suspend () -> T): T =
        credentialsLock.withLock { block() }

    internal interface Storage {
        fun loadAccounts(): List<StoredAccount>
        fun loadCredentials(): Map<String, Credential>
        fun loadCredentialState(): CredentialState = CredentialState.Available(loadCredentials())
        fun saveCredentials(creds: Map<String, Credential>)
        fun putCredential(id: String, cred: Credential) = saveCredentials(loadCredentials() + (id to cred))
        fun removeCredential(id: String) = saveCredentials(loadCredentials() - id)
        fun loadSnapshots(): List<UsageSnapshot>
        fun saveSnapshots(list: List<UsageSnapshot>)
    }

    internal interface AppStorage : Storage {
        fun saveAccounts(accounts: List<StoredAccount>)
        fun loadSettings(): Map<String, String>
        fun saveSettings(settings: Map<String, String>)
    }

    internal fun Store.asStorage(): AppStorage = object : AppStorage {
        override fun loadAccounts() = this@asStorage.loadAccounts()
        override fun saveAccounts(accounts: List<StoredAccount>) = this@asStorage.saveAccounts(accounts)
        override fun loadCredentials() = this@asStorage.loadCredentials()
        override fun loadCredentialState() = this@asStorage.loadCredentialState()
        override fun saveCredentials(creds: Map<String, Credential>) = this@asStorage.saveCredentials(creds)
        override fun putCredential(id: String, cred: Credential) = this@asStorage.putCredential(id, cred)
        override fun removeCredential(id: String) = this@asStorage.removeCredential(id)
        override fun loadSnapshots() = this@asStorage.loadSnapshots()
        override fun saveSnapshots(list: List<UsageSnapshot>) = this@asStorage.saveSnapshots(list)
        override fun loadSettings() = this@asStorage.loadSettings()
        override fun saveSettings(settings: Map<String, String>) = this@asStorage.saveSettings(settings)
    }

    internal val defaultRefreshers: Map<Provider, Refresher> = mapOf(
        Provider.XAI to { r -> XaiOAuth.refresh(r).toOAuthTokens() },
        Provider.CLAUDE_SUB to ClaudeOAuth::refresh,
        Provider.CHATGPT_SUB to CodexOAuth::refresh,
    )

    private fun XaiOAuth.Tokens.toOAuthTokens() = OAuthTokens(accessToken, refreshToken, expiresIn.toLong())

    suspend fun refreshAll(store: Store): List<UsageSnapshot> = refreshAll(store.asStorage())

    internal suspend fun refreshAll(
        storage: Storage,
        fetch: ((Provider, String) -> UsageResult)? = null,
        refreshToken: ((String) -> XaiOAuth.Tokens)? = null,
        refreshers: Map<Provider, Refresher> = defaultRefreshers,
        fetchCredential: (Provider, Credential) -> UsageResult = UsageFetcher::fetch,
        onlyAccountId: String? = null,
    ): List<UsageSnapshot> = coroutineScope {
        val allAccounts = withContext(Dispatchers.IO) { storage.loadAccounts() }
        val accounts = if (onlyAccountId != null) allAccounts.filter { it.id == onlyAccountId } else allAccounts
        if (accounts.isEmpty()) return@coroutineScope emptyList()

        val credState = withContext(Dispatchers.IO) { storage.loadCredentialState() }
        if (credState is CredentialState.Undecryptable) return@coroutineScope emptyList()

        val doFetch: (Provider, Credential) -> UsageResult =
            if (fetch != null) { p, c -> fetch(p, c.token) } else fetchCredential
        val allRefreshers =
            if (refreshToken != null) refreshers + (Provider.XAI to { r: String -> refreshToken(r).toOAuthTokens() })
            else refreshers

        val creds = (credState as? CredentialState.Available)?.credentials.orEmpty()
        val permits = Semaphore(MAX_PARALLEL)
        val results = accounts.map { acc ->
            async(Dispatchers.IO) {
                permits.withPermit {
                    val provider = Provider.from(acc.providerId)
                    val cred = resolveCredential(storage, allRefreshers, provider, acc.id, creds[acc.id])
                    val result = when {
                        provider == null -> UsageResult(error = unknownProviderError(acc.providerId))
                        cred == null || cred.token.isBlank() -> UsageResult(error = MISSING_CREDENTIAL_ERROR)
                        else -> safeFetch(doFetch, provider, cred)
                    }
                    result.toSnapshot(acc.id, System.currentTimeMillis())
                }
            }
        }.awaitAll()

        withContext(Dispatchers.IO) {
            storage.saveSnapshots((storage.loadSnapshots() + results).takeLast(MAX_SNAPSHOTS))
        }
        results
    }

    private fun safeFetch(fetch: (Provider, Credential) -> UsageResult, provider: Provider, cred: Credential): UsageResult =
        try {
            fetch(provider, cred)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UsageResult(error = redact(e.message ?: e.javaClass.simpleName, cred.token, cred.refreshToken))
        }

    private fun needsRefresh(cred: Credential): Boolean {
        val expiresAt = cred.expiresAt ?: return false
        return System.currentTimeMillis() > expiresAt - REFRESH_SKEW_MS && !cred.refreshToken.isNullOrBlank()
    }

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
            val current = withContext(Dispatchers.IO) { storage.loadCredentials()[accountId] } ?: return@withLock cred
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
            } catch (_: Throwable) {
                current
            }
        }
    }
}
