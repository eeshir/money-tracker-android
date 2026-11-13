package com.moneytracker.app.data.backup

import android.content.Context
import android.util.Base64
import com.moneytracker.app.data.statement.StatementFileCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The backup passphrase, remembered on this phone so the daily backup can run without
 * asking. It is encrypted under a Keystore key, so the stored copy is useless anywhere
 * else; the passphrase itself is what opens a backup on a new phone, so it has to be
 * kept by the person too.
 */
@Singleton
class BackupPassphraseStore @Inject constructor(
    @ApplicationContext context: Context,
    private val cipher: StatementFileCipher
) {
    private val prefs = context.getSharedPreferences("backup_passphrase", Context.MODE_PRIVATE)

    fun isSet(): Boolean = get() != null

    fun get(): String? =
        prefs.getString(KEY, null)?.let { blob ->
            runCatching { String(cipher.decrypt(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()
        }

    fun set(passphrase: String) {
        val blob = Base64.encodeToString(cipher.encrypt(passphrase.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(KEY, blob).apply()
    }

    private companion object {
        const val KEY = "passphrase"
    }
}
