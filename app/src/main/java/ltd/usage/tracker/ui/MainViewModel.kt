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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ltd.usage.tracker.UsageApp
import ltd.usage.tracker.data.Credential
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 列表排序方式。
 *
 * [MANUAL] 是唯一允许用户自己拖动的模式：此时列表顺序 = `accounts.json`
 * 的数组顺序，拖动即改写该顺序并落盘。其余模式是每次渲染时按规则重排，
 * 不改动存储顺序 —— 切回 [MANUAL] 仍能看到用户自己排的那一版。
 */
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
    /** xAI 设备码登录进行中时的提示信息。 */
    val xaiLogin: XaiLogin? = null,
    /** 深色 / 浅色。 */
    val dark: Boolean = true,
    /** 汇总展示币种：USD 或 CNY。 */
    val displayCurrency: String = "USD",
    /** 汇率是否为实时值；false 表示用了缓存或内置兜底。 */
    val fxLive: Boolean = false,
    /** 当前排序方式。 */
    val sortMode: SortMode = SortMode.MANUAL,
    /** Claude / ChatGPT 订阅登录进行中（1.8.0）；与 [xaiLogin] 互斥。 */
    val oauthLogin: OAuthLogin? = null,
)

/**
 * 订阅登录的进行态。
 *
 * [provider] 为 [Provider.CLAUDE_SUB] 时是「粘贴授权码」模式：显示 [authorizeUrl]，等用户粘贴；
 * 为 [Provider.CHATGPT_SUB] 时是设备码模式：显示 [userCode] 与 [verificationUri]，后台轮询。
 */
data class OAuthLogin(
    val provider: Provider,
    val userCode: String = "",
    val verificationUri: String = "",
    val authorizeUrl: String = "",
    val status: String = "等待浏览器授权…",
    /** 正在用授权码换令牌（粘贴模式）。 */
    val submitting: Boolean = false,
    /** 上一次提交失败的原因（粘贴模式），对话框里红字显示，可重新粘贴。 */
    val error: String? = null,
) {
    val pasteMode: Boolean get() = provider == Provider.CLAUDE_SUB
}

/**
 * 订阅登录的依赖，测试可替换；生产走真实网络。
 * [claudeBegin] 只在本地生成 PKCE 与授权地址，不发请求。
 */
internal class OAuthLogins(
    val claudeBegin: () -> ClaudeOAuth.Pending = { ClaudeOAuth.begin() },
    val claudeExchange: (code: String, pkce: Pkce) -> OAuthTokens = ClaudeOAuth::exchange,
    val codexEvents: () -> Flow<CodexLoginFlow.Event> = { CodexLoginFlow().run() },
)

/** xAI 设备码登录的进行态。 */
data class XaiLogin(
    val userCode: String,
    val verificationUri: String,
    val status: String = "等待浏览器授权…",
)

