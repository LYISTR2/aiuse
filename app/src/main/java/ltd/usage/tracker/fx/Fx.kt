package ltd.usage.tracker.fx

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ltd.usage.tracker.provider.vendors.httpClient
import ltd.usage.tracker.provider.vendors.sharedJson
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 汇率换算与缓存（线程安全）。
 * 复用全局 OkHttpClient 连接池，经 USD 中转，缓存 6 小时。
 */
object Fx {

    private val cache = ConcurrentHashMap<String, Double>()

    private val fallback = mapOf(
        "USD" to 1.0,
        "CNY" to 6.67,
        "EUR" to 0.90,
        "GBP" to 0.76,
        "JPY" to 160.0,
    )

    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L

    @Volatile private var lastFetchAt = 0L
    @Volatile private var lastOk = false

    private val sources = listOf(
        "https://api.frankfurter.dev/v1/latest?base=USD&symbols=CNY,EUR,GBP,JPY",
        "https://open.er-api.com/v6/latest/USD",
    )

    private val client by lazy {
        httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val supported = listOf("USD", "CNY")

    fun rateTo(currency: String): Double {
        val c = currency.uppercase()
        if (c == "USD") return 1.0
        val cached = cache[c]
        if (cached != null && System.currentTimeMillis() - lastFetchAt < CACHE_TTL_MS) {
            return cached
        }
        refresh()
        return cache[c] ?: fallback[c] ?: 1.0
    }

    fun isLive(): Boolean = lastOk

    fun refresh() {
        val fetched = sources.firstNotNullOfOrNull { url -> runCatching { fetchRates(url) }.getOrNull() }
        if (!fetched.isNullOrEmpty()) {
            cache.putAll(fetched)
            lastFetchAt = System.currentTimeMillis()
            lastOk = true
        } else {
            lastOk = false
        }
    }

    private fun fetchRates(url: String): Map<String, Double> {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string() ?: error("空响应")
            val rates = sharedJson.parseToJsonElement(body).jsonObject["rates"]?.jsonObject
                ?: error("缺 rates")
            return rates.toDoubleMap()
        }
    }

    private fun JsonObject.toDoubleMap(): Map<String, Double> =
        mapNotNull { (k, v) ->
            val d = v.jsonPrimitive.content.toDoubleOrNull()
            if (d != null && d > 0) k.uppercase() to d else null
        }.toMap()

    fun convert(amountMinor: Long, from: String, to: String): Long {
        val f = from.uppercase()
        val t = to.uppercase()
        if (f == t) return amountMinor
        val amount = amountMinor / 100.0
        val usd = if (f == "USD") amount else amount / rateTo(f)
        val out = if (t == "USD") usd else usd * rateTo(t)
        return Math.round(out * 100)
    }

    internal fun resetForTest() {
        cache.clear()
        lastFetchAt = 0L
        lastOk = false
    }

    internal fun setRateForTest(currency: String, rate: Double) {
        cache[currency.uppercase()] = rate
        lastFetchAt = System.currentTimeMillis()
        lastOk = true
    }
}
