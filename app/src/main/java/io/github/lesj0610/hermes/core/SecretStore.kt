package io.github.lesj0610.hermes.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the gateway bearer token with a hardware-backed key before it is
 * written to disk.
 *
 * The token grants full agent execution on the user's machine, so it must not
 * sit in DataStore as plaintext. The key itself never leaves the Android
 * Keystore; only the IV and ciphertext are persisted.
 */
object SecretStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "hermes.gateway.token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12

    /** Returns "<base64 iv>:<base64 ciphertext>", or an empty string for an empty input. */
    fun seal(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return "${iv.b64()}:${body.b64()}"
    }

    /**
     * Reverses [seal]. Returns an empty string when the payload is malformed or
     * the key is gone — the user is then prompted for the token again, which is
     * the right outcome after a backup restore onto different hardware.
     */
    fun unseal(sealed: String): String {
        if (sealed.isEmpty()) return ""
        val parts = sealed.split(':')
        if (parts.size != 2) return ""
        return runCatching {
            val iv = parts[0].unB64()
            val body = parts[1].unB64()
            if (iv.size != IV_BYTES) return ""
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No setUserAuthenticationRequired: approvals must be answerable
                // straight from a notification, without a biometric prompt first.
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unB64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)
}
