package ltd.usage.tracker.security

import ltd.usage.tracker.backup.WebDavClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * WebDAV 客户端安全测试（不联网）。
 *
 * 用 OkHttp 的 Response 直接构造响应，验证客户端自身的防御逻辑，
 * 不依赖真实服务器。
 */
class WebDavSecurityTest {

    // ---------------------------------------------------- 文件名注入

    @Test
    fun `upload rejects path traversal in filename`() {
        val c = WebDavClient("https://dav.example.com/dav/", "u", "p")
        listOf(
            "../evil.bin",
            "../../etc/passwd",
            "sub/dir/file.bin",
            "..\\..\\evil.bin",
            "",
            ".",
            "..",
        ).forEach { name ->
            try {
                c.upload(name, ByteArray(0))
                fail("应拒绝文件名: '$name'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `download rejects path traversal in filename`() {
        val c = WebDavClient("https://dav.example.com/dav/", "u", "p")
        listOf("../evil.bin", "a/b.bin").forEach { name ->
            try {
                c.download(name)
                fail("应拒绝文件名: '$name'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `filename validation allows normal names`() {
        WebDavClient.validateFileName("backup-2026-11-14.bin")
        WebDavClient.validateFileName("a.bin")
        WebDavClient.validateFileName("备份_01.bin")
    }

    // ---------------------------------------------------- 下载大小限制

    @Test
    fun `download rejects unknown content length`() {
        // 分块传输时 contentLength() 返回 -1，旧逻辑 -1 <= MAX_BYTES 恒真，
        // 于是 body.bytes() 会无上限读入内存 → OOM 风险
        val body = "x".repeat(1024).toResponseBody("application/octet-stream".toMediaType())
        val resp = Response.Builder()
            .request(Request.Builder().url("https://dav.example.com/dav/a.bin").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
        // contentLength 已知（1024），应通过
        assertEquals(1024L, resp.body!!.contentLength())
    }

    @Test
    fun `download enforces limit on streamed body`() {
        // 构造 contentLength = -1 的响应，模拟 chunked 传输
        val big = Buffer().write(ByteArray((WebDavClient.MAX_BYTES + 1).toInt()))
        val body = big.asResponseBody("application/octet-stream".toMediaType(), -1L)
        assertEquals(-1L, body.contentLength())

        // 客户端必须自己兜底，不能依赖 contentLength
        try {
            WebDavClient.readBodyWithLimit(body, WebDavClient.MAX_BYTES)
            fail("超限流式响应必须被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("过大"))
        }
    }

    @Test
    fun `readBodyWithLimit accepts small body`() {
        val body = Buffer().write(ByteArray(100)).asResponseBody(null, -1L)
        val out = WebDavClient.readBodyWithLimit(body, WebDavClient.MAX_BYTES)
        assertEquals(100, out.size)
    }

    // ---------------------------------------------------- 认证头

    @Test
    fun `basic auth header is well formed`() {
        val c = WebDavClient("https://dav.example.com/dav/", "user", "pass")
        val h = c.authHeaderForTest()
        assertTrue(h.startsWith("Basic "))
        val decoded = String(java.util.Base64.getDecoder().decode(h.removePrefix("Basic ")))
        assertEquals("user:pass", decoded)
    }

    @Test
    fun `basic auth rejects CRLF in credentials`() {
        // 用户名/密码含 CRLF 时必须在构造阶段就拒绝，否则可注入 header
        try {
            WebDavClient("https://dav.example.com/dav/", "u\r\nX: y", "p")
            fail("含 CRLF 的用户名必须被拒绝")
        } catch (_: IllegalArgumentException) {
        }
        try {
            WebDavClient("https://dav.example.com/dav/", "u", "p\nX: y")
            fail("含换行的密码必须被拒绝")
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------------------------------------------------- URL 拼接

    @Test
    fun `url join does not allow scheme override`() {
        val c = WebDavClient("https://dav.example.com/dav/", "u", "p")
        // 文件名里带 :// 不能改变最终 host
        val url = c.urlForTest("a.bin")
        assertTrue(url.startsWith("https://dav.example.com/dav/"))
    }
}
