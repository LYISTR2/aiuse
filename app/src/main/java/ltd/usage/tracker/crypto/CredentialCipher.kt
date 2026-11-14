package ltd.usage.tracker.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM 凭据加解密。
 *
 * 密钥由 AndroidKeyStore 生成并保管（TEE / StrongBox），应用进程永远拿不到密钥字节。
 * 文件格式：iv(12) || ciphertext || tag(16)
 *
 * 与参考实现的差异：
 *  - 解密时校验长度，避免短数组导致 GCMParameterSpec 抛异常
 *  - 密钥别名带版本号，便于将来轮换
 */
object CredentialCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "ltd.usage.tracker.credentials.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE).apply { load(null) }

    /** 取出或首次生成密钥。 */
    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    /** 加密，返回 iv || ciphertext || tag。 */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "unexpected IV length: ${iv.size}" }
        val body = cipher.doFinal(plaintext)
        return iv + body
    }

    /** 解密 iv || ciphertext || tag。 */
    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LENGTH + TAG_BITS / 8) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(TAG_BITS, blob, 0, IV_LENGTH)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        return cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH)
    }

    /** 删除密钥（用于「清除全部数据」）。 */
    fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
    }
}
