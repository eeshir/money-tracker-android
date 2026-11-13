package com.moneytracker.app.data.backup

import com.moneytracker.app.data.database.entity.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * Backup format models.
 *
 * ## Compatibility contract (see docs/backup-format.md)
 *
 * Serialized with [backupJson] (kotlinx.serialization), which is configured to
 * be tolerant in both directions:
 *  - **Backward** (old backup → new app): every field here has a Kotlin
 *    default, so a key missing from an older backup falls back to that default
 *    instead of crashing. THIS is the property that fixes the "can't restore an
 *    old backup" bug — never remove a default from a field that ships in a
 *    release.
 *  - **Forward** (new backup → old app): `ignoreUnknownKeys = true` means a
 *    field added in a newer version is silently ignored by an older app.
 *
 * Invariant for maintainers: **every property in this file, and every backup-
 * serialized entity, must have a default value.** A `BackupSchemaGuardTest`
 * enforces this in CI.
 */
@Serializable
data class PennyWiseBackup(
    @SerialName("_format")
    val format: String = CURRENT_FORMAT,

    @SerialName("_warning")
    val warning: String = "Contains sensitive financial data. Keep this file secure.",

    @SerialName("_created")
    val created: String = LocalDateTime.now().toString(),

    @SerialName("metadata")
    val metadata: BackupMetadata = BackupMetadata(),

    @SerialName("database")
    val database: DatabaseSnapshot = DatabaseSnapshot(),

    @SerialName("preferences")
    val preferences: PreferencesSnapshot = PreferencesSnapshot(),

    // Receipt photos (#839), embedded so the backup stays one JSON file that
    // every app version can read — older apps just ignore this key, and
    // backups written before it existed decode to an empty list.
    @SerialName("receipts")
    val receipts: List<BackupReceipt> = emptyList(),

    // Saved statement PDFs (still locked by the bank's password), the card reward
    // setup, and webhook setup. Each is optional in the backup (see [BackupOption]);
    // backups written before they existed decode to empty ones.
    @SerialName("statements")
    val statements: List<BackupStatement> = emptyList(),

    @SerialName("rewards")
    val rewards: BackupRewards = BackupRewards(),

    @SerialName("webhooks")
    val webhooks: BackupWebhooks = BackupWebhooks()
) {
    companion object {
        /** Current format string written by this app version. */
        const val CURRENT_FORMAT = "PennyWise Backup v1.4"

        /** Prefix accepted on import — any `v1.x` backup is compatible. */
        const val COMPATIBLE_PREFIX = "PennyWise Backup v1"
    }
}

/**
 * One receipt photo. [path] is the transaction's `receiptPath` (relative to
 * `filesDir`, e.g. `receipts/receipt_123.jpg`); [dataBase64] is the file's
 * bytes, standard Base64. Untrusted on import — see [BackupReceipts].
 */
@Serializable
data class BackupReceipt(
    @SerialName("path")
    val path: String = "",

    @SerialName("data_base64")
    val dataBase64: String = ""
)

/**
 * Metadata about the backup. Informational only — never block an import on it,
 * so every field is defaulted and a malformed/absent metadata block still lets
 * the database restore.
 */
@Serializable
data class BackupMetadata(
    @SerialName("export_id")
    val exportId: String = "",

    @SerialName("app_version")
    val appVersion: String = "",

    @SerialName("database_version")
    val databaseVersion: Int = 0,

    @SerialName("device")
    val device: String = "",

    @SerialName("android_version")
    val androidVersion: Int = 0,

    @SerialName("statistics")
    val statistics: BackupStatistics = BackupStatistics()
)

/**
 * Statistics about the backup content.
 */
@Serializable
data class BackupStatistics(
    @SerialName("total_transactions")
    val totalTransactions: Int = 0,

    @SerialName("total_categories")
    val totalCategories: Int = 0,

    @SerialName("total_cards")
    val totalCards: Int = 0,

    @SerialName("total_subscriptions")
    val totalSubscriptions: Int = 0,

    @SerialName("total_rules")
    val totalRules: Int = 0,

    @SerialName("total_rule_applications")
    val totalRuleApplications: Int = 0,

    @SerialName("total_exchange_rates")
    val totalExchangeRates: Int = 0,

    @SerialName("total_budgets")
    val totalBudgets: Int = 0,

    @SerialName("total_budget_categories")
    val totalBudgetCategories: Int = 0,

    @SerialName("total_transaction_splits")
    val totalTransactionSplits: Int = 0,

    @SerialName("total_bank_notifications")
    val totalBankNotifications: Int = 0,

    @SerialName("total_loans")
    val totalLoans: Int = 0,

    @SerialName("total_transaction_groups")
    val totalTransactionGroups: Int = 0,

    @SerialName("total_profiles")
    val totalProfiles: Int = 0,

    @SerialName("total_budget_month_snapshots")
    val totalBudgetMonthSnapshots: Int = 0,

    @SerialName("total_budget_category_month_snapshots")
    val totalBudgetCategoryMonthSnapshots: Int = 0,

    @SerialName("total_merchant_aliases")
    val totalMerchantAliases: Int = 0,

    @SerialName("total_recurring_transactions")
    val totalRecurringTransactions: Int = 0,

    @SerialName("total_receipts")
    val totalReceipts: Int = 0,

    @SerialName("total_goals")
    val totalGoals: Int = 0,

    @SerialName("total_goal_contributions")
    val totalGoalContributions: Int = 0,

    @SerialName("total_loan_shares")
    val totalLoanShares: Int = 0,

    @SerialName("total_statements")
    val totalStatements: Int = 0,

    @SerialName("total_reward_programs")
    val totalRewardPrograms: Int = 0,

    @SerialName("total_webhook_profiles")
    val totalWebhookProfiles: Int = 0,

    @SerialName("date_range")
    val dateRange: DateRange? = null
)

