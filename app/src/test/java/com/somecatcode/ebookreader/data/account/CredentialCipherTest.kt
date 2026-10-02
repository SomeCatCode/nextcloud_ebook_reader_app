package com.somecatcode.ebookreader.data.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The Keystore key is swapped for a plain JCE key (test seam): format and AAD rules are identical. */
class CredentialCipherTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun roundTripWithAad() {
        val key = newKey()
        val cipher = AesGcmCredentialCipher { key }
        val plain = "app-password-test".toByteArray()
        val encrypted = cipher.encrypt(plain, "acc-1".toByteArray())
        assertEquals(12 + plain.size + 16, encrypted.size) // iv || ciphertext || tag
        assertArrayEquals(plain, cipher.decrypt(encrypted, "acc-1".toByteArray()))
    }

    @Test
    fun ivIsRandomPerValue() {
        val key = newKey()
        val cipher = AesGcmCredentialCipher { key }
        val a = cipher.encrypt("x".toByteArray(), byteArrayOf())
        val b = cipher.encrypt("x".toByteArray(), byteArrayOf())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun wrongAccountIdOrTamperingFails() {
        val key = newKey()
        val cipher = AesGcmCredentialCipher { key }
        val encrypted = cipher.encrypt("secret".toByteArray(), "acc-1".toByteArray())
        try {
            cipher.decrypt(encrypted, "acc-2".toByteArray())
            fail("AAD mismatch must fail")
        } catch (e: GeneralSecurityException) {
            // expected
        }
        val tampered = encrypted.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            cipher.decrypt(tampered, "acc-1".toByteArray())
            fail("tampering must fail")
        } catch (e: GeneralSecurityException) {
            // expected
        }
    }

    @Test
    fun missingOrInvalidatedKeyFailsToDecrypt() {
        val encrypted = AesGcmCredentialCipher { newKey() }.encrypt("secret".toByteArray(), byteArrayOf())
        try {
            AesGcmCredentialCipher { null }.decrypt(encrypted, byteArrayOf())
            fail()
        } catch (e: GeneralSecurityException) {
            // expected: key is gone
        }
        try {
            AesGcmCredentialCipher { newKey() }.decrypt(encrypted, byteArrayOf())
            fail()
        } catch (e: GeneralSecurityException) {
            // expected: different key
        }
    }
}
