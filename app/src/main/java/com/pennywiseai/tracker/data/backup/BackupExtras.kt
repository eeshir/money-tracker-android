package com.pennywiseai.tracker.data.backup

import android.util.Base64
import android.util.Log
import com.pennywiseai.tracker.data.database.PennyWiseDatabase
import com.pennywiseai.tracker.data.database.entity.WebhookCursorEntity
import com.pennywiseai.tracker.data.database.entity.WebhookDataType
import com.pennywiseai.tracker.data.database.entity.WebhookDeliveredTransactionEntity
import com.pennywiseai.tracker.data.database.entity.WebhookLogEntity
import com.pennywiseai.tracker.data.database.entity.WebhookLogStatus
import com.pennywiseai.tracker.data.database.entity.WebhookProfileEntity
import com.pennywiseai.tracker.data.database.entity.WebhookRangePreset
import com.pennywiseai.tracker.data.preferences.RecentSplitContacts
import com.pennywiseai.tracker.data.rewards.CardRewardStore
import com.pennywiseai.tracker.data.rewards.RewardOverrideStore
import com.pennywiseai.tracker.data.rewards.RewardsUiPrefs
import com.pennywiseai.tracker.data.statement.SavedStatementStore
import com.pennywiseai.tracker.data.statement.StatementFileCipher
import com.pennywiseai.tracker.data.webhook.WebhookSyncReason
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The optional parts of a backup that live outside the main tables: saved statement PDFs, card
 * reward setup, webhooks and the split-contact suggestions. Kept apart from [BackupExporter] /
 * [BackupImporter], which only decide *whether* each part goes in or comes out.
 *
 * Transaction ids in a backup are the ones of the phone it came from, so anything that points at
 * a transaction (a rate override, a "sent to the webhook" record) goes through the id map the
 * importer built, and is dropped when its transaction did not come across.
 */
