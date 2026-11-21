package ltd.usage.tracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ltd.usage.tracker.UsageApp
import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.CredentialState
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.data.UsageSync
import ltd.usage.tracker.fx.Fx
import ltd.usage.tracker.provider.ClaudeOAuth
import ltd.usage.tracker.provider.CodexLoginFlow
import ltd.usage.tracker.provider.OAuthException
import ltd.usage.tracker.provider.OAuthTokens
import ltd.usage.tracker.provider.Pkce
import ltd.usage.tracker.provider.XaiLoginFlow
import java.util.UUID

enum class SortMode(val id: String, val label: String) {
    MANUAL("manual", "手动排序"),
    BALANCE("balance", "按余额"),
    NAME("name", "按名称"),
    PROVIDER("provider", "按服务商"),
    ;

    companion object {
        fun from(id: String?): SortMode = entries.firstOrNull { it.id == id } ?: MANUAL
    }
}

data class UiState(
    val accounts: List<StoredAccount> = emptyList(),
    val snapshots: List<UsageSnapshot> = emptyList(),
    val refreshing: Boolean = false,
    val message: String? = null,
    val xaiLogin: XaiLogin? = null,
    val dark: Boolean = true,
    val displayCurrency: String = "USD",
    val fxLive: Boolean = false,
    val sortMode: SortMode = SortMode.MANUAL,
    val oauthLogin: OAuthLogin? = null,
    val dynamicColor: Boolean = true,
    val credentialsUndecryptable: Boolean = false,
    val accountsNeedingRelogin: Set<String> = emptySet(),
    val refreshingIds: Set<String> = emptySet(),
)

data class OAuthLogin(
    val provider: Provider,
    val userCode: String = "",
    val verificationUri: String = "",
    val authorizeUrl: String = "",
    val status: String = "等待浏览器授权…",
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val pasteMode: Boolean get() = provider == Provider.CLAUDE_SUB
}

internal class OAuthLogins(
    val claudeBegin: () -> ClaudeOAuth.Pending = { ClaudeOAuth.begin() },
    val claudeExchange: (code: String, pkce: Pkce) -> OAuthTokens = ClaudeOAuth::exchange,
    val codexEvents: () -> Flow<CodexLoginFlow.Event> = { CodexLoginFlow().run() },
)

data class XaiLogin(
    val userCode: String,
    val verificationUri: String,
    val status: String = "等待浏览器授权…",
)

