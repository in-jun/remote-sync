package dev.injun.remotesync.data.config

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small key-value store whose values are encrypted with an AES-256-GCM key that lives
 * in the Android Keystore, so the SMB passwords in the pair list never touch disk in
 * the clear. Keys (setting names) are not secret and stay readable.
 *
 * Each value is stored as base64(iv || ciphertext) in a plain [SharedPreferences] file.
 * The preference key is bound as GCM associated data, so a ciphertext copied under
 * another key fails to decrypt instead of being accepted.
 *
 * Reads throw when a value cannot be decrypted (tampered file, or the Keystore key was
 * lost); callers treat that as a load failure rather than silently returning defaults.
 */
class SecurePreferences(
    context: Context,
    name: String = DEFAULT_NAME,
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private val key: SecretKey by lazy { loadOrCreateKey() }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun getString(key: String): String? = prefs.getString(key, null)?.let { decrypt(key, it) }

    fun getLong(key: String, default: Long): Long = getString(key)?.toLong() ?: default

    fun getInt(key: String, default: Int): Int = getString(key)?.toInt() ?: default

    fun getFloat(key: String, default: Float): Float = getString(key)?.toFloat() ?: default

    fun getBoolean(key: String, default: Boolean): Boolean = getString(key)?.toBooleanStrict() ?: default

    /** Applies every change in [block] as one atomic write. */
    fun edit(block: Editor.() -> Unit) {
        val editor = prefs.edit()
        Editor(editor).block()
        editor.apply()
    }

    inner class Editor internal constructor(private val editor: SharedPreferences.Editor) {
        fun putString(key: String, value: String) = apply { editor.putString(key, encrypt(key, value)) }
        fun putLong(key: String, value: Long) = putString(key, value.toString())
        fun putInt(key: String, value: Int) = putString(key, value.toString())
        fun putFloat(key: String, value: Float) = putString(key, value.toString())
        fun putBoolean(key: String, value: Boolean) = putString(key, value.toString())
        fun remove(key: String) = apply { editor.remove(key) }
    }

    private fun encrypt(prefKey: String, plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(prefKey.toByteArray())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(prefKey: String, stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Stored value for '$prefKey' is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        cipher.updateAAD(prefKey.toByteArray())
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES))
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val DEFAULT_NAME = "remote-sync-config"
        const val DEFAULT_KEY_ALIAS = "remote-sync-config"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}
