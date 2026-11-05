package com.coke.nestspace.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts LLM API keys before they are written to SharedPreferences.
 *
 * The AES key lives in AndroidKeyStore and is non-exportable. The stored value
 * is `enc:v1:` + Base64(version || GCM IV || GCM ciphertext/tag). A plaintext
 * value (older versions of the app) is accepted on read and re-encrypted by
 * LlmConfigStore.save().
 */
internal object ApiKeyCipher {

    private const val TAG = "ApiKeyCipher"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "nestspace.llm.api_key.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val PREFIX = "enc:v1:"
    private const val VERSION: Byte = 1
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    fun encrypt(plain: String): String {
        if (plain.isBlank()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val payload = ByteArray(1 + iv.size + encrypted.size)
            payload[0] = VERSION
            System.arraycopy(iv, 0, payload, 1, iv.size)
            System.arraycopy(encrypted, 0, payload, 1 + iv.size, encrypted.size)
            PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.e(TAG, "API key encryption failed", t)
            ""
        }
    }

    fun decrypt(stored: String): String {
        if (stored.isBlank()) return ""
        if (!isEncrypted(stored)) return stored
        return try {
            val payload = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (payload.size <= 1 + IV_LENGTH || payload[0] != VERSION) return ""
            val iv = payload.copyOfRange(1, 1 + IV_LENGTH)
            val cipherText = payload.copyOfRange(1 + IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, iv)
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w(TAG, "API key decryption failed", t)
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(ALIAS)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
        }
        return (keyStore.getEntry(ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
}
