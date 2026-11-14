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
class Store(context: Context) {

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

    fun loadCredentials(): Map<String, Credential> {
        val f = file("credentials.bin")
        if (!f.exists()) return emptyMap()
        return runCatching {
            val plain = CredentialCipher.decrypt(f.readBytes())
            json.decodeFromString(credentialMapSerializer, plain.decodeToString())
                .mapValues { (_, d) -> Credential(d.token, d.refreshToken, d.accountId, d.expiresAt) }
        }.getOrDefault(emptyMap())
    }

    fun saveCredentials(creds: Map<String, Credential>) = synchronized(lock) {
        val dto = creds.mapValues { (_, c) ->
            CredentialDto(c.token, c.refreshToken, c.accountId, c.expiresAt)
        }
        val plain = json.encodeToString(credentialMapSerializer, dto).encodeToByteArray()
        writeAtomicBytes("credentials.bin", CredentialCipher.encrypt(plain))
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
}

@kotlinx.serialization.Serializable
internal data class CredentialDto(
    val token: String,
    val refreshToken: String? = null,
    val accountId: String? = null,
    val expiresAt: Long? = null,
)
