package com.moneytracker.app.data.backup

import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The backup is encrypted but this passphrase does not open it. */
class WrongBackupPassphraseException : Exception("Wrong backup passphrase")

/** The backup is encrypted and no passphrase was given. */
class BackupPassphraseRequiredException : Exception("This backup is encrypted")

/**
 * Passphrase encryption for backup files. The key is derived from the passphrase
 * (PBKDF2-HMAC-SHA256, a random salt per file) and the file sealed with AES-GCM, so
 * the backup opens on any phone with the passphrase and on none without it. Unlike a
 * Keystore key, nothing here is lost when the app is uninstalled.
 *
 * Layout: [MAGIC] [salt] [iv] [ciphertext + tag].
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('P'.code.toByte(), 'W'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), 1)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val ITERATIONS = 600_000
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    const val MIN_PASSPHRASE_LENGTH = 8

    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size + SALT_BYTES + IV_BYTES && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun encrypt(plain: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyFor(passphrase, salt)) }
        return MAGIC + salt + cipher.iv + cipher.doFinal(plain)
    }

    /**
     * Wraps [out] so what is written to it is encrypted; close the result to finish.
     * Lets a large backup (with receipt photos) be written without a second copy in memory.
     */
    fun encryptingStream(out: OutputStream, passphrase: String): OutputStream {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keyFor(passphrase, salt)) }
        out.write(MAGIC + salt + cipher.iv)
        return CipherOutputStream(out, cipher)
    }

    /** @throws WrongBackupPassphraseException if [passphrase] doesn't open [stored] (or it was altered). */
    fun decrypt(stored: ByteArray, passphrase: String): ByteArray {
        val salt = stored.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val ivStart = MAGIC.size + SALT_BYTES
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keyFor(passphrase, salt), GCMParameterSpec(TAG_BITS, stored, ivStart, IV_BYTES))
        }
        return try {
            cipher.doFinal(stored, ivStart + IV_BYTES, stored.size - ivStart - IV_BYTES)
        } catch (e: GeneralSecurityException) {
            throw WrongBackupPassphraseException()
        }
    }

    private fun keyFor(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }
}
