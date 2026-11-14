package ltd.usage.tracker.backup

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * WebDAV 备份：把 store 目录打包上传到用户自己的网盘。
 *
 * 安全约束：
 *  - 只接受 https:// （除非是 localhost，便于本地调试）
 *  - 备份内容是**已加密**的 credentials.bin 原样上传，服务器拿不到明文
 *  - 上传前校验大小上限，避免误传超大文件
 *  - 文件名必须是不含路径分隔符的纯名字，防止写出备份目录
 *  - 下载时按流式读取强制上限，不信任 Content-Length（chunked 时为 -1）
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val binType = "application/octet-stream".toMediaType()

    init {
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://localhost")) {
            "WebDAV 地址必须是 https://"
        }
        require(baseUrl.endsWith("/")) { "WebDAV 目录必须以 / 结尾" }
        require(!username.contains('\r') && !username.contains('\n') &&
            !password.contains('\r') && !password.contains('\n')) {
            "WebDAV 用户名/密码不得包含换行符"
        }
    }

    private fun auth(): String = Credentials.basic(username, password)

    fun upload(fileName: String, bytes: ByteArray) {
        validateFileName(fileName)
        require(bytes.size <= MAX_BYTES) { "备份过大: ${bytes.size} 字节" }
        val req = Request.Builder()
            .url(baseUrl + fileName)
            .put(bytes.toRequestBody(binType))
            .header("Authorization", auth())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("WebDAV 上传失败: HTTP ${resp.code}")
        }
    }

    fun download(fileName: String): ByteArray {
        validateFileName(fileName)
        val req = Request.Builder()
            .url(baseUrl + fileName)
            .get()
            .header("Authorization", auth())
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("WebDAV 下载失败: HTTP ${resp.code}")
            val body = resp.body ?: error("空响应")
            return readBodyWithLimit(body, MAX_BYTES)
        }
    }

    fun ensureDirectory(): Boolean {
        val req = Request.Builder()
            .url(baseUrl)
            .method("MKCOL", null)
            .header("Authorization", auth())
            .build()
        return client.newCall(req).execute().use { it.isSuccessful || it.code == 405 }
    }

    // ---------------------------------------------------------------- 测试可见

    internal fun authHeaderForTest(): String = auth()

    internal fun urlForTest(fileName: String): String {
        validateFileName(fileName)
        return baseUrl + fileName
    }

    companion object {
        const val MAX_BYTES = 32L * 1024 * 1024

        /**
         * 校验备份文件名。
         *
         * 必须是单段名字：不含 `/`、`\`，不是 `.` / `..`，非空。
         * 否则 `baseUrl + name` 会拼出备份目录之外的路径。
         */
        fun validateFileName(name: String) {
            require(name.isNotEmpty()) { "文件名不能为空" }
            require(name != "." && name != "..") { "非法文件名: $name" }
            require(!name.contains('/') && !name.contains('\\')) {
                "文件名不得包含路径分隔符: $name"
            }
            require(!name.contains('\u0000')) { "文件名不得包含空字节" }
        }

        /**
         * 流式读取并强制上限。
         *
         * 不能用 `contentLength() <= MAX_BYTES` 判断：chunked 传输时它返回 -1，
         * 比较恒为真，随后 `body.bytes()` 会无上限读入内存。
         */
        fun readBodyWithLimit(body: ResponseBody, limit: Long): ByteArray {
            val declared = body.contentLength()
            if (declared > limit) throw IllegalArgumentException("备份过大: $declared 字节")
            val source = body.source()
            val buffer = okio.Buffer()
            var total = 0L
            while (true) {
                val read = source.read(buffer, 8192L)
                if (read == -1L) break
                total += read
                if (total > limit) throw IllegalArgumentException("备份过大: 超过 $limit 字节")
            }
            return buffer.readByteArray()
        }
    }
}
