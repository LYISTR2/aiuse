package ltd.usage.tracker.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 解密失败不得把空映射写回 credentials.bin；
 * 损坏文件要备份且只留最新一份；
 * 分次恢复账号时已写入的条目不能丢。
 *
 * 测试里用固定 AES-256-GCM 密钥，避开 Robolectric 下 AndroidKeyStore 不稳定的问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialDecryptFailureTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val key = ByteArray(32) { 7 }
    private var clock = 1_000_000L

    private fun aesEncrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plain)
    }

    private fun aesDecrypt(blob: ByteArray): ByteArray {
        require(blob.size >= 12 + 16) { "密文太短" }
        val iv = blob.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    private fun store() = Store(
        app,
        encrypt = ::aesEncrypt,
        decrypt = ::aesDecrypt,
        nowMs = { clock },
    )

    @Before
    fun clean() {
        store().wipe()
        clock = 1_000_000L
    }

    @Test
    fun loadCorruptDoesNotOverwriteLiveFile() {
        val s = store()
        val live = File(s.directory, Store.CREDENTIALS_FILE)
        val raw = ByteArray(64) { 0xFF.toByte() }
        live.writeBytes(raw)

        assertTrue(s.loadCredentialState() is CredentialState.Undecryptable)
        assertEquals(emptyMap<String, Credential>(), s.loadCredentials())
        assertTrue(live.exists())
        assertTrue(live.readBytes().contentEquals(raw))
    }

    @Test
    fun corruptBackupCreatedAndOnlyLatestKept() {
        val s = store()
        val live = File(s.directory, Store.CREDENTIALS_FILE)
        val firstBytes = ByteArray(32) { 0xAA.toByte() }
        live.writeBytes(firstBytes)

        clock = 111L
        assertTrue(s.loadCredentialState() is CredentialState.Undecryptable)
        val first = s.listCorruptBackups()
        assertEquals(1, first.size)
        assertEquals(Store.CORRUPT_PREFIX + "111", first.single().name)
        assertTrue(first.single().readBytes().contentEquals(firstBytes))
        assertTrue(live.readBytes().contentEquals(firstBytes))

        clock = 222L
        s.loadCredentialState()
        val second = s.listCorruptBackups()
        assertEquals(1, second.size)
        assertEquals(Store.CORRUPT_PREFIX + "222", second.single().name)
        assertTrue(second.single().readBytes().contentEquals(firstBytes))
        assertFalse(File(s.directory, Store.CORRUPT_PREFIX + "111").exists())
        assertTrue(live.readBytes().contentEquals(firstBytes))
    }

    @Test
    fun putAfterCorruptMergesRecoveredAccounts() {
        val s = store()
        s.putCredential("old", Credential("tok-old"))
        val live = File(s.directory, Store.CREDENTIALS_FILE)
        live.writeBytes(ByteArray(32) { 0xAA.toByte() })

        clock = 333L
        s.putCredential("a1", Credential("tok-a1"))
        val afterA = s.loadCredentialState() as CredentialState.Available
        assertEquals(setOf("a1"), afterA.credentials.keys)
        assertEquals("tok-a1", afterA.credentials.getValue("a1").token)
        assertEquals(1, s.listCorruptBackups().size)

        s.putCredential("a2", Credential("tok-a2"))
        val afterB = s.loadCredentialState() as CredentialState.Available
        assertEquals(setOf("a1", "a2"), afterB.credentials.keys)
        assertEquals("tok-a1", afterB.credentials.getValue("a1").token)
        assertEquals("tok-a2", afterB.credentials.getValue("a2").token)

        s.removeCredential("a1")
        assertEquals(setOf("a2"), s.loadCredentials().keys)
        assertEquals("tok-a2", s.loadCredentials().getValue("a2").token)
    }

    @Test
    fun removeCredentialOnUndecryptableDoesNotOverwrite() {
        val s = store()
        val live = File(s.directory, Store.CREDENTIALS_FILE)
        val raw = ByteArray(32) { 0xFF.toByte() }
        live.writeBytes(raw)

        s.removeCredential("a1")
        assertTrue(live.exists())
        assertTrue(live.readBytes().contentEquals(raw))
        assertTrue(s.loadCredentialState() is CredentialState.Undecryptable)
    }
}
