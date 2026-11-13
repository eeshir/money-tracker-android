package com.moneytracker.app.data.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class BackupCryptoTest {

    private val plain = """{"_format":"PennyWise Backup v1.3","database":{}}""".toByteArray()

    @Test
    fun `a backup opens with the passphrase it was made with`() {
        val stored = BackupCrypto.encrypt(plain, "correct horse battery")
        assertTrue(BackupCrypto.isEncrypted(stored))
        assertArrayEquals(plain, BackupCrypto.decrypt(stored, "correct horse battery"))
    }

    @Test
    fun `the stored bytes do not contain the backup`() {
        val stored = BackupCrypto.encrypt(plain, "correct horse battery")
        assertFalse(String(stored, Charsets.ISO_8859_1).contains("PennyWise Backup"))
    }

    @Test
    fun `a wrong passphrase is refused`() {
        val stored = BackupCrypto.encrypt(plain, "correct horse battery")
        try {
            BackupCrypto.decrypt(stored, "wrong passphrase")
            fail("expected a wrong-passphrase error")
        } catch (_: WrongBackupPassphraseException) {
        }
    }

    @Test
    fun `an altered file is refused rather than read`() {
        val stored = BackupCrypto.encrypt(plain, "correct horse battery")
        stored[stored.size - 3] = (stored[stored.size - 3].toInt() xor 1).toByte()
        try {
            BackupCrypto.decrypt(stored, "correct horse battery")
            fail("expected the tampered file to be refused")
        } catch (_: WrongBackupPassphraseException) {
        }
    }

    @Test
    fun `two backups of the same data differ`() {
        val a = BackupCrypto.encrypt(plain, "correct horse battery")
        val b = BackupCrypto.encrypt(plain, "correct horse battery")
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `the streaming writer produces a file the reader opens`() {
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptingStream(out, "correct horse battery").use { it.write(plain) }
        assertEquals(true, BackupCrypto.isEncrypted(out.toByteArray()))
        assertArrayEquals(plain, BackupCrypto.decrypt(out.toByteArray(), "correct horse battery"))
    }

    @Test
    fun `a plain old backup is not mistaken for an encrypted one`() {
        assertFalse(BackupCrypto.isEncrypted(plain))
    }
}