class MainViewModel internal constructor(
    app: Application,
    private val store: UsageSync.AppStorage,
    private val oauth: OAuthLogins = OAuthLogins(),
    private val xaiLoginEvents: () -> Flow<XaiLoginFlow.Event>,
) : AndroidViewModel(app) {

    constructor(app: Application) : this(
        app,
        with(UsageSync) { (app as UsageApp).store.asStorage() },
        OAuthLogins(),
        { XaiLoginFlow().run() },
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** O(1) 快照索引，避免每次重组扫描全部历史快照。 */
    @Volatile
    private var latestIndex: Map<String, UsageSnapshot> = emptyMap()

    private val prefsLock = Mutex()
    private var xaiLoginJob: Job? = null
    @Volatile private var xaiLoginSession = 0

    private var oauthLoginJob: Job? = null
    @Volatile private var oauthLoginSession = 0
    @Volatile private var claudePending: Pair<ClaudeOAuth.Pending, String?>? = null

    init {
        val s = store.loadSettings()
        _state.update {
            it.copy(
                dark = s["dark"]?.toBooleanStrictOrNull() ?: true,
                displayCurrency = s["displayCurrency"]?.takeIf { c -> c == "USD" || c == "CNY" } ?: "USD",
                sortMode = SortMode.from(s["sortMode"]),
                dynamicColor = s["dynamicColor"]?.toBooleanStrictOrNull() ?: true,
            )
        }
        reload()
        refreshFx()
    }

    private fun refreshFx() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Fx.refresh() }
            _state.update { it.copy(fxLive = Fx.isLive()) }
        }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    private fun savePref(key: String, value: String) = io {
        prefsLock.withLock { store.saveSettings(store.loadSettings() + (key to value)) }
    }

    private fun indexLatest(snapshots: List<UsageSnapshot>): Map<String, UsageSnapshot> {
        val map = HashMap<String, UsageSnapshot>()
        for (s in snapshots) {
            val prev = map[s.accountId]
            if (prev == null || s.capturedAt >= prev.capturedAt) map[s.accountId] = s
        }
        return map
    }

    fun setDark(dark: Boolean) {
        _state.update { it.copy(dark = dark) }
        savePref("dark", dark.toString())
    }

    fun setDisplayCurrency(currency: String) {
        if (currency != "USD" && currency != "CNY") return
        _state.update { it.copy(displayCurrency = currency) }
        savePref("displayCurrency", currency)
    }

    fun setDynamicColor(enabled: Boolean) {
        _state.update { it.copy(dynamicColor = enabled) }
        savePref("dynamicColor", enabled.toString())
    }

    fun setSortMode(mode: SortMode) {
        _state.update { it.copy(sortMode = mode) }
        savePref("sortMode", mode.id)
    }

    fun moveAccount(from: Int, to: Int) {
        val current = _state.value.accounts
        if (from !in current.indices || to !in current.indices || from == to) return
        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        _state.update { it.copy(accounts = reordered) }
        io { store.saveAccounts(reordered) }
    }

    fun reload() {
        viewModelScope.launch {
            val (accounts, snapshots, credState) = withContext(Dispatchers.IO) {
                Triple(store.loadAccounts(), store.loadSnapshots(), store.loadCredentialState())
            }
            latestIndex = indexLatest(snapshots)
            val missing = when (credState) {
                is CredentialState.Available ->
                    accounts.map { it.id }.filter { it !in credState.credentials }.toSet()
                is CredentialState.Missing, is CredentialState.Undecryptable ->
                    accounts.map { it.id }.toSet()
            }
            _state.update {
                it.copy(
                    accounts = accounts,
                    snapshots = snapshots,
                    credentialsUndecryptable = credState is CredentialState.Undecryptable,
                    accountsNeedingRelogin = missing,
                )
            }
        }
    }

    fun addAccount(provider: Provider, name: String, token: String) {
        if (token.isBlank()) {
            _state.update { it.copy(message = "API Key 不能为空") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                saveAccountLocked(provider, name.ifBlank { provider.displayName }) { Credential(token = token) }
            }
            _state.update { it.copy(message = "已添加 ${provider.displayName}") }
            reload()
        }
    }

    fun updateAccountToken(accountId: String, token: String) {
        if (token.isBlank()) {
            _state.update { it.copy(message = "API Key 不能为空") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                UsageSync.withCredentialsLock {
                    val now = System.currentTimeMillis()
                    store.saveAccounts(store.loadAccounts().map {
                        if (it.id == accountId) it.copy(updatedAt = now) else it
                    })
                    store.putCredential(accountId, Credential(token = token))
                }
            }
            _state.update { it.copy(message = "已更新登录信息") }
            reload()
        }
    }

    private suspend fun saveAccountLocked(
        provider: Provider,
        name: String,
        replaceAccountId: String? = null,
        beforeWrite: () -> Unit = {},
        credential: (now: Long) -> Credential,
    ) = UsageSync.withCredentialsLock {
        beforeWrite()
        val now = System.currentTimeMillis()
        val accounts = store.loadAccounts()
        val existing = replaceAccountId?.let { id -> accounts.firstOrNull { it.id == id } }
        if (existing != null) {
            store.saveAccounts(accounts.map { if (it.id == existing.id) it.copy(updatedAt = now) else it })
            store.putCredential(existing.id, credential(now))
        } else {
            val account = StoredAccount(
                id = "a-" + UUID.randomUUID().toString().take(8),
                providerId = provider.id,
                name = name,
                createdAt = now,
                updatedAt = now,
            )
            store.saveAccounts(accounts + account)
            store.putCredential(account.id, credential(now))
        }
    }

    // ---------------------------------------------------------------- xAI 登录

    fun loginXai(replaceAccountId: String? = null) {
        if (_state.value.xaiLogin != null || _state.value.oauthLogin != null) return
        _state.update { it.copy(xaiLogin = XaiLogin("", "", "正在获取设备码…")) }
        val session = ++xaiLoginSession
        xaiLoginJob = viewModelScope.launch {
            xaiLoginEvents().collect { event ->
                when (event) {
                    is XaiLoginFlow.Event.CodeReady ->
                        _state.update { it.copy(xaiLogin = XaiLogin(event.userCode, event.verificationUri)) }
                    is XaiLoginFlow.Event.Authorized -> {
                        withContext(Dispatchers.IO) {
                            saveAccountLocked(
                                provider = Provider.XAI,
                                name = "Grok 订阅",
                                replaceAccountId = replaceAccountId,
                                beforeWrite = {
                                    ensureActive()
                                    if (session != xaiLoginSession) throw CancellationException("xAI 登录已取消")
                                },
                            ) { now ->
                                Credential(
                                    token = event.tokens.accessToken,
                                    refreshToken = event.tokens.refreshToken,
                                    expiresAt = now + event.tokens.expiresIn * 1000L,
                                )
                            }
                        }
                        _state.update { it.copy(xaiLogin = null, message = "Grok 登录成功") }
                        reload()
                    }
                    is XaiLoginFlow.Event.Failed ->
                        _state.update { it.copy(xaiLogin = null, message = event.message) }
                }
            }
        }
    }

    fun cancelXaiLogin() {
        xaiLoginSession++
        xaiLoginJob?.cancel()
        xaiLoginJob = null
        _state.update { it.copy(xaiLogin = null) }
    }

    // ---------------------------------------------------------------- Claude / ChatGPT 订阅登录

    fun loginClaude(replaceAccountId: String? = null) {
        if (_state.value.oauthLogin != null || _state.value.xaiLogin != null) return
        oauthLoginSession++
        val pending = runCatching { oauth.claudeBegin() }.getOrElse { e ->
            _state.update { it.copy(message = "无法开始登录: ${e.message ?: e.javaClass.simpleName}") }
            return
        }
        claudePending = pending to replaceAccountId
        _state.update {
            it.copy(
                oauthLogin = OAuthLogin(
                    provider = Provider.CLAUDE_SUB,
                    authorizeUrl = pending.authorizeUrl,
                    status = "授权后页面会显示授权码，复制回来粘贴",
                )
            )
        }
    }

    fun submitClaudeCode(pasted: String) {
        val login = _state.value.oauthLogin ?: return
        if (!login.pasteMode || login.submitting) return
        val (pending, replaceId) = claudePending ?: return
        val parsed = ClaudeOAuth.splitPasted(pasted)
        if (parsed == null) {
            _state.update { it.copy(oauthLogin = login.copy(error = "没有识别到授权码，请完整复制页面上的内容")) }
            return
        }
        val (code, state) = parsed
        if (state != null && state != pending.pkce.state) {
            _state.update { it.copy(oauthLogin = login.copy(error = "授权码不属于本次登录，请重新打开授权页面")) }
            return
        }
        _state.update { it.copy(oauthLogin = login.copy(submitting = true, error = null, status = "正在完成登录…")) }

        val session = oauthLoginSession
        oauthLoginJob = viewModelScope.launch {
            val tokens = withContext(Dispatchers.IO) { runCatching { oauth.claudeExchange(code, pending.pkce) } }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    if (session == oauthLoginSession) {
                        _state.update {
                            it.copy(
                                oauthLogin = it.oauthLogin?.copy(
                                    submitting = false,
                                    status = "授权后页面会显示授权码，复制回来粘贴",
                                    error = loginError(e),
                                )
                            )
                        }
                    }
                    return@launch
                }
            saveOAuthAccount(session, Provider.CLAUDE_SUB, "Claude 订阅", tokens, accountId = null, replaceAccountId = replaceId)
            claudePending = null
            _state.update { it.copy(oauthLogin = null, message = "Claude 登录成功") }
            reload()
        }
    }

    fun loginChatGpt(replaceAccountId: String? = null) {
        if (_state.value.oauthLogin != null || _state.value.xaiLogin != null) return
        _state.update { it.copy(oauthLogin = OAuthLogin(provider = Provider.CHATGPT_SUB, status = "正在获取设备码…")) }
        val session = ++oauthLoginSession
        oauthLoginJob = viewModelScope.launch {
            oauth.codexEvents().collect { event ->
                when (event) {
                    is CodexLoginFlow.Event.CodeReady -> _state.update {
                        it.copy(
                            oauthLogin = OAuthLogin(
                                provider = Provider.CHATGPT_SUB,
                                userCode = event.userCode,
                                verificationUri = event.verificationUri,
                            )
                        )
                    }
                    is CodexLoginFlow.Event.Authorized -> {
                        saveOAuthAccount(
                            session = session,
                            provider = Provider.CHATGPT_SUB,
                            name = "ChatGPT 订阅",
                            tokens = event.login.tokens,
                            accountId = event.login.accountId,
                            replaceAccountId = replaceAccountId,
                        )
                        _state.update { it.copy(oauthLogin = null, message = "ChatGPT 登录成功") }
                        reload()
                    }
                    is CodexLoginFlow.Event.Failed ->
                        _state.update { it.copy(oauthLogin = null, message = event.message) }
                }
            }
        }
    }

    fun cancelOAuthLogin() {
        oauthLoginSession++
        oauthLoginJob?.cancel()
        oauthLoginJob = null
        claudePending = null
        _state.update { it.copy(oauthLogin = null) }
    }

    private suspend fun saveOAuthAccount(
        session: Int,
        provider: Provider,
        name: String,
        tokens: OAuthTokens,
        accountId: String?,
        replaceAccountId: String?,
    ) = withContext(Dispatchers.IO) {
        saveAccountLocked(
            provider = provider,
            name = name,
            replaceAccountId = replaceAccountId,
            beforeWrite = {
                ensureActive()
                if (session != oauthLoginSession) throw CancellationException("登录已取消")
            },
        ) { now ->
            Credential(
                token = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                accountId = accountId,
                expiresAt = now + tokens.expiresIn * 1000L,
            )
        }
    }

    private fun loginError(e: Throwable): String =
        if (e is OAuthException) e.message.orEmpty() else "登录失败: ${e.message ?: e.javaClass.simpleName}"

    // ---------------------------------------------------------------- 删除与刷新

    fun removeAccount(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                UsageSync.withCredentialsLock {
                    store.saveAccounts(store.loadAccounts().filterNot { it.id == id })
                    store.removeCredential(id)
                }
            }
            reload()
        }
    }

    fun refreshAll() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true, message = null) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Fx.refresh() }
            val result = withContext(Dispatchers.IO) { runCatching { UsageSync.refreshAll(store) } }
            val loaded = withContext(Dispatchers.IO) { store.loadSnapshots() }
            latestIndex = indexLatest(loaded)
            val error = result.exceptionOrNull()
            _state.update {
                it.copy(
                    refreshing = false,
                    message = if (error != null) "刷新失败: ${error.message ?: error.javaClass.simpleName}" else "刷新完成",
                    snapshots = loaded,
                    fxLive = Fx.isLive(),
                )
            }
        }
    }

    fun refreshAccount(accountId: String) {
        if (_state.value.refreshing || accountId in _state.value.refreshingIds) return
        _state.update { it.copy(refreshingIds = it.refreshingIds + accountId) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { UsageSync.refreshAll(store, onlyAccountId = accountId) }
            }
            val loaded = withContext(Dispatchers.IO) { store.loadSnapshots() }
            latestIndex = indexLatest(loaded)
            _state.update {
                it.copy(
                    refreshingIds = it.refreshingIds - accountId,
                    snapshots = loaded,
                    message = result.exceptionOrNull()?.let { e -> "刷新失败: ${e.message ?: e.javaClass.simpleName}" },
                )
            }
        }
    }

    fun latestFor(accountId: String): UsageSnapshot? = latestIndex[accountId]

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }
}