class MainViewModel internal constructor(
    app: Application,
    private val store: UsageSync.AppStorage,
    /** Claude / ChatGPT 订阅登录依赖（1.8.0）；放在 xAI 之前，1.7.1 测试的尾随 lambda 仍落在 xAI 上。 */
    private val oauth: OAuthLogins = OAuthLogins(),
    /** xAI 登录事件源；测试可换成假的设备码 / 轮询。 */
    private val xaiLoginEvents: () -> Flow<XaiLoginFlow.Event>,
) : AndroidViewModel(app) {

    /** 系统 ViewModelProvider 通过反射调用这个 (Application) 构造器。 */
    constructor(app: Application) : this(
        app,
        with(UsageSync) { (app as UsageApp).store.asStorage() },
        OAuthLogins(),
        { XaiLoginFlow().run() },
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val s = store.loadSettings()
        _state.value = _state.value.copy(
            dark = s["dark"]?.toBooleanStrictOrNull() ?: true,
            displayCurrency = s["displayCurrency"]?.takeIf { it == "USD" || it == "CNY" } ?: "USD",
            sortMode = SortMode.from(s["sortMode"]),
        )
        reload()
        refreshFx()
    }

    private fun refreshFx() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Fx.refresh() }
            _state.value = _state.value.copy(fxLive = Fx.isLive())
        }
    }

    /** 后台写盘，不等结果：界面状态已先行更新，磁盘慢不能拖住交互。 */
    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    // 设置是「读整份 → 改一项 → 写整份」，连续切换两个设置时两次写入可能交错，后写的覆盖先写的；串行化避免丢项
    private val prefsLock = Mutex()

    /** 写一项偏好。 */
    private fun savePref(key: String, value: String) = io {
        prefsLock.withLock { store.saveSettings(store.loadSettings() + (key to value)) }
    }

    fun setDark(dark: Boolean) {
        _state.value = _state.value.copy(dark = dark)
        savePref("dark", dark.toString())
    }

    fun setDisplayCurrency(currency: String) {
        if (currency != "USD" && currency != "CNY") return
        _state.value = _state.value.copy(displayCurrency = currency)
        savePref("displayCurrency", currency)
    }

    /**
     * 切换排序方式并持久化。
     *
     * 只存「用哪种规则」，不重写 accounts.json —— 自动排序是视图层的，
     * 用户手动排的顺序必须原样留着，切回 [SortMode.MANUAL] 才能复原。
     */
    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(sortMode = mode)
        savePref("sortMode", mode.id)
    }

    /**
     * 手动排序：把 [from] 位置的账号挪到 [to] 位置并落盘。
     *
     * 只在 [SortMode.MANUAL] 下生效 —— 自动排序时列表顺序由规则决定，
     * 拖动结果下一秒就会被重排覆盖，允许拖动只会让人困惑。
     */
    fun moveAccount(from: Int, to: Int) {
        val current = _state.value.accounts
        if (from !in current.indices || to !in current.indices || from == to) return
        val reordered = current.toMutableList().apply { add(to, removeAt(from)) }
        // 先更新 UI，落盘在后台；拖动要跟手，不能等磁盘
        _state.value = _state.value.copy(accounts = reordered)
        io { store.saveAccounts(reordered) }
    }

    fun reload() {
        viewModelScope.launch {
            val (accounts, snapshots) = withContext(Dispatchers.IO) {
                store.loadAccounts() to store.loadSnapshots()
            }
            _state.value = _state.value.copy(
                accounts = accounts,
                snapshots = snapshots,
            )
        }
    }

    fun addAccount(provider: Provider, name: String, token: String) {
        if (token.isBlank()) {
            _state.value = _state.value.copy(message = "API Key 不能为空")
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                saveNewAccount(provider, name.ifBlank { provider.displayName }) { Credential(token = token) }
            }
            _state.value = _state.value.copy(message = "已添加 ${provider.displayName}")
            reload()
        }
    }

    /**
     * 新账号元数据与凭据一起落盘；必须在 IO 线程调用。
     *
     * 持有与刷新相同的凭据锁：刷新换 token 写回与这里的「读 → 加一条 → 写」不会互相覆盖。
     * [beforeWrite] 在拿到锁之后、写入之前执行，登录流程用它做最后一次「是否已取消」检查。
     */
    private suspend fun saveNewAccount(
        provider: Provider,
        name: String,
        beforeWrite: () -> Unit = {},
        credential: (now: Long) -> Credential,
    ) = UsageSync.withCredentialsLock {
        beforeWrite()
        val now = System.currentTimeMillis()
        val account = StoredAccount(
            id = "a-" + java.util.UUID.randomUUID().toString().take(8),
            providerId = provider.id,
            name = name,
            createdAt = now,
            updatedAt = now,
        )
        store.saveAccounts(store.loadAccounts() + account)
        store.saveCredentials(store.loadCredentials() + (account.id to credential(now)))
    }

    /** 当前登录协程；取消登录时 cancel 它，轮询随之停止。 */
    private var xaiLoginJob: Job? = null

    /** 登录会话号：每次开始或取消登录都加一，过期会话的授权结果一律丢弃。 */
    @Volatile
    private var xaiLoginSession = 0

    /**
     * xAI 设备码登录。轮询流程在 [XaiLoginFlow]，这里只把事件映射到 [UiState.xaiLogin] 并落库。
     */
    fun loginXai() {
        if (_state.value.xaiLogin != null || _state.value.oauthLogin != null) return

        // 立即给出反馈，避免「点了没反应」的错觉
        _state.value = _state.value.copy(
            xaiLogin = XaiLogin(
                userCode = "",
                verificationUri = "",
                status = "正在获取设备码…",
            )
        )

        val session = ++xaiLoginSession
        xaiLoginJob = viewModelScope.launch {
            xaiLoginEvents().collect { event ->
                when (event) {
                    is XaiLoginFlow.Event.CodeReady -> _state.value = _state.value.copy(
                        xaiLogin = XaiLogin(event.userCode, event.verificationUri)
                    )
                    is XaiLoginFlow.Event.Authorized -> {
                        withContext(Dispatchers.IO) {
                            saveNewAccount(
                                Provider.XAI,
                                "Grok 订阅",
                                beforeWrite = {
                                    // 写入前最后确认：已取消（协程被 cancel 或会话已换）就什么都不写
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
                        _state.value = _state.value.copy(xaiLogin = null, message = "Grok 登录成功")
                        reload()
                    }
                    is XaiLoginFlow.Event.Failed ->
                        _state.value = _state.value.copy(xaiLogin = null, message = event.message)
                }
            }
        }
    }

    /**
     * 取消登录：立刻停止轮询。之后即使用户仍在浏览器里完成授权，也不会添加账号。
     * （1.7.0 及以前只清界面状态，轮询协程继续跑，授权后账号照样会被加进来。）
     */
    fun cancelXaiLogin() {
        xaiLoginSession++
        xaiLoginJob?.cancel()
        xaiLoginJob = null
        _state.value = _state.value.copy(xaiLogin = null)
    }

    // ---------------------------------------------------------------- 订阅登录（1.8.0）
    //
    // 取消语义与 1.7.1 的 xAI 修复一致：取消 = 会话号加一 + cancel 协程（轮询随之停止）+ 丢弃本地 PKCE；
    // 写库前在凭据锁内再确认一次会话号，过期会话的授权结果一律不写。
    // 两种模式都不在手机上起本地回调服务器，所以没有需要关闭的服务器。

    private var oauthLoginJob: Job? = null

    @Volatile
    private var oauthLoginSession = 0

    /** Claude 粘贴模式的 PKCE；只在内存里，取消 / 完成即清空。 */
    @Volatile
    private var claudePending: ClaudeOAuth.Pending? = null

    /** 开始 Claude 订阅登录：生成授权地址，等用户在浏览器授权后粘贴授权码。 */
    fun loginClaude() {
        if (_state.value.oauthLogin != null || _state.value.xaiLogin != null) return
        oauthLoginSession++
        val pending = runCatching { oauth.claudeBegin() }.getOrElse { e ->
            _state.value = _state.value.copy(message = "无法开始登录: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        claudePending = pending
        _state.value = _state.value.copy(
            oauthLogin = OAuthLogin(
                provider = Provider.CLAUDE_SUB,
                authorizeUrl = pending.authorizeUrl,
                status = "授权后页面会显示授权码，复制回来粘贴",
            )
        )
    }

    /** 提交粘贴的授权码（「code#state」或整段回调地址）。 */
    fun submitClaudeCode(pasted: String) {
        val login = _state.value.oauthLogin ?: return
        if (!login.pasteMode || login.submitting) return
        val pending = claudePending ?: return
        val parsed = ClaudeOAuth.splitPasted(pasted)
        if (parsed == null) {
            _state.value = _state.value.copy(oauthLogin = login.copy(error = "没有识别到授权码，请完整复制页面上的内容"))
            return
        }
        val (code, state) = parsed
        if (state != null && state != pending.pkce.state) {
            _state.value = _state.value.copy(oauthLogin = login.copy(error = "授权码不属于本次登录，请重新打开授权页面"))
            return
        }
        _state.value = _state.value.copy(oauthLogin = login.copy(submitting = true, error = null, status = "正在完成登录…"))

        val session = oauthLoginSession
        oauthLoginJob = viewModelScope.launch {
            val tokens = withContext(Dispatchers.IO) { runCatching { oauth.claudeExchange(code, pending.pkce) } }
                .getOrElse { e ->
                    if (e is CancellationException) throw e
                    if (session == oauthLoginSession) {
                        _state.value = _state.value.copy(
                            oauthLogin = _state.value.oauthLogin?.copy(
                                submitting = false,
                                status = "授权后页面会显示授权码，复制回来粘贴",
                                error = loginError(e),
                            )
                        )
                    }
                    return@launch
                }
            saveOAuthAccount(session, Provider.CLAUDE_SUB, "Claude 订阅", tokens, accountId = null)
            claudePending = null
            _state.value = _state.value.copy(oauthLogin = null, message = "Claude 登录成功")
            reload()
        }
    }

    /** 开始 ChatGPT 订阅登录（Codex 设备码）。 */
    fun loginChatGpt() {
        if (_state.value.oauthLogin != null || _state.value.xaiLogin != null) return
        _state.value = _state.value.copy(
            oauthLogin = OAuthLogin(provider = Provider.CHATGPT_SUB, status = "正在获取设备码…")
        )
        val session = ++oauthLoginSession
        oauthLoginJob = viewModelScope.launch {
            oauth.codexEvents().collect { event ->
                when (event) {
                    is CodexLoginFlow.Event.CodeReady -> _state.value = _state.value.copy(
                        oauthLogin = OAuthLogin(
                            provider = Provider.CHATGPT_SUB,
                            userCode = event.userCode,
                            verificationUri = event.verificationUri,
                        )
                    )
                    is CodexLoginFlow.Event.Authorized -> {
                        saveOAuthAccount(session, Provider.CHATGPT_SUB, "ChatGPT 订阅", event.login.tokens, event.login.accountId)
                        _state.value = _state.value.copy(oauthLogin = null, message = "ChatGPT 登录成功")
                        reload()
                    }
                    is CodexLoginFlow.Event.Failed ->
                        _state.value = _state.value.copy(oauthLogin = null, message = event.message)
                }
            }
        }
    }

    /** 取消订阅登录：停止轮询 / 换令牌，丢弃 PKCE；之后浏览器里授权成功也不会添加账号。 */
    fun cancelOAuthLogin() {
        oauthLoginSession++
        oauthLoginJob?.cancel()
        oauthLoginJob = null
        claudePending = null
        _state.value = _state.value.copy(oauthLogin = null)
    }

    private suspend fun saveOAuthAccount(
        session: Int,
        provider: Provider,
        name: String,
        tokens: OAuthTokens,
        accountId: String?,
    ) = withContext(Dispatchers.IO) {
        saveNewAccount(
            provider,
            name,
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

    fun removeAccount(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 与刷新写回共用凭据锁，避免刷新把刚删掉的凭据又写回去
                UsageSync.withCredentialsLock {
                    store.saveAccounts(store.loadAccounts().filterNot { it.id == id })
                    store.saveCredentials(store.loadCredentials() - id)
                }
            }
            reload()
        }
    }

    fun refreshAll() {
        if (_state.value.refreshing) return
        _state.value = _state.value.copy(refreshing = true, message = null)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Fx.refresh() }
            val snapshots = withContext(Dispatchers.IO) {
                runCatching { UsageSync.refreshAll(store) }
            }
            val error = snapshots.exceptionOrNull()
            _state.value = _state.value.copy(
                refreshing = false,
                message = if (error != null) {
                    "刷新失败: ${error.message ?: error.javaClass.simpleName}"
                } else {
                    "刷新完成"
                },
                snapshots = withContext(Dispatchers.IO) { store.loadSnapshots() },
                fxLive = Fx.isLive(),
            )
        }
    }

    fun latestFor(accountId: String): UsageSnapshot? =
        _state.value.snapshots.filter { it.accountId == accountId }.maxByOrNull { it.capturedAt }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }
}

/**
 * 按 [mode] 生成展示顺序。
 *
 * 纯函数，不碰存储 —— 自动排序只在渲染时重排，`accounts.json` 的顺序
 * 始终保留用户手动排的那一版。
 *
 * [BALANCE] 用换算到 [displayCurrency] 后的余额降序；拿不到金额的账号
 * （只有百分比、或快照失败）排在最后，而不是当成 0 混在中间 —— 那会让
 * 「没数据」看起来像「余额为零」。
 */
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
        // 先算出每个账号的可比较金额，null 表示无金额
        val amounts = accounts.associate { acc ->
            acc.id to balanceIn(acc.id, displayCurrency, latestFor)
        }
        accounts.sortedWith(
            compareBy<StoredAccount> { amounts[it.id] == null }
                .thenByDescending { amounts[it.id] ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        )
    }
}

/** 账号换算到 [target] 后的可用余额；无金额或换算失败返回 null。 */
private fun balanceIn(
    accountId: String,
    target: String,
    latestFor: (String) -> UsageSnapshot?,
): Long? {
    val snap = latestFor(accountId)?.takeIf { it.error == null } ?: return null
    val fromMetrics = snap.metrics.mapNotNull { m ->
        m.balanceMinor?.let { (m.currency ?: snap.currency ?: target) to it }
    }
    if (fromMetrics.isNotEmpty()) {
        var sum = 0L
        var any = false
        fromMetrics.forEach { (cur, v) ->
            val r = runCatching { Fx.convert(v, cur, target) }.getOrNull()
            if (r != null) { sum += r; any = true }
        }
        return if (any) sum else null
    }
    val minor = snap.balanceMinor ?: return null
    val cur = snap.currency ?: target
    return runCatching { Fx.convert(minor, cur, target) }.getOrNull()
}
