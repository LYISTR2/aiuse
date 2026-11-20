package ltd.usage.tracker.ui

import ltd.usage.tracker.ui.home.ServicesContent
import ltd.usage.tracker.ui.home.UsageHome
import ltd.usage.tracker.ui.add.AddAccountForm
import ltd.usage.tracker.ui.add.AddMethod
import ltd.usage.tracker.ui.login.SubscriptionLoginCardPreview
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import ltd.usage.tracker.ui.login.XaiLoginCardPreview
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import ltd.usage.tracker.data.Metric
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.StoredAccount
import ltd.usage.tracker.data.UsageSnapshot
import ltd.usage.tracker.ui.theme.Palette
import ltd.usage.tracker.ui.theme.UsageTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 在 JVM 上渲染真实 Compose UI 并输出 PNG，用于视觉验证。
 * 不依赖模拟器。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun save(name: String, bitmap: Bitmap) {
        File(outDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun render(name: String, dark: Boolean = true, content: @Composable () -> Unit) {
        // 关闭自动推进时钟，避免无限动画导致 waitForIdle 永不返回
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageTrackerTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Palette.Bg)) { content() }
            }
        }
        // 手动推进到动画结束（错开入场 40ms*n + 弹簧收敛）
        compose.mainClock.advanceTimeBy(1_500)

        // Robolectric 下 captureToImage 依赖真实窗口重绘，会超时。
        // 改为直接把根 View 绘制到 Bitmap。
        val view = compose.activity!!.window.decorView
        val w = if (view.width > 0) view.width else 1080
        val h = if (view.height > 0) view.height else 2340
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        view.draw(canvas)
        save(name, bmp)
    }

    @Test
    fun emptyState() {
        render("01-empty") {
            ServicesContent(
                state = UiState(),
                latestFor = { null },
                onDelete = {},
            )
        }
    }

    @Test
    fun withAccounts() {
        val now = System.currentTimeMillis()
        val accounts = listOf(
            StoredAccount("a1", "deepseek", "DeepSeek 主账号", now, now),
            StoredAccount("a2", "anthropic", "Claude Pro", now, now),
            StoredAccount("a3", "mistral", "Mistral", now, now),
        )
        val snapshots = listOf(
            UsageSnapshot("a1", now, balanceMinor = 12_345, currency = "CNY", note = "可用: true"),
            UsageSnapshot("a2", now, note = "5h 窗口已用 42%"),
            UsageSnapshot("a3", now, balanceMinor = 8_000, currency = "USD", note = "预算已用 20%"),
        )
        render("02-accounts") {
            ServicesContent(
                state = UiState(accounts = accounts, snapshots = snapshots),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onDelete = {},
            )
        }
    }

    @Test
    fun errorState() {
        val now = System.currentTimeMillis()
        val accounts = listOf(StoredAccount("a1", "openai", "OpenAI", now, now))
        val snapshots = listOf(
            UsageSnapshot("a1", now, error = "HTTP 401: Invalid API key")
        )
        render("03-error") {
            ServicesContent(
                state = UiState(accounts = accounts, snapshots = snapshots),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onDelete = {},
            )
        }
    }

    @Test
    fun mixedState() {
        val now = System.currentTimeMillis()
        val accounts = listOf(
            StoredAccount("a1", "deepseek", "DeepSeek", now, now),
            StoredAccount("a2", "openai", "OpenAI", now, now),
        )
        val snapshots = listOf(
            UsageSnapshot("a1", now, balanceMinor = 5_000, currency = "CNY", note = "可用: true"),
            UsageSnapshot("a2", now, error = "HTTP 401: Invalid API key"),
        )
        render("05-mixed") {
            ServicesContent(
                state = UiState(accounts = accounts, snapshots = snapshots),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onDelete = {},
            )
        }
    }

    @Test
    fun mistralDualBudget() {
        val now = System.currentTimeMillis()
        val accounts = listOf(
            StoredAccount("a1", "mistral", "Mistral", now, now),
            StoredAccount("a2", "deepseek", "DeepSeek", now, now),
        )
        val snapshots = listOf(
            UsageSnapshot(
                accountId = "a1",
                capturedAt = now,
                balanceMinor = 24_000,
                currency = "EUR",
                metrics = listOf(
                    Metric("API", balanceMinor = 24_000, currency = "EUR", usedPercent = 20.0, note = "已用 20%"),
                    Metric("Vibe", balanceMinor = 4_500, currency = "EUR", usedPercent = 85.0, note = "已用 85% · 按量超支已开启"),
                ),
            ),
            UsageSnapshot("a2", now, balanceMinor = 12_345, currency = "CNY", note = "可用: true"),
        )
        render("07-mistral-dual") {
            ServicesContent(
                state = UiState(accounts = accounts, snapshots = snapshots),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onDelete = {},
            )
        }
    }

    @Test
    fun grokUsage() {
        val now = System.currentTimeMillis()
        val accounts = listOf(StoredAccount("a1", "xai", "Grok 订阅", now, now))
        val snapshots = listOf(
            UsageSnapshot(
                accountId = "a1",
                capturedAt = now,
                metrics = listOf(
                    Metric("周额度", usedPercent = 100.0, note = "已用 100% · 08-15 重置"),
                    Metric("SuperGrok", usedPercent = 100.0, note = "已用 100%"),
                    Metric("Grok Bot", usedPercent = 42.0, note = "已用 42%"),
                    Metric("按量", balanceMinor = 2_000, currency = "USD", note = "上限 $20.00"),
                ),
            ),
        )
        render("08-grok") {
            ServicesContent(
                state = UiState(accounts = accounts, snapshots = snapshots),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onDelete = {},
            )
        }
    }

    @Test
    fun xaiLoginDialog() {
        // AlertDialog 渲染在独立 Window 上，decorView.draw() 抓不到，
        // 这里用同款内容的内联布局验证视觉。
        render("06-xai-login") {
            XaiLoginCardPreview(
                login = XaiLogin(
                    userCode = "R8R4-BHJG",
                    verificationUri = "https://accounts.x.ai/oauth2/device?user_code=R8R4-BHJG",
                ),
            )
        }
    }

    @Test
    fun xaiLoginPending() {
        render("09-xai-pending") {
            XaiLoginCardPreview(
                login = XaiLogin(userCode = "", verificationUri = "", status = "正在获取设备码…")
            )
        }
    }

    /** 浅色主题 + 进度条：验证黑白切换与额度条同时生效。 */
    @Test
    fun lightThemeWithBars() {
        val now = System.currentTimeMillis()
        val accounts = listOf(
            StoredAccount("a1", "mistral", "Mistral", now, now),
            StoredAccount("a2", "deepseek", "DeepSeek", now, now),
        )
        val snapshots = listOf(
            UsageSnapshot(
                accountId = "a1",
                capturedAt = now,
                balanceMinor = 1_600,
                currency = "USD",
                metrics = listOf(
                    Metric(
                        "API",
                        balanceMinor = 600,
                        currency = "USD",
                        usedPercent = 40.0,
                        quotaMinor = 1_000,
                        note = "已用 $4.00",
                    ),
                    Metric(
                        "Vibe",
                        balanceMinor = 600,
                        currency = "USD",
                        usedPercent = 96.0,
                        quotaMinor = 15_000,
                        note = "已用 $144.00",
                    ),
                ),
            ),
            UsageSnapshot("a2", now, balanceMinor = 1_968, currency = "CNY", note = "可用: true"),
        )
        render("10-light", dark = false) {
            ServicesContent(
                state = UiState(
                    accounts = accounts,
                    snapshots = snapshots,
                    displayCurrency = "CNY",
                    fxLive = true,
                ),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onDelete = {},
            )
        }
    }

    /** 深色主题 + 进度条，与 10-light 对照。 */
    @Test
    fun darkThemeWithBars() {
        val now = System.currentTimeMillis()
        val accounts = listOf(
            StoredAccount("a1", "mistral", "Mistral", now, now),
            StoredAccount("a2", "deepseek", "DeepSeek", now, now),
        )
        val snapshots = listOf(
            UsageSnapshot(
                accountId = "a1",
                capturedAt = now,
                balanceMinor = 1_600,
                currency = "USD",
                metrics = listOf(
                    Metric(
                        "API",
                        balanceMinor = 600,
                        currency = "USD",
                        usedPercent = 40.0,
                        quotaMinor = 1_000,
                        note = "已用 $4.00",
                    ),
                    Metric(
                        "Vibe",
                        balanceMinor = 600,
                        currency = "USD",
                        usedPercent = 96.0,
                        quotaMinor = 15_000,
                        note = "已用 $144.00",
                    ),
                ),
            ),
            UsageSnapshot("a2", now, balanceMinor = 1_968, currency = "CNY", note = "可用: true"),
        )
        render("11-dark", dark = true) {
            ServicesContent(
                state = UiState(
                    accounts = accounts,
                    snapshots = snapshots,
                    displayCurrency = "CNY",
                    fxLive = true,
                ),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onDelete = {},
            )
        }
    }


    private fun sampleHomeState(): Pair<List<StoredAccount>, List<UsageSnapshot>> {
        val now = System.currentTimeMillis()
        val accounts = listOf(
            StoredAccount("a1", "deepseek", "DeepSeek", now, now),
            StoredAccount("a2", "mistral", "Mistral", now, now),
            StoredAccount("a3", "anthropic", "Claude Pro", now, now),
        )
        val snapshots = listOf(
            UsageSnapshot("a1", now, balanceMinor = 12_345, currency = "CNY", note = "可用: true"),
            UsageSnapshot(
                accountId = "a2",
                capturedAt = now,
                balanceMinor = 1_600,
                currency = "USD",
                metrics = listOf(
                    Metric("API", balanceMinor = 600, currency = "USD", usedPercent = 40.0, quotaMinor = 1_000, note = "已用 $4.00"),
                    Metric("Vibe", balanceMinor = 600, currency = "USD", usedPercent = 96.0, quotaMinor = 15_000, note = "已用 $144.00"),
                ),
            ),
            UsageSnapshot("a3", now, note = "5h 窗口已用 42%", metrics = listOf(Metric("5h", usedPercent = 42.0))),
        )
        return accounts to snapshots
    }

    @Test
    fun homeDark190() {
        val (accounts, snapshots) = sampleHomeState()
        render("190-home-dark", dark = true) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeLight190() {
        val (accounts, snapshots) = sampleHomeState()
        render("190-home-light", dark = false) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dark = false, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeDark191() {
        val (accounts, snapshots) = sampleHomeState()
        render("191-home-dark", dark = true) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeLight191() {
        val (accounts, snapshots) = sampleHomeState()
        render("191-home-light", dark = false) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dark = false, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }


    private fun renderCollapsedHome(name: String, dark: Boolean) {
        val (accounts, snapshots) = sampleHomeState()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            UsageTrackerTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(Palette.Bg)) {
                    UsageHome(
                        state = UiState(
                            accounts = accounts,
                            snapshots = snapshots,
                            displayCurrency = "USD",
                            fxLive = true,
                            dark = dark,
                            dynamicColor = false,
                        ),
                        latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                        onRefresh = {},
                        onAdd = {},
                        onToggleTheme = {},
                        onSortModeChange = {},
                        onDynamicColorChange = {},
                        onDelete = {},
                        onCurrencyChange = {},
                        onMove = { _, _ -> },
                        snackbar = remember { SnackbarHostState() },
                        dynamicColorAvailable = false,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500)
        // 尝试上滑收起 LargeFlexibleTopAppBar；Robolectric 下 nested scroll 可能无效
        compose.onRoot().performTouchInput {
            swipeUp()
            swipeUp()
        }
        compose.mainClock.advanceTimeBy(1_200)
        val view = compose.activity!!.window.decorView
        val w = if (view.width > 0) view.width else 1080
        val h = if (view.height > 0) view.height else 2340
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        view.draw(canvas)
        save(name, bmp)
    }

    @Test
    fun homeDark191Collapsed() {
        renderCollapsedHome("191-home-dark-collapsed", dark = true)
    }

    @Test
    fun homeLight191Collapsed() {
        renderCollapsedHome("191-home-light-collapsed", dark = false)
    }

    @Test
    fun addFormDark190() {
        render("190-add-dark", dark = true) {
            AddAccountForm(
                onDismiss = {},
                onConfirm = { _, _, _ -> },
                onXaiLogin = {},
                onSubscriptionLogin = {},
                initialProvider = Provider.ANTHROPIC,
                initialMethod = AddMethod.SUBSCRIPTION,
            )
        }
    }

    @Test
    fun addFormLight190() {
        render("190-add-light", dark = false) {
            AddAccountForm(
                onDismiss = {},
                onConfirm = { _, _, _ -> },
                onXaiLogin = {},
                onSubscriptionLogin = {},
                initialProvider = Provider.ANTHROPIC,
                initialMethod = AddMethod.SUBSCRIPTION,
            )
        }
    }

    @Test
    fun loginDialogDark190() {
        render("190-login-dark", dark = true) {
            XaiLoginCardPreview(
                login = XaiLogin(
                    userCode = "R8R4-BHJG",
                    verificationUri = "https://accounts.x.ai/oauth2/device?user_code=R8R4-BHJG",
                ),
            )
        }
    }

    @Test
    fun loginDialogLight190() {
        render("190-login-light", dark = false) {
            XaiLoginCardPreview(
                login = XaiLogin(
                    userCode = "R8R4-BHJG",
                    verificationUri = "https://accounts.x.ai/oauth2/device?user_code=R8R4-BHJG",
                ),
            )
        }
    }

    @Test
    fun claudeLoginDark190() {
        render("190-login-claude-dark", dark = true) {
            SubscriptionLoginCardPreview(
                login = OAuthLogin(provider = Provider.CLAUDE_SUB, authorizeUrl = "https://claude.ai/oauth", status = "打开授权页后，把授权码粘贴回来"),
                pastedCode = "",
            )
        }
    }



    @Test
    fun homeDark192() {
        val (accounts, snapshots) = sampleHomeState()
        render("192-home-dark", dark = true) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeLight192() {
        val (accounts, snapshots) = sampleHomeState()
        render("192-home-light", dark = false) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dark = false, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun addFormDark192() {
        render("192-add-dark", dark = true) {
            AddAccountForm(
                onDismiss = {},
                onConfirm = { _, _, _ -> },
                onXaiLogin = {},
                onSubscriptionLogin = {},
                initialProvider = Provider.ANTHROPIC,
                initialMethod = AddMethod.SUBSCRIPTION,
            )
        }
    }

    @Test
    fun addFormLight192() {
        render("192-add-light", dark = false) {
            AddAccountForm(
                onDismiss = {},
                onConfirm = { _, _, _ -> },
                onXaiLogin = {},
                onSubscriptionLogin = {},
                initialProvider = Provider.ANTHROPIC,
                initialMethod = AddMethod.SUBSCRIPTION,
            )
        }
    }



    @Test
    fun homeDark193() {
        val (accounts, snapshots) = sampleHomeState()
        render("193-home-dark", dark = true) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeLight193() {
        val (accounts, snapshots) = sampleHomeState()
        render("193-home-light", dark = false) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dark = false, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeDark194() {
        val (accounts, snapshots) = sampleHomeState()
        render("194-home-dark", dark = true) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeLight194() {
        val (accounts, snapshots) = sampleHomeState()
        render("194-home-light", dark = false) {
            UsageHome(
                state = UiState(accounts = accounts, snapshots = snapshots, displayCurrency = "USD", fxLive = true, dark = false, dynamicColor = false),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeDark196DecryptError() {
        val (accounts, snapshots) = sampleHomeState()
        render("196-home-dark", dark = true) {
            UsageHome(
                state = UiState(
                    accounts = accounts,
                    snapshots = snapshots,
                    displayCurrency = "USD",
                    fxLive = true,
                    dynamicColor = false,
                    credentialsUndecryptable = true,
                    accountsNeedingRelogin = accounts.map { it.id }.toSet(),
                ),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

    @Test
    fun homeLight196DecryptError() {
        val (accounts, snapshots) = sampleHomeState()
        render("196-home-light", dark = false) {
            UsageHome(
                state = UiState(
                    accounts = accounts,
                    snapshots = snapshots,
                    displayCurrency = "USD",
                    fxLive = true,
                    dark = false,
                    dynamicColor = false,
                    credentialsUndecryptable = true,
                    accountsNeedingRelogin = accounts.map { it.id }.toSet(),
                ),
                latestFor = { id -> snapshots.firstOrNull { it.accountId == id } },
                onRefresh = {},
                onAdd = {},
                onToggleTheme = {},
                onSortModeChange = {},
                onDynamicColorChange = {},
                onDelete = {},
                onCurrencyChange = {},
                onMove = { _, _ -> },
                snackbar = remember { SnackbarHostState() },
                dynamicColorAvailable = false,
            )
        }
    }

}