/**
 * Date range of transactions.
 */
@Serializable
data class DateRange(
    @SerialName("earliest")
    val earliest: String? = null,

    @SerialName("latest")
    val latest: String? = null
)

/**
 * Complete database snapshot.
 *
 * Every list defaults to `emptyList()`, so a backup that omits a whole table
 * (older formats, or a newer table an older app never knew about) imports
 * cleanly with that table simply empty.
 */
@Serializable
data class DatabaseSnapshot(
    @SerialName("transactions")
    val transactions: List<TransactionEntity> = emptyList(),

    @SerialName("categories")
    val categories: List<CategoryEntity> = emptyList(),

    @SerialName("cards")
    val cards: List<CardEntity> = emptyList(),

    @SerialName("account_balances")
    val accountBalances: List<AccountBalanceEntity> = emptyList(),

    @SerialName("subscriptions")
    val subscriptions: List<SubscriptionEntity> = emptyList(),

    @SerialName("merchant_mappings")
    val merchantMappings: List<MerchantMappingEntity> = emptyList(),

    @SerialName("merchant_aliases")
    val merchantAliases: List<MerchantAliasEntity> = emptyList(),

    @SerialName("unrecognized_sms")
    val unrecognizedSms: List<UnrecognizedSmsEntity> = emptyList(),

    @SerialName("chat_messages")
    val chatMessages: List<ChatMessage> = emptyList(),

    @SerialName("rules")
    val rules: List<RuleEntity> = emptyList(),

    @SerialName("rule_applications")
    val ruleApplications: List<RuleApplicationEntity> = emptyList(),

    @SerialName("exchange_rates")
    val exchangeRates: List<ExchangeRateEntity> = emptyList(),

    @SerialName("budgets")
    val budgets: List<BudgetEntity> = emptyList(),

    @SerialName("budget_categories")
    val budgetCategories: List<BudgetCategoryEntity> = emptyList(),

    @SerialName("transaction_splits")
    val transactionSplits: List<TransactionSplitEntity> = emptyList(),

    @SerialName("bank_notifications")
    val bankNotifications: List<BankNotificationEntity> = emptyList(),

    @SerialName("loans")
    val loans: List<LoanEntity> = emptyList(),

    @SerialName("transaction_groups")
    val transactionGroups: List<TransactionGroupEntity> = emptyList(),

    @SerialName("profiles")
    val profiles: List<ProfileEntity> = emptyList(),

    @SerialName("budget_month_snapshots")
    val budgetMonthSnapshots: List<BudgetMonthSnapshotEntity> = emptyList(),

    @SerialName("budget_category_month_snapshots")
    val budgetCategoryMonthSnapshots: List<BudgetCategoryMonthSnapshotEntity> = emptyList(),

    @SerialName("tags")
    val tags: List<TagEntity> = emptyList(),

    @SerialName("transaction_tag_cross_refs")
    val transactionTagCrossRefs: List<TransactionTagCrossRef> = emptyList(),

    @SerialName("recurring_transactions")
    val recurringTransactions: List<RecurringTransactionEntity> = emptyList(),

    // Savings goals (#914). Contributions reference goals by id and,
    // optionally, transactions by id; the importer remaps both.
    @SerialName("goals")
    val goals: List<GoalEntity> = emptyList(),

    @SerialName("goal_contributions")
    val goalContributions: List<GoalContributionEntity> = emptyList(),

    // Split-bill shares (#346): each points at a loan and a transaction by id;
    // the importer remaps both.
    @SerialName("loan_shares")
    val loanShares: List<LoanShareEntity> = emptyList(),

    // Refund links: which expense each part of a refund pays back. Both ids are remapped on restore.
    @SerialName("refund_allocations")
    val refundAllocations: List<com.moneytracker.app.data.database.entity.RefundAllocationEntity> = emptyList()
)

