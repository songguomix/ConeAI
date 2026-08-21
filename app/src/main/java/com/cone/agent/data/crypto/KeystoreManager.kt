package com.cone.agent.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Ciphertext + IV pair, both Base64 encoded for storage in Room. */
data class Sealed(val cipherText: String, val iv: String)

/**
 * Encrypts/decrypts secrets (API keys) with an AES-256-GCM key that lives inside the
 * hardware-backed Android Keystore. The raw key material never leaves the Keystore and
 * is never written to disk in plaintext.
 */
@Singleton
class KeystoreManager @Inject constructor() {

    private val keyStore: KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey? {
        val ks = keyStore ?: return null
        return try {
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
                val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
                generator.init(spec)
                generator.generateKey()
            }
        } catch (e: Exception) {
            null
        }
    }

    fun encrypt(plain: String): Sealed {
        val key = getOrCreateKey() ?: return Sealed("", "")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val bytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Sealed(
                cipherText = Base64.encodeToString(bytes, Base64.NO_WRAP),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP),
            )
        } catch (e: Exception) {
            Sealed("", "")
        }
    }

    fun decrypt(sealed: Sealed): String {
        if (sealed.cipherText.isBlank()) return ""
        val key = getOrCreateKey() ?: return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val ivBytes = Base64.decode(sealed.iv, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, ivBytes))
            val bytes = cipher.doFinal(Base64.decode(sealed.cipherText, Base64.NO_WRAP))
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cone_agent_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
