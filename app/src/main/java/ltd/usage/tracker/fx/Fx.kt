package ltd.usage.tracker.fx

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 汇率。
 *
 * 数据源：frankfurter.dev（欧洲央行参考汇率，每天更新一次，无需 key）。
 * 备用：open.er-api.com（同样免费无 key）。
 *
 * 设计要点：
 *  - 只缓存「1 USD 等于多少目标币种」，不做多币种矩阵 —— 所有换算都经 USD 中转。
 *  - 缓存 6 小时，失败时回退到上次成功的值，再失败就用内置兜底值。
 *  - 换算用 Double 而非 Long：汇率本身是近似值，精度到分已足够，
 *    但中间结果不能先取整再乘，否则误差会放大。
 */
object Fx {

    /** 1 USD = rate[currency]。 */
    private val cache = mutableMapOf<String, Double>()

    /** 内置兜底：网络完全不可用时的近似值，只求量级正确。 */
    private val fallback = mapOf(
        "USD" to 1.0,
        "CNY" to 6.67,
        "EUR" to 0.90,
        "GBP" to 0.76,
        "JPY" to 160.0,
    )

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private var lastFetchAt = 0L
    private var lastOk = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** 支持的目标币种。 */
    val supported = listOf("USD", "CNY")

    /**
     * 1 USD 兑 [currency] 的汇率。
     *
     * 命中缓存直接返回；过期或未命中才联网。联网失败时返回旧值/兜底值，
     * 绝不抛异常 —— 汇率拿不到不该让整个首页崩掉。
     */
    fun rateTo(currency: String): Double {
        val c = currency.uppercase()
        if (c == "USD") return 1.0
        val now = System.currentTimeMillis()
        if (cache.containsKey(c) && now - lastFetchAt < CACHE_TTL_MS) {
            return cache.getValue(c)
        }
        refresh()
        return cache[c] ?: fallback[c] ?: 1.0
    }

    /** 上次拉取是否成功，用于 UI 提示「汇率为离线估算」。 */
    fun isLive(): Boolean = lastOk

    /** 强制刷新（下拉刷新时调用）。 */
    fun refresh() {
        val fetched = runCatching { fetchFrankfurter() }
            .recoverCatching { fetchErApi() }
            .getOrNull()

        if (fetched != null && fetched.isNotEmpty()) {
            cache.putAll(fetched)
            lastFetchAt = System.currentTimeMillis()
            lastOk = true
        } else {
            lastOk = false
        }
    }

    private fun fetchFrankfurter(): Map<String, Double> {
        val req = Request.Builder()
            .url("https://api.frankfurter.dev/v1/latest?base=USD&symbols=CNY,EUR,GBP,JPY")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("空响应")
            val rates = json.parseToJsonElement(body).jsonObject["rates"]?.jsonObject
                ?: error("缺 rates")
            return rates.toDoubleMap()
        }
    }

    private fun fetchErApi(): Map<String, Double> {
        val req = Request.Builder()
            .url("https://open.er-api.com/v6/latest/USD")
            .get()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("空响应")
            val rates = json.parseToJsonElement(body).jsonObject["rates"]?.jsonObject
                ?: error("缺 rates")
            return rates.toDoubleMap()
        }
    }

    private fun JsonObject.toDoubleMap(): Map<String, Double> =
        mapNotNull { (k, v) ->
            val d = v.jsonPrimitive.content.toDoubleOrNull()
            if (d != null && d > 0) k.uppercase() to d else null
        }.toMap()

    /**
     * 把 [amountMinor]（[from] 币种的最小单位，如分）换算成 [to] 币种的最小单位。
     *
     * 经 USD 中转：from → USD → to。全程用 Double，最后一次性四舍五入到分，
     * 避免中间取整放大误差。
     */
    fun convert(amountMinor: Long, from: String, to: String): Long {
        val f = from.uppercase()
        val t = to.uppercase()
        if (f == t) return amountMinor
        val amount = amountMinor / 100.0
        val usd = if (f == "USD") amount else amount / rateTo(f)
        val out = if (t == "USD") usd else usd * rateTo(t)
        return Math.round(out * 100)
    }

    /** 清空缓存，测试用。 */
    internal fun resetForTest() {
        cache.clear()
        lastFetchAt = 0L
        lastOk = false
    }

    /** 直接注入汇率，测试用。 */
    internal fun setRateForTest(currency: String, rate: Double) {
        cache[currency.uppercase()] = rate
        lastFetchAt = System.currentTimeMillis()
        lastOk = true
    }
}
