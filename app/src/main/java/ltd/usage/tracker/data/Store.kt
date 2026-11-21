package ltd.usage.tracker.data

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import ltd.usage.tracker.crypto.CredentialCipher
import java.io.File

/**
 * 凭据读取结果：把「还没有凭据文件」和「文件在但解不开」分开，
 * 避免解密失败时被当成空库再写回去。
 */
sealed class CredentialState {
    data class Available(val credentials: Map<String, Credential>) : CredentialState()
    data object Missing : CredentialState()
    data object Undecryptable : CredentialState()
}

/**
 * 持久层（filesDir/store/）：元数据明文与凭据密文物理分离。
 */
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

    private fun <T> readJson(name: String, serializer: KSerializer<T>, default: T): T {
        val f = file(name)
        if (!f.exists()) return default
        return runCatching { json.decodeFromString(serializer, f.readText()) }.getOrDefault(default)
    }

    private fun <T> writeJson(name: String, serializer: KSerializer<T>, value: T) = synchronized(lock) {
        writeAtomicBytes(name, json.encodeToString(serializer, value).encodeToByteArray())
    }

    // ---------- 账号元数据 ----------

    fun loadAccounts(): List<StoredAccount> = readJson("accounts.json", accountsSerializer, emptyList())

    fun saveAccounts(accounts: List<StoredAccount>) = writeJson("accounts.json", accountsSerializer, accounts)

    // ---------- 凭据（加密） ----------

    fun loadCredentials(): Map<String, Credential> =
        (loadCredentialState() as? CredentialState.Available)?.credentials.orEmpty()

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

    fun saveCredentials(creds: Map<String, Credential>) = synchronized(lock) {
        // 若当前密文解不开，先通过 loadCredentialStateLocked() 备份原文件，再写入
        loadCredentialStateLocked()
        writeCredentialsLocked(creds)
    }

    fun putCredential(id: String, cred: Credential) = synchronized(lock) {
        val base = (loadCredentialStateLocked() as? CredentialState.Available)?.credentials.orEmpty()
        writeCredentialsLocked(base + (id to cred))
    }

    fun removeCredential(id: String) = synchronized(lock) {
        val state = loadCredentialStateLocked()
        if (state is CredentialState.Available) {
            writeCredentialsLocked(state.credentials - id)
        }
    }

    private fun writeCredentialsLocked(creds: Map<String, Credential>) {
        val dto = creds.mapValues { (_, c) ->
            CredentialDto(c.token, c.refreshToken, c.accountId, c.expiresAt)
        }
        val plain = json.encodeToString(credentialMapSerializer, dto).encodeToByteArray()
        writeAtomicBytes(CREDENTIALS_FILE, encrypt(plain))
    }

    private fun backupCorruptLocked() {
        val src = file(CREDENTIALS_FILE)
        if (!src.exists()) return
        val dest = file(CORRUPT_PREFIX + nowMs())
        src.copyTo(dest, overwrite = true)
        root.listFiles()?.filter { it.name.startsWith(CORRUPT_PREFIX) && it != dest }?.forEach { it.delete() }
    }

    fun listCorruptBackups(): List<File> = synchronized(lock) {
        root.listFiles()?.filter { it.name.startsWith(CORRUPT_PREFIX) }?.sortedBy { it.name }.orEmpty()
    }

    // ---------- 用量快照 ----------

    fun loadSnapshots(): List<UsageSnapshot> = readJson("snapshots.json", snapshotsSerializer, emptyList())

    fun saveSnapshots(list: List<UsageSnapshot>) = writeJson("snapshots.json", snapshotsSerializer, list)

    // ---------- 设置 ----------

    fun loadSettings(): Map<String, String> = readJson("settings.json", settingsSerializer, emptyMap())

    fun saveSettings(settings: Map<String, String>) = writeJson("settings.json", settingsSerializer, settings)

    // ---------- 原子写 ----------

    private fun writeAtomicBytes(name: String, bytes: ByteArray) {
        val target = file(name)
        val tmp = File(root, "$name.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

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

@Serializable
internal data class CredentialDto(
    val token: String,
    val refreshToken: String? = null,
    val accountId: String? = null,
    val expiresAt: Long? = null,
)