/**
 * User preferences snapshot. Every section is defaulted so a backup missing a
 * whole section (or written by an app that didn't have it yet) still imports.
 */
@Serializable
data class PreferencesSnapshot(
    @SerialName("theme")
    val theme: ThemePreferences = ThemePreferences(),

    @SerialName("sms")
    val sms: SmsPreferences = SmsPreferences(),

    @SerialName("developer")
    val developer: DeveloperPreferences = DeveloperPreferences(),

    @SerialName("app")
    val app: AppPreferences = AppPreferences()
)

@Serializable
data class ThemePreferences(
    @SerialName("is_dark_theme_enabled")
    val isDarkThemeEnabled: Boolean? = null,

    @SerialName("is_dynamic_color_enabled")
    val isDynamicColorEnabled: Boolean = false,

    // Routes of hidden bottom-bar tabs (#912). Null in older backups, so a
    // restore of one leaves the current choice alone.
    @SerialName("hidden_nav_tabs")
    val hiddenNavTabs: List<String>? = null
)

@Serializable
data class SmsPreferences(
    @SerialName("has_skipped_sms_permission")
    val hasSkippedSmsPermission: Boolean = false,

    @SerialName("sms_scan_months")
    val smsScanMonths: Int = 6,

    @SerialName("last_scan_timestamp")
    val lastScanTimestamp: Long? = null,

    @SerialName("last_scan_period")
    val lastScanPeriod: Int? = null,

    @SerialName("sms_scan_use_custom_date")
    val smsScanUseCustomDate: Boolean = false,

    @SerialName("sms_scan_custom_date")
    val smsScanCustomDate: Long? = null
)

@Serializable
data class DeveloperPreferences(
    @SerialName("is_developer_mode_enabled")
    val isDeveloperModeEnabled: Boolean = false,

    @SerialName("system_prompt")
    val systemPrompt: String? = null
)

@Serializable
data class AppPreferences(
    @SerialName("has_shown_scan_tutorial")
    val hasShownScanTutorial: Boolean = false,

    @SerialName("first_launch_time")
    val firstLaunchTime: Long? = null,

    @SerialName("has_shown_review_prompt")
    val hasShownReviewPrompt: Boolean = false,

    @SerialName("last_review_prompt_time")
    val lastReviewPromptTime: Long? = null,

    // Website-sold Pro license (Dodo). Travels with the backup so a restore on
    // a new phone keeps Pro without re-entering the key or hitting the
    // one-device activation limit. Both optional: pre-license backups omit them.
    @SerialName("license_key")
    val licenseKey: String? = null,

    @SerialName("license_instance_id")
    val licenseInstanceId: String? = null,

    // Accounts the user chose to ignore (#826), as "bankName_last4" keys.
    // Without this a restore silently starts tracking a relative's account
    // again. Defaulted, so pre-#826 backups still import.
    @SerialName("ignored_accounts")
    val ignoredAccounts: List<String> = emptyList(),

    @SerialName("bank_account_merges")
    val bankAccountMerges: Map<String, String> = emptyMap(),

    // The user's own UPI ID for split-bill requests (#346). FULL exports only.
    @SerialName("upi_id")
    val upiId: String? = null,

    // Own-account payee rules (a card-bill service or another of your accounts), so a
    // restore keeps transfers between your accounts out of spending. FULL exports only.
    @SerialName("own_payee_rules")
    val ownPayeeRules: List<com.moneytracker.app.data.payees.OwnPayeeRule> = emptyList(),

    // Statement PDF passwords by bank and card, in plain text: only ever written inside an
    // encrypted backup, so a restore doesn't mean typing them all again. FULL exports only.
    @SerialName("statement_passwords")
    val statementPasswords: Map<String, String> = emptyMap(),

    // People recently used in a split (names only), so they stay suggestions. FULL exports only.
    @SerialName("recent_split_contacts")
    val recentSplitContacts: List<String> = emptyList()
)

/**
 * One saved statement PDF, as the bank sent it (still locked by the bank's own password),
 * plus what the app learned about it. [dataBase64] is the plain PDF: the backup itself is
 * encrypted, and the app re-encrypts it with this phone's key on restore.
 */
@Serializable
data class BackupStatement(
    @SerialName("id") val id: String = "",
    @SerialName("label") val label: String = "",
    @SerialName("bank_name") val bankName: String = "",
    @SerialName("card_last4") val cardLast4: String? = null,
    @SerialName("saved_at") val savedAt: Long = 0L,
    @SerialName("gmail_key") val gmailKey: String? = null,
    @SerialName("email_date") val emailDate: Long = 0L,
    @SerialName("account_key") val accountKey: String? = null,
    @SerialName("statement_day") val statementDay: Long = 0L,
    @SerialName("imported_at") val importedAt: Long = 0L,
    @SerialName("applied_at") val appliedAt: Long = 0L,
    @SerialName("group_key") val groupKey: String? = null,
    @SerialName("unsupported") val unsupported: Boolean = false,
    @SerialName("data_base64") val dataBase64: String = ""
)

