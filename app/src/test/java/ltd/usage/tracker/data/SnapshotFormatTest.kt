package ltd.usage.tracker.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 存储格式护栏：1.6.1 及更早写下的 snapshots.json / accounts.json 升级后照常能读；
 * 1.7.0 写出的快照与 1.6.1 逐字节一致（toSnapshot 只是收拢映射，不改 JSON 形状）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotFormatTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: Store
    private lateinit var dir: File

    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText().trim()

    @Before
    fun setUp() {
        store = Store(context)
        dir = store.directory
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun reads161Snapshots_andRewritesIdentically() {
        val old = fixture("snapshots-1.6.1.json")
        File(dir, "snapshots.json").writeText(old)

        val list = store.loadSnapshots()
        assertEquals(4, list.size)
        assertEquals(1234L, list[0].balanceMinor)
        assertEquals("CNY", list[0].currency)
        assertEquals(2, list[1].metrics.size)
        assertEquals(5000L, list[1].metrics[0].quotaMinor)
        assertEquals("11-20 重置", list[1].metrics[1].note)
        assertEquals("HTTP 401: invalid key", list[2].error)

        // 原样写回，文件内容与 1.6.1 写出的一致
        store.saveSnapshots(list)
        assertEquals(old, File(dir, "snapshots.json").readText())
    }

    @Test
    fun readsLegacySnapshotWithoutMetricsField() {
        File(dir, "snapshots.json").writeText(fixture("snapshots-legacy.json"))
        val s = store.loadSnapshots().single()
        assertEquals(99L, s.balanceMinor)
        assertEquals(10L, s.usedTokens)
        assertEquals("旧版", s.note)
        assertEquals(emptyList<Metric>(), s.metrics)
    }

    @Test
    fun toSnapshotMatchesOldFieldByFieldMapping() {
        val r = UsageResult(
            balanceMinor = 4200, currency = "USD", usedTokens = 1, quotaTokens = 2,
            note = "n", error = null,
            metrics = listOf(Metric("API 预算", 4200, "USD", 16.0, 5000, null)),
        )
        // 1.6.1 UsageSync 里逐字段拷贝的写法
        val legacy = UsageSnapshot(
            accountId = "a", capturedAt = 7,
            balanceMinor = r.balanceMinor, currency = r.currency,
            usedTokens = r.usedTokens, quotaTokens = r.quotaTokens,
            note = r.note, error = r.error, metrics = r.metrics,
        )
        assertEquals(legacy, r.toSnapshot("a", 7))
    }

    @Test
    fun accountsReadAfterUpgrade_andUnknownProviderGetsExplanation() = runBlocking {
        File(dir, "accounts.json").writeText(fixture("accounts-1.6.1.json"))
        val accounts = store.loadAccounts()
        assertEquals(listOf("a-1111aaaa", "a-4444dddd"), accounts.map { it.id })
        assertNull(Provider.from(accounts[1].providerId))

        // 凭据文件缺失（Robolectric 下也没有 KeyStore）：两个账号都得到说明，而不是空白快照
        val results = UsageSync.refreshAll(store)
        assertEquals("缺少凭据，请重新添加", results[0].error)
        assertEquals("不支持的服务商（retired-vendor），请删除后重新添加", results[1].error)
        assertEquals(results, store.loadSnapshots())
    }
}
