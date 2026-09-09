package com.nuvio.tv.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SecureStringCipher"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "nuvio_secure_string_key_v1"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128
private const val ENCRYPTED_PREFIX = "enc1:"

/**
 * AES-256-GCM encryption for individual sensitive string values (VPN WireGuard private
 * key, debrid provider API keys) that are stored in DataStore preferences, which is NOT
 * encrypted at rest. The key lives in the Android Keystore and never leaves it, is
 * generated on first use, and is not exportable.
 *
 * Values are self-describing: [encrypt] prefixes its output so [decrypt] - and, more
 * importantly, every existing caller reading a value written before this class existed -
 * can tell an already-encrypted value apart from legacy plaintext. [decrypt] returns a
 * value that isn't prefixed unchanged, so old plaintext data keeps working exactly as
 * before and is expected to get silently upgraded to encrypted form the next time the
 * caller writes it back (e.g. on next save) rather than being wiped or rejected.
 */
@Singleton
class SecureStringCipher @Inject constructor() {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /** Encrypts [plaintext]. Falls back to returning it unchanged (logged) if the Keystore
     *  operation fails for any reason - callers must keep working even on a device/OEM
     *  where Keystore is unavailable, rather than losing the value entirely. */
    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return plaintext
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = cipher.iv + ciphertext
            ENCRYPTED_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Encryption failed, value will be stored unencrypted", e)
            plaintext
        }
    }

    /** Returns the decrypted value; the input unchanged if it's legacy plaintext (no
     *  [ENCRYPTED_PREFIX]); or empty string if it WAS encrypted but decryption failed
     *  (e.g. the Keystore key is gone - a full device restore onto different hardware).
     *  Callers should treat an empty result the same as "not configured", not as an error. */
    fun decrypt(value: String): String {
        if (value.isEmpty() || !value.startsWith(ENCRYPTED_PREFIX)) return value
        return try {
            val combined = Base64.decode(value.removePrefix(ENCRYPTED_PREFIX), Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_LENGTH_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Decryption failed, treating value as unavailable", e)
            ""
        }
    }
}
