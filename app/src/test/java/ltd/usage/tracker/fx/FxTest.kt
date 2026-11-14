package ltd.usage.tracker.fx

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * 汇率换算测试。
 *
 * 不联网：用 [Fx.setRateForTest] 注入汇率，只验证换算数学与边界，
 * 真实汇率拉取属于集成测试范畴。
 */
class FxTest {

    @Before
    fun setUp() = Fx.resetForTest()

    @After
    fun tearDown() = Fx.resetForTest()

    @Test
    fun `same currency is identity`() {
        assertEquals(12345L, Fx.convert(12345L, "USD", "USD"))
        assertEquals(999L, Fx.convert(999L, "CNY", "CNY"))
    }

    @Test
    fun `usd to cny uses rate`() {
        Fx.setRateForTest("CNY", 7.0)
        // $1.00 = ¥7.00
        assertEquals(700L, Fx.convert(100L, "USD", "CNY"))
    }

    @Test
    fun `cny to usd divides by rate`() {
        Fx.setRateForTest("CNY", 7.0)
        // ¥7.00 = $1.00
        assertEquals(100L, Fx.convert(700L, "CNY", "USD"))
    }

    @Test
    fun `conversion rounds to nearest cent`() {
        Fx.setRateForTest("CNY", 7.13)
        // $0.23 * 7.13 = ¥1.6399 → ¥1.64
        assertEquals(164L, Fx.convert(23L, "USD", "CNY"))
    }

    @Test
    fun `round trip does not drift more than one cent`() {
        Fx.setRateForTest("CNY", 7.13)
        val start = 14993L // $149.93
        val cny = Fx.convert(start, "USD", "CNY")
        val back = Fx.convert(cny, "CNY", "USD")
        assertEquals(start, back)
    }

    @Test
    fun `zero stays zero`() {
        Fx.setRateForTest("CNY", 7.0)
        assertEquals(0L, Fx.convert(0L, "USD", "CNY"))
    }

    @Test
    fun `negative balance converts symmetrically`() {
        Fx.setRateForTest("CNY", 7.0)
        // 欠款为负值时换算不应翻转符号
        assertEquals(-700L, Fx.convert(-100L, "USD", "CNY"))
    }

    @Test
    fun `currency codes are case insensitive`() {
        Fx.setRateForTest("CNY", 7.0)
        assertEquals(700L, Fx.convert(100L, "usd", "cny"))
    }

    @Test
    fun `unknown currency falls back instead of throwing`() {
        // 未知币种不该让首页崩掉，返回兜底值 1.0 的等值换算
        val out = runCatching { Fx.convert(100L, "XYZ", "USD") }
        assertEquals(true, out.isSuccess)
    }

    @Test
    fun `supported list covers usd and cny`() {
        assertEquals(listOf("USD", "CNY"), Fx.supported)
    }
}