@Singleton
class BackupExtras @Inject constructor(
    private val database: PennyWiseDatabase,
    private val savedStatementStore: SavedStatementStore,
    private val statementCipher: StatementFileCipher,
    private val cardRewardStore: CardRewardStore,
    private val rewardOverrideStore: RewardOverrideStore,
    private val rewardsUiPrefs: RewardsUiPrefs,
    private val recentSplitContacts: RecentSplitContacts
) {

    // ---- Export ----

    /** Every saved statement with its PDF (decrypted from this phone's key; the backup is encrypted instead). */
    fun collectStatements(): List<BackupStatement> =
        savedStatementStore.list().mapNotNull { s ->
            runCatching {
                val pdf = statementCipher.decrypt(savedStatementStore.file(s.id).readBytes())
                BackupStatement(
                    id = s.id, label = s.label, bankName = s.bankName, cardLast4 = s.cardLast4,
                    savedAt = s.savedAt, gmailKey = s.gmailKey, emailDate = s.emailDate,
                    accountKey = s.accountKey, statementDay = s.statementDay, importedAt = s.importedAt,
                    appliedAt = s.appliedAt, groupKey = s.groupKey, unsupported = s.unsupported,
                    dataBase64 = Base64.encodeToString(pdf, Base64.NO_WRAP)
                )
            }.onFailure { Log.w(TAG, "Statement ${s.id} left out of the backup: ${it.message}") }.getOrNull()
        }

    /** Reward programs, the rates set on single purchases (only those in [transactionIds]) and the screen choice. */
    fun collectRewards(transactionIds: Set<Long>): BackupRewards = BackupRewards(
        programs = cardRewardStore.all(),
        overrides = rewardOverrideStore.rates.value
            .filterKeys { it in transactionIds }
            .map { (id, rate) -> BackupRewardOverride(transactionId = id, ratePercent = rate) },
        advancedScreen = rewardsUiPrefs.advanced.value
    )

    fun collectRecentSplitContacts(): List<String> = recentSplitContacts.names.value

    /** Webhook profiles with their logs, cursors and "already sent" records (only for transactions in [transactionIds]). */
    suspend fun collectWebhooks(transactionIds: Set<Long>): BackupWebhooks = BackupWebhooks(
        profiles = database.webhookProfileDao().getAll().map {
            BackupWebhookProfile(
                id = it.id, name = it.name, url = it.url, enabled = it.enabled,
                rangePreset = it.rangePreset.name, customStart = it.customStart?.toString(),
                customEnd = it.customEnd?.toString(), dataTypes = it.dataTypes, headersJson = it.headersJson,
                currency = it.currency, lastError = it.lastError, consecutiveFailures = it.consecutiveFailures,
                lastSyncedAt = it.lastSyncedAt?.toString(), createdAt = it.createdAt.toString(),
                updatedAt = it.updatedAt.toString()
            )
        },
        logs = database.webhookLogDao().getAll().map {
            BackupWebhookLog(
                profileId = it.profileId, profileName = it.profileName, syncReason = it.syncReason.name,
                status = it.status.name, message = it.message, httpStatus = it.httpStatus,
                batchCount = it.batchCount, createdAt = it.createdAt.toString()
            )
        },
        cursors = database.webhookCursorDao().getAll().map {
            BackupWebhookCursor(
                profileId = it.profileId, dataType = it.dataType.name,
                lastSuccessAt = it.lastSuccessAt?.toString(), lastRangeEnd = it.lastRangeEnd?.toString(),
                updatedAt = it.updatedAt.toString()
            )
        },
        delivered = database.webhookDeliveredTransactionDao().getAll()
            .filter { it.transactionId in transactionIds }
            .map { BackupWebhookDelivered(profileId = it.profileId, transactionId = it.transactionId) }
    )

    // ---- Import ----

    /**
     * Puts saved statements back, encrypted with this phone's key. One already saved here (same
     * PDF) is left as it is. Returns how many were restored; a bad entry is skipped and counted
     * through [onSkip].
     */
    fun restoreStatements(statements: List<BackupStatement>, onSkip: () -> Unit = {}): Int {
        if (statements.isEmpty()) return 0
        val have = savedStatementStore.list().mapTo(HashSet()) { it.id }
        var restored = 0
        for (s in statements) {
            try {
                if (s.id in have) continue
                val pdf = Base64.decode(s.dataBase64, Base64.DEFAULT)
                require(pdf.isNotEmpty()) { "Statement ${s.id} has no data" }
                val saved = savedStatementStore.save(
                    pdf = pdf, bankName = s.bankName, cardLast4 = s.cardLast4, label = s.label,
                    gmailKey = s.gmailKey, emailDate = s.emailDate, accountKey = s.accountKey,
                    statementDay = s.statementDay, groupKey = s.groupKey
                )
                // Keep what the app had learned, e.g. that it was already imported (so it is not imported again).
                savedStatementStore.update(saved.id) {
                    it.copy(
                        savedAt = s.savedAt.takeIf { at -> at > 0 } ?: it.savedAt,
                        importedAt = s.importedAt, appliedAt = s.appliedAt, unsupported = s.unsupported
                    )
                }
                restored++
            } catch (e: Exception) {
                Log.w(TAG, "Skipped a statement: ${e.message}")
                onSkip()
            }
        }
        return restored
    }

    /** Restores reward programs (additive), rate overrides (ids remapped) and the screen choice. */
    fun restoreRewards(rewards: BackupRewards, transactionIdMap: Map<Long, Long>) {
        cardRewardStore.restore(rewards.programs)
        rewardOverrideStore.restore(
            rewards.overrides.mapNotNull { o -> transactionIdMap[o.transactionId]?.let { it to o.ratePercent } }.toMap()
        )
        rewards.advancedScreen?.let { rewardsUiPrefs.setAdvanced(it) }
    }

    fun restoreRecentSplitContacts(names: List<String>) {
        if (names.isNotEmpty()) recentSplitContacts.remember(names.reversed())
    }

    /**
     * Restores webhooks. With [replace] the webhooks on this phone are removed first (a full restore);
     * otherwise a profile already here (same id) is left alone, along with its state, so a repeated
     * merge adds nothing twice. Logs, cursors and "already sent" records are restored only for the
     * profiles added now. Must run inside the importer's transaction.
     */
    suspend fun restoreWebhooks(
        webhooks: BackupWebhooks,
        transactionIdMap: Map<Long, Long>,
        replace: Boolean,
        onSkip: () -> Unit
    ) {
        if (webhooks.profiles.isEmpty()) return
        val profiles = database.webhookProfileDao()
        if (replace) profiles.deleteAll()
        val added = HashSet<String>()
        for (p in webhooks.profiles) {
            try {
                if (p.id.isBlank() || p.url.isBlank()) error("Webhook profile has no id or URL")
                if (profiles.byId(p.id) != null) continue
                profiles.upsert(
                    WebhookProfileEntity(
                        id = p.id, name = p.name, url = p.url, enabled = p.enabled,
                        rangePreset = WebhookRangePreset.valueOf(p.rangePreset),
                        customStart = p.customStart?.let(LocalDateTime::parse),
                        customEnd = p.customEnd?.let(LocalDateTime::parse),
                        dataTypes = p.dataTypes, headersJson = p.headersJson, currency = p.currency,
                        lastError = p.lastError, consecutiveFailures = p.consecutiveFailures,
                        lastSyncedAt = p.lastSyncedAt?.let(LocalDateTime::parse),
                        createdAt = p.createdAt?.let(LocalDateTime::parse) ?: LocalDateTime.now(),
                        updatedAt = p.updatedAt?.let(LocalDateTime::parse) ?: LocalDateTime.now()
                    )
                )
                added += p.id
            } catch (e: Exception) {
                Log.w(TAG, "Skipped a webhook profile: ${e.message}")
                onSkip()
            }
        }
        for (c in webhooks.cursors.filter { it.profileId in added }) {
            try {
                database.webhookCursorDao().upsert(
                    WebhookCursorEntity(
                        profileId = c.profileId, dataType = WebhookDataType.valueOf(c.dataType),
                        lastSuccessAt = c.lastSuccessAt?.let(LocalDateTime::parse),
                        lastRangeEnd = c.lastRangeEnd?.let(LocalDateTime::parse),
                        updatedAt = c.updatedAt?.let(LocalDateTime::parse) ?: LocalDateTime.now()
                    )
                )
            } catch (e: Exception) { Log.w(TAG, "Skipped a webhook cursor: ${e.message}"); onSkip() }
        }
        for (d in webhooks.delivered.filter { it.profileId in added }) {
            try {
                val id = transactionIdMap[d.transactionId] ?: continue
                database.webhookDeliveredTransactionDao().insert(WebhookDeliveredTransactionEntity(d.profileId, id))
            } catch (e: Exception) { Log.w(TAG, "Skipped a webhook delivery record: ${e.message}"); onSkip() }
        }
        for (l in webhooks.logs.filter { it.profileId in added }) {
            try {
                database.webhookLogDao().insert(
                    WebhookLogEntity(
                        profileId = l.profileId, profileName = l.profileName,
                        syncReason = WebhookSyncReason.valueOf(l.syncReason),
                        status = WebhookLogStatus.valueOf(l.status), message = l.message,
                        httpStatus = l.httpStatus, batchCount = l.batchCount,
                        createdAt = l.createdAt?.let(LocalDateTime::parse) ?: LocalDateTime.now()
                    )
                )
            } catch (e: Exception) { Log.w(TAG, "Skipped a webhook log: ${e.message}"); onSkip() }
        }
    }

    private companion object { const val TAG = "BackupExtras" }
}