internal fun sortAccounts(
    accounts: List<StoredAccount>,
    mode: SortMode,
    displayCurrency: String,
    latestFor: (String) -> UsageSnapshot?,
): List<StoredAccount> = when (mode) {
    SortMode.MANUAL -> accounts
    SortMode.NAME -> accounts.sortedWith(
        compareBy<StoredAccount, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
    )
    SortMode.PROVIDER -> accounts.sortedWith(
        compareBy<StoredAccount, String>(String.CASE_INSENSITIVE_ORDER) {
            Provider.from(it.providerId)?.displayName ?: it.providerId
        }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    )
    SortMode.BALANCE -> {
        val amounts = accounts.associate { acc -> acc.id to snapshotBalanceIn(latestFor(acc.id), displayCurrency) }
        accounts.sortedWith(
            compareBy<StoredAccount> { amounts[it.id] == null }
                .thenByDescending { amounts[it.id] ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }
}

/** 从单条快照提取换算到 [target] 的余额；供排序与汇总共用。 */
internal fun snapshotBalanceIn(snap: UsageSnapshot?, target: String): Long? {
    if (snap == null || snap.error != null) return null
    val fromMetrics = snap.metrics.mapNotNull { m ->
        m.balanceMinor?.let { (m.currency ?: snap.currency ?: target) to it }
    }
    if (fromMetrics.isNotEmpty()) {
        var sum = 0L
        var any = false
        for ((cur, v) in fromMetrics) {
            runCatching { Fx.convert(v, cur, target) }.getOrNull()?.let { sum += it; any = true }
        }
        return if (any) sum else null
    }
    val minor = snap.balanceMinor ?: return null
    return runCatching { Fx.convert(minor, snap.currency ?: target, target) }.getOrNull()
}
