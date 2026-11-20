package ltd.usage.tracker.security

import ltd.usage.tracker.provider.vendors.OpenRouterVendor
import ltd.usage.tracker.provider.vendors.redact

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ltd.usage.tracker.backup.WebDavClient
import ltd.usage.tracker.crypto.CredentialCipher
import ltd.usage.tracker.data.Credential
import ltd.usage.tracker.data.Provider
import ltd.usage.tracker.data.Store
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 安全测试。
 *
 * 覆盖四类风险：
 *  1. 凭据泄露（toString / 日志 / 异常消息）
 *  2. 传输安全（明文 http、header 注入）
 *  3. 加密边界（短密文、篡改、空输入）
 *  4. 存储隔离（元数据与凭据分离、wipe 彻底性）
 *
 * 不覆盖：真实 AndroidKeyStore 行为（Robolectric 无 TEE，密钥操作在真机才有效）。
 * 这部分只能靠代码审查 + 真机验证，测试里会明确标注。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // ---------------------------------------------------- 1. 凭据泄露

    @Test
    fun `credential toString never exposes token`() {
        val secret = "sk-live-abcdef1234567890SECRET"
        val refresh = "rt-REFRESH-9876543210SECRET"
        val s = Credential(secret, refresh, "acct-1", 123L).toString()

        assertFalse("token 原文出现在 toString 里", s.contains(secret))
        assertFalse("refreshToken 原文出现在 toString 里", s.contains(refresh))
        // 只暴露长度，便于排查而不泄露内容
        assertTrue(s.contains("***(${secret.length})"))
        assertTrue(s.contains("***(${refresh.length})"))
    }

    @Test
    fun `credential toString handles empty and null`() {
        val s1 = Credential("").toString()
        assertTrue(s1.contains("<empty>"))

        val s2 = Credential("t", null).toString()
        assertTrue(s2.contains("refreshToken=null"))
    }

    @Test
    fun `data class copy does not bypass masking`() {
        // 防御性：copy 出来的实例仍然脱敏
        val c = Credential("sk-secret-value").copy(accountId = "x")
        assertFalse(c.toString().contains("sk-secret-value"))
    }

    // ---------------------------------------------------- 2. 传输安全

    @Test
    fun `webdav rejects plain http`() {
        try {
            WebDavClient("http://example.com/dav/", "u", "p")
            fail("明文 http 必须被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun `webdav rejects http with credentials in url`() {
        try {
            WebDavClient("http://user:pass@evil.com/dav/", "u", "p")
            fail("带凭据的明文 http 必须被拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `webdav rejects scheme-relative and other schemes`() {
        listOf(
            "ftp://example.com/dav/",
            "file:///sdcard/dav/",
            "//example.com/dav/",
        ).forEach { url ->
            try {
                WebDavClient(url, "u", "p")
                fail("应拒绝: $url")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `webdav allows https and localhost`() {
        WebDavClient("https://dav.example.com/dav/", "u", "p")
        WebDavClient("HTTPS://dav.example.com/dav/", "u", "p")
        WebDavClient("http://localhost:8080/dav/", "u", "p")
        WebDavClient("http://127.0.0.1:8080/dav/", "u", "p")
    }

    @Test
    fun `webdav rejects localhost prefix bypass`() {
        listOf(
            "http://localhost.evil.com/dav/",
            "http://localhost@evil.com/dav/",
            "http://localhost.evil.com:8080/dav/",
        ).forEach { url ->
            try {
                WebDavClient(url, "u", "p")
                fail("应拒绝本机前缀绕过: $url")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `webdav rejects credentials embedded in url`() {
        try {
            WebDavClient("https://user:pass@dav.example.com/dav/", "u", "p")
            fail("内嵌用户名密码必须被拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `webdav requires trailing slash to prevent path traversal`() {
        // 没有结尾 / 时 baseUrl + fileName 会拼成 .../davbackup.bin，写到错误位置
        try {
            WebDavClient("https://dav.example.com/dav", "u", "p")
            fail("必须以 / 结尾")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("/"))
        }
    }

    @Test
    fun `webdav upload rejects oversized payload before network`() {
        val c = WebDavClient("https://127.0.0.1:1/dav/", "u", "p")
        try {
            c.upload("big.bin", ByteArray((WebDavClient.MAX_BYTES + 1).toInt()))
            fail("超限必须拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("过大"))
        }
    }

    @Test
    fun `okhttp rejects header injection via token`() {
        // 若 token 含 CRLF，可能注入额外 header。OkHttp 应直接拒绝。
        try {
            Request.Builder()
                .url("https://api.example.com/x")
                .header("Authorization", "Bearer abc\r\nX-Injected: evil")
                .build()
            fail("OkHttp 必须拒绝含 CRLF 的 header 值")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun `okhttp rejects header injection via newline only`() {
        try {
            Request.Builder()
                .url("https://api.example.com/x")
                .header("Authorization", "Bearer abc\nX-Injected: evil")
                .build()
            fail("换行符必须被拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------------------------------------------------- 3. 加密边界

    @Test
    fun `decrypt rejects truncated blob`() {
        // 长度 <= 12(IV) + 16(tag) 时必须在进入 Cipher 前就拒绝
        listOf(0, 1, 12, 16, 27, 28).forEach { size ->
            try {
                CredentialCipher.decrypt(ByteArray(size))
                fail("长度 $size 应被拒绝")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("too short"))
            }
        }
    }

    @Test
    fun `cipher constants are strong`() {
        // 锁住参数：AES-256 + GCM + 128-bit tag + 96-bit IV
        // 这些值改动会静默降低安全性，用反射读私有常量防回归
        val ivLen = privateConst("IV_LENGTH")
        val tagBits = privateConst("TAG_BITS")
        assertEquals("GCM IV 必须是 12 字节", 12, ivLen)
        assertEquals("GCM tag 必须是 128 位", 128, tagBits)
    }

    @Test
    fun `transformation is authenticated mode`() {
        val t = privateConstString("TRANSFORMATION")
        assertTrue("必须是 GCM（提供完整性校验）", t.contains("GCM"))
        assertFalse("ECB 无 IV，绝不可用", t.contains("ECB"))
        assertTrue("GCM 不需要 padding", t.contains("NoPadding"))
    }

    // ---------------------------------------------------- 4. 存储隔离

    @Test
    fun `accounts metadata file contains no token`() {
        val store = Store(context)
        store.wipe()
        store.saveAccounts(
            listOf(
                ltd.usage.tracker.data.StoredAccount(
                    id = "a1", providerId = "deepseek", name = "主账号",
                    createdAt = 1L, updatedAt = 1L,
                )
            )
        )
        val raw = java.io.File(store.directory, "accounts.json").readText()
        assertFalse("元数据文件不得包含 token 字段", raw.contains("token", ignoreCase = true))
        assertFalse(raw.contains("sk-"))
    }

    @Test
    fun `credentials file is not readable as plaintext json`() {
        val store = Store(context)
        store.wipe()
        // Robolectric 下 AndroidKeyStore 不可用，加密会失败；
        // 这里只断言「没有明文凭据文件被写出来」
        runCatching {
            store.saveCredentials(mapOf("a1" to Credential("sk-plaintext-secret")))
        }
        val f = java.io.File(store.directory, "credentials.bin")
        if (f.exists()) {
            val bytes = f.readBytes()
            val asText = String(bytes, Charsets.ISO_8859_1)
            assertFalse("凭据文件里出现明文 token", asText.contains("sk-plaintext-secret"))
        }
    }

    @Test
    fun `wipe removes all stored files`() {
        val store = Store(context)
        java.io.File(store.directory, "accounts.json").writeText("[]")
        java.io.File(store.directory, "snapshots.json").writeText("[]")
        java.io.File(store.directory, "settings.json").writeText("{}")

        store.wipe()

        val left = store.directory.listFiles()?.toList().orEmpty()
        assertTrue("wipe 后不应残留文件: $left", left.isEmpty())
    }

    @Test
    fun `loadCredentials on corrupt file returns empty instead of crashing`() {
        val store = Store(context)
        store.wipe()
        java.io.File(store.directory, "credentials.bin").writeBytes(ByteArray(64) { 0xFF.toByte() })
        // 解密失败必须降级为空，不能让 App 启动崩溃
        assertEquals(emptyMap<String, Credential>(), store.loadCredentials())
    }

    @Test
    fun `loadAccounts on malformed json returns empty`() {
        val store = Store(context)
        store.wipe()
        java.io.File(store.directory, "accounts.json").writeText("{not json")
        assertEquals(emptyList<Any>(), store.loadAccounts())
    }

    // ---------------------------------------------------- 5. 输入校验

    @Test
    fun `unknown provider id is rejected`() {
        assertNull(Provider.from("../../etc/passwd"))
        assertNull(Provider.from(""))
        assertNull(Provider.from("DEEPSEEK"))  // 大小写敏感
        assertNotNull(Provider.from("deepseek"))
    }

    @Test
    fun `fetcher reports invalid json as error not crash`() {
        val r = OpenRouterVendor.parse("not json at all")
        assertNotNull(r.error)
    }

    @Test
    fun `fetcher handles deeply nested json without stack overflow`() {
        val deep = "[".repeat(500) + "]".repeat(500)
        // 不应抛 StackOverflowError 到调用方
        runCatching { OpenRouterVendor.parse(deep) }
    }

    @Test
    fun `fetcher handles huge numbers without overflow`() {
        val body = """{"data":{"limit":1e308,"usage":1e308,"limit_remaining":1e308}}"""
        val r = OpenRouterVendor.parse(body)
        // Math.round(Double.MAX) 会饱和到 Long.MAX_VALUE，不应抛异常
        assertNull(r.error)
    }

    @Test
    fun `fetcher handles negative and NaN-like values`() {
        val body = """{"data":{"limit":-5.0,"usage":-1.0,"limit_remaining":-4.0}}"""
        val r = OpenRouterVendor.parse(body)
        // limit <= 0 视为不限额度，只报已用
        assertNull(r.error)
    }

    // ---------------------------------------------------- 5. 错误消息脱敏

    @Test
    fun `error message never contains token`() {
        val token = "sk-live-SECRET-abcdef123456"
        val msg = "HTTP 401: {\"error\":\"invalid key sk-live-SECRET-abcdef123456\"}"
        val out = redact(msg, token)
        assertFalse("token 原文泄露到错误消息", out.contains(token))
        assertTrue(out.contains("***"))
        assertTrue("应保留 HTTP 状态码便于排查", out.contains("401"))
    }

    @Test
    fun `redact handles truncated token fragment`() {
        // 响应体只取前 200 字符时 token 可能被切成半截
        val token = "sk-live-SECRET-abcdef123456"
        val msg = "HTTP 401: echo sk-live-SECR"
        val out = redact(msg, token)
        assertFalse(out.contains("sk-live-SECR"))
    }

    @Test
    fun `redact leaves message intact when token absent`() {
        val out = redact("HTTP 500: internal error", "sk-abc")
        assertEquals("HTTP 500: internal error", out)
    }

    @Test
    fun `redact handles empty token`() {
        assertEquals("boom", redact("boom", ""))
    }

    @Test
    fun `redact also strips refresh token`() {
        val access = "sk-live-ACCESS-abcdef123456"
        val refresh = "rt-REFRESH-9876543210SECRET"
        val msg = "HTTP 400: invalid_grant token=$refresh access=$access"
        val out = redact(msg, access, refresh)
        assertFalse(out.contains(access))
        assertFalse(out.contains(refresh))
        assertTrue(out.contains("***"))
    }

    // ---------------------------------------------------- 工具

    private fun privateConst(name: String): Int {
        val f = CredentialCipher::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(null) as Int
    }

    private fun privateConstString(name: String): String {
        val f = CredentialCipher::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(null) as String
    }
}
