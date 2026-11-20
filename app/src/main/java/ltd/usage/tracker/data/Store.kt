package ltd.usage.tracker.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ltd.usage.tracker.crypto.CredentialCipher
import java.io.File

/**
 * 持久层。
 *
 * 布局（filesDir/store/）：
 *   accounts.json     明文，仅元数据（id/provider/name/时间）
 *   credentials.bin   AES-GCM 加密的凭据
 *   snapshots.json    明文，用量历史
 *   settings.json     明文，设置
 *
 * 元数据与密钥物理分离：即使 accounts.json 泄露也拿不到凭据。
 */
/**
 * 凭据读取结果：把「还没有凭据文件」和「文件在但解不开」分开，
 * 避免解密失败时被当成空库再写回去。
 */
sealed class CredentialState {
    data class Available(val credentials: Map<String, Credential>) : CredentialState()
    data object Missing : CredentialState()
    data object Undecryptable : CredentialState()
}

class Store(
    context: Context,
    private val encrypt: (ByteArray) -> ByteArray = CredentialCipher::encrypt,
    private val decrypt: (ByteArray) -> ByteArray = CredentialCipher::decrypt,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val root: File = File(context.filesDir, "store").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }
    private val lock = Any()

    private val accountsSerializer = ListSerializer(StoredAccount.serializer())
    private val snapshotsSerializer = ListSerializer(UsageSnapshot.serializer())
    private val credentialMapSerializer = MapSerializer(String.serializer(), CredentialDto.serializer())
    private val settingsSerializer = MapSerializer(String.serializer(), String.serializer())

    private fun file(name: String) = File(root, name)

    // ---------- 账号元数据 ----------

    fun loadAccounts(): List<StoredAccount> {
        val f = file("accounts.json")
        if (!f.exists()) return emptyList()
        return runCatching { json.decodeFromString(accountsSerializer, f.readText()) }
            .getOrDefault(emptyList())
    }

    fun saveAccounts(accounts: List<StoredAccount>) = synchronized(lock) {
        writeAtomic("accounts.json", json.encodeToString(accountsSerializer, accounts))
    }

    // ---------- 凭据（加密） ----------

    /** 可读时返回映射；缺失或解不开时返回空 Map，不会写盘。写路径请用 [loadCredentialState] / [putCredential]。 */
    fun loadCredentials(): Map<String, Credential> =
        when (val s = loadCredentialState()) {
            is CredentialState.Available -> s.credentials
            is CredentialState.Missing, is CredentialState.Undecryptable -> emptyMap()
        }

    fun loadCredentialState(): CredentialState = synchronized(lock) { loadCredentialStateLocked() }

    private fun loadCredentialStateLocked(): CredentialState {
        val f = file(CREDENTIALS_FILE)
        if (!f.exists()) return CredentialState.Missing
        return try {
            val plain = decrypt(f.readBytes())
            val map = json.decodeFromString(credentialMapSerializer, plain.decodeToString())
                .mapValues { (_, d) -> Credential(d.token, d.refreshToken, d.accountId, d.expiresAt) }
            CredentialState.Available(map)
        } catch (_: Exception) {
            backupCorruptLocked()
            CredentialState.Undecryptable
        }
    }

    /**
     * 写入完整映射。若当前密文已经解不开，先备份再写，避免把原文件静默覆盖成空库。
     */
    fun saveCredentials(creds: Map<String, Credential>) = synchronized(lock) {
        if (loadCredentialStateLocked() is CredentialState.Undecryptable) {
            // load 里已经做过备份
        }
        writeCredentialsLocked(creds)
    }

    /**
     * 写入或更新一条凭据。当前文件可读则与已有条目合并；
     * 解不开时先备份原文件，再只写入这一条（以及其他已经恢复、写在新文件里的条目）。
     */
    fun putCredential(id: String, cred: Credential) = synchronized(lock) {
        val base = when (val s = loadCredentialStateLocked()) {
            is CredentialState.Available -> s.credentials
            is CredentialState.Missing -> emptyMap()
            is CredentialState.Undecryptable -> emptyMap()
        }
        writeCredentialsLocked(base + (id to cred))
    }

    /**
     * 删除一条凭据。密文解不开时只保留备份，不把空映射写回原文件。
     */
    fun removeCredential(id: String) = synchronized(lock) {
        when (val s = loadCredentialStateLocked()) {
            is CredentialState.Available -> writeCredentialsLocked(s.credentials - id)
            is CredentialState.Missing -> Unit
            is CredentialState.Undecryptable -> Unit
        }
    }

    private fun writeCredentialsLocked(creds: Map<String, Credential>) {
        val dto = creds.mapValues { (_, c) ->
            CredentialDto(c.token, c.refreshToken, c.accountId, c.expiresAt)
        }
        val plain = json.encodeToString(credentialMapSerializer, dto).encodeToByteArray()
        writeAtomicBytes(CREDENTIALS_FILE, encrypt(plain))
    }

    /**
     * 把当前 credentials.bin 复制为 credentials.bin.corrupt-<时间戳>，只留最新一份。
     * 原文件不动：在用户写入新凭据之前，损坏密文还在原位。
     */
    private fun backupCorruptLocked() {
        val src = file(CREDENTIALS_FILE)
        if (!src.exists()) return
        val dest = file(CORRUPT_PREFIX + nowMs())
        src.copyTo(dest, overwrite = true)
        root.listFiles()?.filter { it.name.startsWith(CORRUPT_PREFIX) && it != dest }?.forEach { it.delete() }
    }

    fun listCorruptBackups(): List<File> =
        synchronized(lock) {
            root.listFiles()?.filter { it.name.startsWith(CORRUPT_PREFIX) }?.sortedBy { it.name }.orEmpty()
        }

    // ---------- 用量快照 ----------

    fun loadSnapshots(): List<UsageSnapshot> {
        val f = file("snapshots.json")
        if (!f.exists()) return emptyList()
        return runCatching { json.decodeFromString(snapshotsSerializer, f.readText()) }
            .getOrDefault(emptyList())
    }

    fun saveSnapshots(list: List<UsageSnapshot>) = synchronized(lock) {
        writeAtomic("snapshots.json", json.encodeToString(snapshotsSerializer, list))
    }

    // ---------- 设置 ----------

    fun loadSettings(): Map<String, String> {
        val f = file("settings.json")
        if (!f.exists()) return emptyMap()
        return runCatching { json.decodeFromString(settingsSerializer, f.readText()) }
            .getOrDefault(emptyMap())
    }

    fun saveSettings(settings: Map<String, String>) = synchronized(lock) {
        writeAtomic("settings.json", json.encodeToString(settingsSerializer, settings))
    }

    // ---------- 原子写 ----------

    private fun writeAtomic(name: String, content: String) =
        writeAtomicBytes(name, content.encodeToByteArray())

    private fun writeAtomicBytes(name: String, bytes: ByteArray) {
        val target = file(name)
        val tmp = File(root, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    /**
     * 清除全部本地数据。
     *
     * 删密钥失败不能阻断文件清理：文件已经删了，若再抛异常，
     * 用户会看到「清除失败」却找不到残留文件。密钥删除失败只意味着
     * 密文可能仍被旧密钥保护，但密文本身已随文件一起删除。
     */
    fun wipe() = synchronized(lock) {
        root.listFiles()?.forEach { it.deleteRecursively() }
        runCatching { CredentialCipher.deleteKey() }
    }

    val directory: File get() = root

    companion object {
        const val CREDENTIALS_FILE = "credentials.bin"
        const val CORRUPT_PREFIX = "credentials.bin.corrupt-"
    }
}

@kotlinx.serialization.Serializable
internal data class CredentialDto(
    val token: String,
    val refreshToken: String? = null,
    val accountId: String? = null,
    val expiresAt: Long? = null,
)
