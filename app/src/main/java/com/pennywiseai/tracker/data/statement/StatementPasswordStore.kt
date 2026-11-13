package com.pennywiseai.tracker.data.statement

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Statement PDF passwords, remembered per bank (and card) so a locked statement
 * opens without asking again. Each is encrypted with AES-GCM under a key that
 * lives in the Android Keystore — it never leaves the device's secure hardware,
 * so the stored text is useless anywhere else, and a restored copy on another
 * phone simply reads as "no saved passwords".
 */
@Singleton
class StatementPasswordStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Remembers [password] for [bankName] (and [cardLast4], when the statement is for one card). */
    fun save(bankName: String, cardLast4: String?, password: String) {
        val encrypted = runCatching { encrypt(password) }.getOrNull() ?: return
        prefs.edit().putString(entryKey(bankName, cardLast4), encrypted).apply()
    }

    /** Every remembered password, to try on a locked PDF whose bank isn't known until it opens. */
    fun all(): List<String> =
        prefs.all.values.mapNotNull { (it as? String)?.let { blob -> runCatching { decrypt(blob) }.getOrNull() } }.distinct()

    /** Every remembered password by its [entryKey], decrypted — for a backup, which is itself encrypted. */
    fun entries(): Map<String, String> =
        prefs.all.mapNotNull { (key, blob) ->
            (blob as? String)?.let { runCatching { decrypt(it) }.getOrNull() }?.let { key to it }
        }.toMap()

    /** Remembers passwords from a backup; one already saved on this phone is kept. */
    fun restore(passwords: Map<String, String>) {
        val editor = prefs.edit()
        passwords.forEach { (key, password) ->
            if (!prefs.contains(key)) runCatching { encrypt(password) }.getOrNull()?.let { editor.putString(key, it) }
        }
        editor.apply()
    }

    fun isEmpty(): Boolean = prefs.all.isEmpty()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val bytes = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        private const val PREFS = "statement_passwords"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pennywise_statement_passwords"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        /** One entry per bank + card; a bank-wide statement (no card) shares the bank's entry. */
        internal fun entryKey(bankName: String, cardLast4: String?) =
            "${bankName.trim().lowercase()}|${cardLast4.orEmpty()}"
    }
}
