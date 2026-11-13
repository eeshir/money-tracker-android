package com.moneytracker.app.data.backup

import com.moneytracker.app.data.rewards.CardRewardProgram
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The optional backup parts: statements, card rewards, webhooks (format v1.4). */
class BackupExtrasFormatTest {

    @Test
    fun extras_roundTrip() {
        val backup = PennyWiseBackup(
            statements = listOf(BackupStatement(id = "abc", bankName = "Bank", cardLast4 = "1234", importedAt = 5L, dataBase64 = "JVBERg==")),
            rewards = BackupRewards(
                programs = listOf(CardRewardProgram(bankName = "Bank", cardLast4 = "1234", baseRatePercent = "1.5")),
                overrides = listOf(BackupRewardOverride(transactionId = 7, ratePercent = "2")),
                advancedScreen = true
            ),
            webhooks = BackupWebhooks(
                profiles = listOf(BackupWebhookProfile(id = "p1", name = "Sheet", url = "https://example.invalid/hook")),
                delivered = listOf(BackupWebhookDelivered(profileId = "p1", transactionId = 7))
            ),
            preferences = PreferencesSnapshot(app = AppPreferences(recentSplitContacts = listOf("A", "B")))
        )

        val decoded = backupJson.decodeFromString<PennyWiseBackup>(backupJson.encodeToString(backup))

        assertEquals(backup.statements, decoded.statements)
        assertEquals(backup.rewards, decoded.rewards)
        assertEquals(backup.webhooks, decoded.webhooks)
        assertEquals(listOf("A", "B"), decoded.preferences.app.recentSplitContacts)
    }

    @Test
    fun olderBackupWithoutExtras_decodesToEmptyOnes() {
        val old = """{"_format":"PennyWise Backup v1.3","database":{},"preferences":{"app":{}}}"""

        val decoded = backupJson.decodeFromString<PennyWiseBackup>(old)

        assertTrue(decoded.statements.isEmpty())
        assertTrue(decoded.rewards.programs.isEmpty())
        assertTrue(decoded.rewards.overrides.isEmpty())
        assertNull(decoded.rewards.advancedScreen)
        assertTrue(decoded.webhooks.profiles.isEmpty())
        assertTrue(decoded.preferences.app.recentSplitContacts.isEmpty())
    }

    @Test
    fun webhookProfileMissingNewerKeys_takesDefaults() {
        val json = """{"webhooks":{"profiles":[{"id":"p1","url":"https://example.invalid/h"}]}}"""

        val profile = backupJson.decodeFromString<PennyWiseBackup>(json).webhooks.profiles.single()

        assertTrue(profile.enabled)
        assertEquals("SINCE_LAST_SUCCESS", profile.rangePreset)
        assertEquals("[]", profile.headersJson)
    }

    @Test
    fun options_defaults_matchWhatEachBackupHasAlwaysDone() {
        // Receipts: on for a backup made by hand, off for the daily one (as before).
        assertTrue(BackupScope.MANUAL.default(BackupOption.RECEIPTS))
        assertTrue(!BackupScope.AUTOMATIC.default(BackupOption.RECEIPTS))
        assertEquals("export_include_receipts", "${BackupScope.MANUAL.prefix}_include_${BackupOption.RECEIPTS.key}")
        assertEquals("folder_backup_include_receipts", "${BackupScope.AUTOMATIC.prefix}_include_${BackupOption.RECEIPTS.key}")
    }
}
