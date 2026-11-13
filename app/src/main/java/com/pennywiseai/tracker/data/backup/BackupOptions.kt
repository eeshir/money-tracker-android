package com.pennywiseai.tracker.data.backup

/**
 * The optional parts of a backup the person can switch off in Settings. Everything else
 * (transactions, accounts, budgets, ...) is always included. Only a FULL export can carry them.
 *
 * [key] names the saved preference (`export_include_<key>` / `folder_backup_include_<key>`),
 * so [RECEIPTS] keeps the setting it has always had.
 */
enum class BackupOption(val key: String, val defaultOnManual: Boolean, val defaultOnAutomatic: Boolean) {
    /** Photos attached to transactions. Makes the file larger. */
    RECEIPTS("receipts", true, false),

    /** Fetched or imported statement PDFs, so they are not fetched or imported twice. Larger file. */
    STATEMENTS("statements", true, false),

    /** Card reward programs, per-purchase rate overrides and the rewards screen choice. */
    REWARDS("rewards", true, true),

    /** Webhook profiles (URLs and headers), their delivery state and logs. */
    WEBHOOKS("webhooks", true, true);

    companion object {
        /** Everything: what a plain `exportBackup()` call includes. */
        val ALL: Set<BackupOption> = entries.toSet()
    }
}

/** Which backup a set of [BackupOption]s belongs to: the one made by hand, or the automatic folder backup. */
enum class BackupScope(val prefix: String) {
    MANUAL("export"),
    AUTOMATIC("folder_backup");

    fun default(option: BackupOption): Boolean = when (this) {
        MANUAL -> option.defaultOnManual
        AUTOMATIC -> option.defaultOnAutomatic
    }
}
