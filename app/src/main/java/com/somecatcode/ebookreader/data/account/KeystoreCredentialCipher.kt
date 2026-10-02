package com.somecatcode.ebookreader.data.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a random 12 byte IV per value and the account id as associated data.
 * Output format: `iv || ciphertext || tag`. The key comes from [keyProvider] (`create = true` may
 * generate it); this indirection is the test seam, production uses [KeystoreCredentialCipher].
 */
open class AesGcmCredentialCipher(
    private val keyProvider: (create: Boolean) -> SecretKey?,
) : CredentialCipher {

    private val random = SecureRandom()

    override fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray {
        val key = keyProvider(true) ?: throw GeneralSecurityException("No encryption key")
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    override fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
        if (encrypted.size < IV_BYTES + TAG_BITS / 8) throw GeneralSecurityException("Ciphertext too short")
        val key = keyProvider(false) ?: throw GeneralSecurityException("Key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, encrypted.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(aad)
        return cipher.doFinal(encrypted, IV_BYTES, encrypted.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** [CredentialCipher] with a non-exportable key (alias `ebookreader_credentials`) in the Android Keystore. */
class KeystoreCredentialCipher : AesGcmCredentialCipher({ create -> loadKey(create) }) {

    companion object {
        const val KEY_ALIAS = "ebookreader_credentials"
        private const val PROVIDER = "AndroidKeyStore"

        @Synchronized
        private fun loadKey(create: Boolean): SecretKey? {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            if (!create) return null
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