/** Card reward setup: per-card programs, per-purchase rate overrides, and the screen the person prefers. */
@Serializable
data class BackupRewards(
    @SerialName("programs")
    val programs: List<com.moneytracker.app.data.rewards.CardRewardProgram> = emptyList(),

    @SerialName("overrides")
    val overrides: List<BackupRewardOverride> = emptyList(),

    // Null in backups that did not carry it, so a restore leaves the current choice alone.
    @SerialName("advanced_screen")
    val advancedScreen: Boolean? = null
)

/** A rate the person set on one purchase. [transactionId] is remapped on restore. */
@Serializable
data class BackupRewardOverride(
    @SerialName("transaction_id") val transactionId: Long = 0L,
    @SerialName("rate_percent") val ratePercent: String = ""
)

/** Webhook setup and delivery state (so a restore does not send everything again). */
@Serializable
data class BackupWebhooks(
    @SerialName("profiles") val profiles: List<BackupWebhookProfile> = emptyList(),
    @SerialName("logs") val logs: List<BackupWebhookLog> = emptyList(),
    @SerialName("cursors") val cursors: List<BackupWebhookCursor> = emptyList(),
    @SerialName("delivered") val delivered: List<BackupWebhookDelivered> = emptyList()
)

@Serializable
data class BackupWebhookProfile(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("url") val url: String = "",
    @SerialName("enabled") val enabled: Boolean = true,
    @SerialName("range_preset") val rangePreset: String = "SINCE_LAST_SUCCESS",
    @SerialName("custom_start") val customStart: String? = null,
    @SerialName("custom_end") val customEnd: String? = null,
    @SerialName("data_types") val dataTypes: String = "SUMMARY,TRANSACTIONS,BUDGETS,ACCOUNTS,SUBSCRIPTIONS",
    // Can hold an auth token: only ever written inside an encrypted backup.
    @SerialName("headers_json") val headersJson: String = "[]",
    @SerialName("currency") val currency: String = "INR",
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("consecutive_failures") val consecutiveFailures: Int = 0,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class BackupWebhookLog(
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("profile_name") val profileName: String = "",
    @SerialName("sync_reason") val syncReason: String = "MANUAL",
    @SerialName("status") val status: String = "SUCCESS",
    @SerialName("message") val message: String = "",
    @SerialName("http_status") val httpStatus: Int? = null,
    @SerialName("batch_count") val batchCount: Int = 0,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class BackupWebhookCursor(
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("data_type") val dataType: String = "",
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
    @SerialName("last_range_end") val lastRangeEnd: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

/** A transaction already sent to a webhook. [transactionId] is remapped on restore. */
@Serializable
data class BackupWebhookDelivered(
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("transaction_id") val transactionId: Long = 0L
)

/**
 * Import result. [skippedRows] counts entity rows that failed to insert and
 * were skipped so a single bad row never aborts the whole restore.
 */
sealed class ImportResult {
    data class Success(
        val importedTransactions: Int,
        val importedCategories: Int,
        val skippedDuplicates: Int,
        val skippedRows: Int = 0
    ) : ImportResult()

    data class Error(val message: String) : ImportResult()

    /** The backup is encrypted: ask for its passphrase ([wrong]: the one tried didn't open it). */
    data class NeedsPassphrase(val wrong: Boolean) : ImportResult()
}

/**
 * Export result.
 */
sealed class ExportResult {
    data class Success(val file: java.io.File) : ExportResult()
    data class Error(val message: String) : ExportResult()
    data class Progress(val current: Int, val total: Int) : ExportResult()
}

/**
 * In-memory export result for scheduled and manual folder backups.
 */
sealed class ExportBytesResult {
    // Plain class, not data class: ByteArray has reference equality, so a generated
    // equals/hashCode over it would be misleading. This result is only matched via `when`.
    class Success(val bytes: ByteArray) : ExportBytesResult()
    data class Error(val message: String) : ExportBytesResult()
}

/**
 * Import strategy options.
 */
enum class ImportStrategy {
    REPLACE_ALL,    // Replace all existing data
    MERGE,          // Merge with existing data (skip duplicates)
    SELECTIVE       // User selects what to import
}

/**
 * Privacy level for export.
 */
enum class ExportPrivacy {
    FULL,          // Export everything as-is
    MASKED,        // Mask sensitive data like account numbers
    ANONYMOUS      // Remove merchant names and descriptions
}
