package com.moneytracker.app.data.statement

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts the saved statement files at rest with AES-GCM under a key held in the
 * Android Keystore, like [StatementPasswordStore]. A copied file is unreadable
 * anywhere else. Files written before this existed are plain; [decrypt] hands those
 * back unchanged so nothing already saved is lost.
 */
@Singleton
class StatementFileCipher @Inject constructor() {

    /** [MAGIC], then the IV, then the ciphertext. */
    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return MAGIC + cipher.iv + cipher.doFinal(plain)
    }

    fun decrypt(stored: ByteArray): ByteArray {
        if (!isEncrypted(stored)) return stored
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, stored, MAGIC.size, IV_BYTES))
        }
        val start = MAGIC.size + IV_BYTES
        return cipher.doFinal(stored, start, stored.size - start)
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
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "pennywise_statement_files"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val MAGIC = byteArrayOf('P'.code.toByte(), 'W'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte())

        internal fun isEncrypted(bytes: ByteArray): Boolean =
            bytes.size > MAGIC.size + IV_BYTES && MAGIC.indices.all { bytes[it] == MAGIC[it] }
    }
}
