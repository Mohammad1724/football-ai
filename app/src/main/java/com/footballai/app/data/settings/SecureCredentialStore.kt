package com.footballai.app.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores provider API keys encrypted with a hardware-backed Android Keystore key.
 *
 * Keys are never compiled into the APK, never written in plain text and are excluded
 * from cloud backups (see res/xml/backup_rules.xml). If the keystore entry is
 * invalidated (for example after a factory reset) the stored value is discarded and
 * the user is asked to re-enter the key.
 */
interface CredentialStore {
    fun getKey(providerId: String): String?
    fun saveKey(providerId: String, apiKey: String)
    fun removeKey(providerId: String)
    fun hasKey(providerId: String): Boolean = !getKey(providerId).isNullOrBlank()
}

class KeystoreCredentialStore(context: Context) : CredentialStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun getKey(providerId: String): String? {
        val stored = preferences.getString(preferenceKey(providerId), null) ?: return null
        return try {
            decrypt(stored)
        } catch (error: Exception) {
            preferences.edit().remove(preferenceKey(providerId)).apply()
            null
        }
    }

    override fun saveKey(providerId: String, apiKey: String) {
        val encrypted = encrypt(apiKey)
        preferences.edit().putString(preferenceKey(providerId), encrypted).apply()
    }

    override fun removeKey(providerId: String) {
        preferences.edit().remove(preferenceKey(providerId)).apply()
    }

    private fun preferenceKey(providerId: String) = "credential_$providerId"

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + cipherText
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(payload: String): String {
        val bytes = Base64.decode(payload, Base64.NO_WRAP)
        if (bytes.size <= IV_SIZE) error("Corrupted credential payload")
        val iv = bytes.copyOfRange(0, IV_SIZE)
        val cipherText = bytes.copyOfRange(IV_SIZE, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }

    companion object {
        private const val PREFERENCES_NAME = "secure_credentials"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "football_ai_credentials"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }
}
