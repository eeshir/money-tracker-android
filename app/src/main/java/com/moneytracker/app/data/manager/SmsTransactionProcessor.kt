package com.moneytracker.app.data.manager

import com.moneytracker.app.data.preferences.BankAccountMergeStore

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.moneytracker.parser.core.ParsedTransaction
import com.moneytracker.parser.core.bank.BankParserFactory
import com.moneytracker.app.data.database.PennyWiseDatabase
import com.moneytracker.app.data.database.entity.AccountBalanceEntity
import com.moneytracker.app.data.database.entity.ProfileEntity
import com.moneytracker.app.data.database.entity.CardType
import com.moneytracker.app.data.database.entity.TransactionEntity
import com.moneytracker.app.data.database.entity.TransactionType
import com.moneytracker.app.data.mapper.toEntity
import com.moneytracker.app.data.mapper.toEntityType
import com.moneytracker.app.utils.BalanceCalculator
import com.moneytracker.app.data.repository.AccountBalanceRepository
import com.moneytracker.app.data.repository.CardRepository
import com.moneytracker.app.data.preferences.IgnoredAccountsStore
import com.moneytracker.app.data.repository.MerchantMappingRepository
import com.moneytracker.app.data.repository.mappingFits
import com.moneytracker.app.data.repository.SubscriptionRepository
import com.moneytracker.app.data.repository.TagRepository
import com.moneytracker.app.data.repository.TransactionRepository
import com.moneytracker.app.domain.model.rule.tagChanges
import com.moneytracker.app.domain.repository.RuleRepository
import com.moneytracker.app.domain.service.RuleEngine
import java.math.BigDecimal
import java.time.Instant
import com.moneytracker.app.receiver.BankNotificationConfig
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.ZoneId
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared processor for SMS transactions. Used by both SmsBroadcastReceiver
 * and OptimizedSmsReaderWorker to ensure consistent transaction processing.
 */
@Singleton
class SmsTransactionProcessor @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val transactionRepository: TransactionRepository,
    private val accountBalanceRepository: AccountBalanceRepository,
    private val cardRepository: CardRepository,
    private val merchantMappingRepository: MerchantMappingRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val ruleRepository: RuleRepository,
    private val ruleEngine: RuleEngine,
    private val tagRepository: TagRepository,
    private val ignoredAccountsStore: IgnoredAccountsStore,
    private val database: PennyWiseDatabase,
    private val ownPayeeMirror: com.moneytracker.app.data.payees.OwnPayeeMirror
) {
    private val bankAccountMerges = BankAccountMergeStore(appContext)
    companion object {
        private const val TAG = "SmsTransactionProcessor"
    }

    /**
     * Result of processing an SMS message
     */
    data class ProcessingResult(
        val success: Boolean,
        val transactionId: Long? = null,
        val reason: String? = null,
        /**
         * True when nothing was saved on purpose (the account is ignored), as
         * opposed to a parse or storage failure. Callers that queue a message
         * for retry should treat this as settled, not as something to try again.
         */
        val intentionallySkipped: Boolean = false
    )

    /**
     * Parses and saves a transaction from an SMS message.
     *
     * @param sender SMS sender address
     * @param body SMS body text
     * @param timestamp SMS timestamp in milliseconds
     * @return ProcessingResult indicating success/failure and transaction ID
     */
    suspend fun processAndSaveTransaction(
        sender: String,
        body: String,
        timestamp: Long
    ): ProcessingResult {
        try {
            // Some senders are shared by multiple parsers (e.g. M-Pesa
            // Kenya/Tanzania/Mozambique all use "M-Pesa"), so try every parser
            // that handles this sender and use the first whose content parses.
            val parsers = BankParserFactory.getParsers(sender)
            if (parsers.isEmpty()) {
                Log.d(TAG, "[SMS_INSPECT] Sender=$sender: No parser registered for this sender")
                return ProcessingResult(false, reason = "No parser found for sender: $sender")
            }

            // Parse the SMS
            val parsedTransaction = parsers.firstNotNullOfOrNull { it.parse(body, sender, timestamp) }
            if (parsedTransaction == null) {
                val nonTxnReason = parsers.firstNotNullOfOrNull { it.getNonTransactionReason(body) }
                Log.d(TAG, "[SMS_INSPECT] Sender=$sender: Skipped message${if (nonTxnReason != null) " (Reason: $nonTxnReason)" else ""}")
                return ProcessingResult(false, reason = "Could not parse transaction from SMS: ${nonTxnReason ?: "Unrecognized format"}")
            }

            Log.i(TAG, "[SMS_INSPECT] Parsed from $sender: Bank=${parsedTransaction.bankName}, Amount=${parsedTransaction.amount}, Type=${parsedTransaction.type}, Merchant=${parsedTransaction.merchant}, Acct=••••${parsedTransaction.accountLast4}, Bal=${parsedTransaction.balance}")

            // Save the transaction
            val result = saveParsedTransaction(parsedTransaction, body)
            // A payment to one of the person's own accounts: pair it with the credit on the
            // other side, or record it (a few days back is enough for a live SMS).
            if (result.success) runCatching { ownPayeeMirror.applyAll(sinceDays = 10) }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS", e)
            return ProcessingResult(false, reason = e.message)
        }
    }

    /**
     * Saves a parsed transaction to the database with all necessary processing:
     * - Duplicate detection
     * - Merchant mapping
     * - Rule application
     * - Subscription matching
     * - Balance updates
     */
    suspend fun saveParsedTransaction(
        parsedInput: ParsedTransaction,
        smsBody: String
    ): ProcessingResult = BankAccountMergeStore.mutationMutex.withLock {
        // One lock across the SMS receiver and the notification listener: a bank
        // that texts and pushes at the same moment would otherwise let both pass
        // the duplicate check before either insert lands.
        try {
            val parsedTransaction = withHeldAccount(bankAccountMerges.resolve(parsedInput))

            // Resolve debit card to underlying bank account if applicable
            var resolvedAccountLast4 = parsedTransaction.accountLast4
            val isFromCard = parsedTransaction.isFromCard
            val rawCardDigits = parsedTransaction.accountLast4
            if (isFromCard && rawCardDigits != null) {
                val isCredit = (parsedTransaction.type.toEntityType() == TransactionType.CREDIT)
                var card = cardRepository.findOrCreateCard(
                    cardLast4 = rawCardDigits,
                    bankName = parsedTransaction.bankName,
                    isCredit = isCredit
                )
                if (card.cardType == CardType.DEBIT) {
                    var linkedAcct = card.accountLast4
                    if (linkedAcct == null) {
                        linkedAcct = resolveActiveAccountForBank(card.bankName)
                        if (linkedAcct != null) {
                            cardRepository.linkCardToAccount(card.id, linkedAcct)
                            card = card.copy(accountLast4 = linkedAcct)
                            Log.i(TAG, "Auto-linked debit card ${card.cardLast4} (${card.bankName}) to bank account $linkedAcct")
                        }
                    }
                    if (linkedAcct != null) {
                        resolvedAccountLast4 = linkedAcct
                    }
                }
            }

            // Convert to entity, remapping debit card number to the actual parent bank account
            var entity = parsedTransaction.toEntity()
            if (resolvedAccountLast4 != null && resolvedAccountLast4 != entity.accountNumber) {
                entity = entity.copy(accountNumber = resolvedAccountLast4)
            }

            // An ignored account's messages are dropped before anything is
            // stored, so no row and no notification (#826). A debit-card
            // purchase reports the card's digits while the money leaves the
            // linked account, so check both — ignoring the account has to stop
            // its card spend too.
            val linkedAccountLast4 = if (parsedTransaction.isFromCard) {
                parsedTransaction.accountLast4?.let {
                    cardRepository.getCard(parsedTransaction.bankName, it)?.accountLast4
                } ?: resolvedAccountLast4
            } else null
            if (ignoredAccountsStore.isIgnored(
                    entity.bankName,
                    entity.accountNumber,
                    linkedAccountLast4
                )
            ) {
                return ProcessingResult(
                    success = false,
                    reason = "Account is ignored",
                    intentionallySkipped = true
                )
            }

            // Check if this transaction was previously deleted by the user
            val existingTransaction = transactionRepository.getTransactionByHash(entity.transactionHash)
            if (existingTransaction != null) {
                if (existingTransaction.isDeleted) {
                    Log.d(TAG, "Skipping previously deleted transaction with hash: ${entity.transactionHash}")
                    return ProcessingResult(false, reason = "Transaction was previously deleted")
                }
                // Transaction already exists and not deleted - normal deduplication
                Log.d(TAG, "Transaction already exists: ${entity.transactionHash}")
                return ProcessingResult(false, reason = "Duplicate transaction")
            }

            // Same SMS already stored under an older hash (a parser fix changed the
            // amount): never insert it twice, and repair a known-bad amount. (#886)
            entity.smsBody?.let { body ->
                transactionRepository.getAnyBySms(body, entity.smsSender)?.let { stored ->
                    if (!stored.isDeleted && TransactionDeduplication.isParseArtifact(stored, entity)) {
                        transactionRepository.correctParsedAmount(stored, entity)
                    }
                    return ProcessingResult(false, reason = "Duplicate transaction")
                }
            }

            // Same charge already booked from the bank's other channel (SMS vs app
            // notification). Runs under saveLock, together with the insert below.
            if (entity.bankName in BankNotificationConfig.notificationBankNames) {
                val nearby = transactionRepository.getTransactionByAmountAndDate(
                    entity.amount,
                    entity.dateTime.minusMinutes(2),
                    entity.dateTime.plusMinutes(2)
                )
                if (TransactionDeduplication.isBookedByOtherChannel(
                        entity, nearby, BankNotificationConfig.notificationAliases
                    )
                ) {
                    Log.d(TAG, "Already booked from the other channel: ${entity.transactionHash}")
                    return ProcessingResult(false, reason = "Duplicate transaction")
                }
            }

            // Check for custom merchant mapping
            val customCategory = merchantMappingRepository.getCategoryForMerchant(entity.merchantName)
                ?.takeIf { mappingFits(database.categoryDao().getCategoryByName(it)?.isIncome, entity.transactionType) }
            val entityWithMapping = if (customCategory != null) {
                Log.d(TAG, "Found custom category mapping: ${entity.merchantName} -> $customCategory")
                entity.copy(category = customCategory)
            } else {
                entity
            }

            // Apply rule engine to the transaction
            val activeRules = ruleRepository.getActiveRulesByType(entityWithMapping.transactionType)

            // Check if this transaction should be blocked
            val blockingRule = ruleEngine.shouldBlockTransaction(
                entityWithMapping,
                smsBody,
                activeRules
            )

            if (blockingRule != null) {
                Log.d(TAG, "Transaction blocked by rule: ${blockingRule.name}")
                return ProcessingResult(false, reason = "Blocked by rule: ${blockingRule.name}")
            }

            val (entityWithRules, ruleApplications) = ruleEngine.evaluateRules(
                entityWithMapping,
                smsBody,
                activeRules
            )

            if (ruleApplications.isNotEmpty()) {
                Log.d(TAG, "Applied ${ruleApplications.size} rules to transaction")
            }

            // Check if this transaction matches an active subscription
            val matchedSubscription = subscriptionRepository.matchTransactionToSubscription(
                entityWithRules.merchantName,
                entityWithRules.amount
            )
            val finalEntity = if (matchedSubscription != null) {
                Log.d(TAG, "Matched subscription ${matchedSubscription.id}")
                entityWithRules.copy(isRecurring = true)
            } else {
                entityWithRules
            }

            val rowId = if (matchedSubscription != null) {
                // Atomicity: advance the billing cycle only if the charge row commits.
                // Insert returning -1L (duplicate hash) or a throw must leave
                // next_payment_date untouched. Callees are DAO passthroughs; no
                // nested transactions.
                database.withTransaction {
                    val id = transactionRepository.insertTransaction(finalEntity)
                    if (id != -1L) {
                        subscriptionRepository.updateNextPaymentDateAfterCharge(
                            matchedSubscription.id,
                            finalEntity.dateTime.toLocalDate()
                        )
                    }
                    id
                }
            } else {
                transactionRepository.insertTransaction(finalEntity)
            }
            if (rowId != -1L) {
                Log.d(TAG, "Saved new transaction with ID: $rowId${if (finalEntity.isRecurring) " (Recurring)" else ""}")

                // Save rule applications if any rules were applied. The engine ran
                // before the insert, so each application still carries the entity's
                // placeholder id of 0 — remap to the real row id, as the SMS worker
                // does, or the FK on rule_applications.transaction_id rejects the write
                // and the throw skips the tag/balance/widget work below.
                if (ruleApplications.isNotEmpty()) {
                    ruleRepository.saveRuleApplications(
                        ruleApplications.map { it.copy(transactionId = rowId.toString()) }
                    )
                    // ADD_TAG / REMOVE_TAG live in the tag table, not on the row (#748).
                    val (add, remove) = ruleApplications.tagChanges()
                    if (add.isNotEmpty() || remove.isNotEmpty()) tagRepository.applyTagChanges(rowId, add, remove)
                }

                // Process balance updates
                processBalanceUpdate(parsedTransaction, finalEntity, rowId)

                // Trigger widget refresh for the transaction-derived widgets
                com.moneytracker.app.widget.WidgetRefresher.refreshTransactionWidgets(appContext)

                return ProcessingResult(true, transactionId = rowId)
            } else {
                Log.d(TAG, "Transaction already exists (duplicate): ${entity.transactionHash}")
                return ProcessingResult(false, reason = "Duplicate transaction")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving transaction: ${e.message}")
            return ProcessingResult(false, reason = e.message)
        }
    }

    private suspend fun processBalanceUpdate(
        parsedTransaction: ParsedTransaction,
        entity: TransactionEntity,
        rowId: Long
    ) {
        if (parsedTransaction.accountLast4 == null) {
            // Mobile-money wallets (eMola, M-Pesa Mozambique) have no per-account
            // number — the whole wallet is one account. Derive a single service-level
            // account row from the running balance the SMS carries, so the wallet shows
            // up as an account and counts toward the total balance.
            if (parsedTransaction.isMobileWallet && parsedTransaction.balance != null) {
                upsertWalletBalance(parsedTransaction, entity, rowId)
            }
            return
        }

        val isFromCard = parsedTransaction.isFromCard

        val targetAccountLast4: String? = if (isFromCard) {
            var card = parsedTransaction.accountLast4?.let {
                cardRepository.getCard(parsedTransaction.bankName, it)
            }

            if (card == null) {
                val isCredit = (parsedTransaction.type.toEntityType() == TransactionType.CREDIT)
                parsedTransaction.accountLast4?.let { accountLast4 ->
                    cardRepository.findOrCreateCard(
                        cardLast4 = accountLast4,
                        bankName = parsedTransaction.bankName,
                        isCredit = isCredit
                    )
                }
                card = parsedTransaction.accountLast4?.let {
                    cardRepository.getCard(parsedTransaction.bankName, it)
                }
            }

            if (card == null) {
                Log.w(TAG, "Could not create/find card for ${parsedTransaction.bankName}")
                null
            } else {
                // Update card's balance
                cardRepository.updateCardBalance(
                    cardId = card.id,
                    balance = parsedTransaction.balance,
                    source = parsedTransaction.smsBody.take(200),
                    date = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(parsedTransaction.timestamp),
                        ZoneId.systemDefault()
                    )
                )

                if (card.cardType == CardType.DEBIT && card.accountLast4 == null) {
                    val resolvedParent = resolveActiveAccountForBank(card.bankName)
                    if (resolvedParent != null) {
                        cardRepository.linkCardToAccount(card.id, resolvedParent)
                        card = card.copy(accountLast4 = resolvedParent)
                        Log.i(TAG, "Auto-linked debit card ${card.cardLast4} (${card.bankName}) to bank account $resolvedParent")
                    }
                }

                when {
                    card.cardType == CardType.CREDIT -> parsedTransaction.accountLast4
                    card.cardType == CardType.DEBIT && card.accountLast4 != null -> card.accountLast4
                    else -> null // Unlinked debit cards MUST NEVER create standalone account rows
                }
            }
        } else {
            parsedTransaction.accountLast4
        }

        if (targetAccountLast4 != null) {
            val isCreditCard = (parsedTransaction.type.toEntityType() == TransactionType.CREDIT) ||
                    parsedTransaction.accountLast4?.let {
                        cardRepository.getCard(parsedTransaction.bankName, it)?.cardType
                    } == CardType.CREDIT

            val existingAccount = accountBalanceRepository.getLatestBalance(
                parsedTransaction.bankName,
                targetAccountLast4
            )

            val resolvedIsCreditCard = BalanceCalculator.resolvedIsCreditCard(isCreditCard, existingAccount)

            // The account keeps its own currency; a foreign-currency spend isn't
            // added to it (see BalanceCalculator.balanceAfter).
            val accountCurrency = existingAccount?.currency
                ?: parsedTransaction.accountCurrency
                ?: parsedTransaction.currency
            // Moves from the balance just before this SMS in time, not the newest
            // row, the same rule as the inbox scan (#982).
            accountBalanceRepository.insertSmsTransactionBalance(
                parsedTransaction.bankName, targetAccountLast4, entity.dateTime
            ) { before ->
                val newBalance = BalanceCalculator.balanceAfter(
                    explicitBalance = parsedTransaction.balance,
                    // A loan account's balance is owed too: a payment lowers it (#792).
                    isCreditCard = resolvedIsCreditCard || existingAccount?.isLoan == true,
                    transactionType = parsedTransaction.type.toEntityType(),
                    transactionAmount = parsedTransaction.amount,
                    transactionCurrency = parsedTransaction.currency,
                    accountCurrency = accountCurrency,
                    currentBalance = before
                )

                Log.i(TAG, "[BAL_INSPECT] Balance for ${parsedTransaction.bankName} Account ••••$targetAccountLast4: before=$before, delta=${parsedTransaction.amount}, after=$newBalance (explicitBal=${parsedTransaction.balance})")

                // Credit-card SMS report the AVAILABLE limit, not the total (the UI derives
                // available = creditLimit − balance); see BalanceCalculator.cardFiguresAfter.
                val card = if (resolvedIsCreditCard) {
                    BalanceCalculator.cardFiguresAfter(
                        availableLimit = parsedTransaction.creditLimit,
                        explicitOutstanding = parsedTransaction.balance,
                        runningOutstanding = newBalance,
                        existingLimit = existingAccount?.creditLimit
                    )
                } else {
                    null
                }
                val resolvedCreditLimit = card?.creditLimit ?: existingAccount?.creditLimit
                val finalBalance = card?.outstanding ?: newBalance


                AccountBalanceEntity(
                    bankName = parsedTransaction.bankName,
                    accountLast4 = targetAccountLast4,
                    balance = finalBalance,
                    timestamp = entity.dateTime,
                    transactionId = if (rowId != -1L) rowId else null,
                    creditLimit = resolvedCreditLimit,
                    isCreditCard = resolvedIsCreditCard,
                    smsSource = parsedTransaction.smsBody.take(500),
                    sourceType = "TRANSACTION",
                    accountType = BalanceCalculator.preservedAccountType(existingAccount) ?: if (resolvedIsCreditCard) "CREDIT" else null,
                    currency = accountCurrency,
                    statementDay = existingAccount?.statementDay,
                    profileId = existingAccount?.profileId ?: ProfileEntity.PERSONAL_ID,
                    alias = existingAccount?.alias,
                    lowBalanceThreshold = existingAccount?.lowBalanceThreshold
                )
            }
            Log.d(TAG, "Saved balance update for ${parsedTransaction.bankName} **$targetAccountLast4")
        }
    }

    /**
     * Derives a single service-level account row for a mobile-money wallet
     * (eMola, M-Pesa Mozambique). These SMS carry the running balance but no
     * per-account number, so the account is keyed on bankName with the
     * [AccountBalanceEntity.WALLET_ACCOUNT_MARKER] sentinel and the balance is
     * taken straight from the SMS (never a card, never credit).
     */
    private suspend fun upsertWalletBalance(
        parsedTransaction: ParsedTransaction,
        entity: TransactionEntity,
        rowId: Long
    ) {
        val balance = parsedTransaction.balance ?: return
        val existingAccount = accountBalanceRepository.getLatestBalance(
            parsedTransaction.bankName,
            AccountBalanceEntity.WALLET_ACCOUNT_MARKER
        )

        val balanceEntity = AccountBalanceEntity(
            bankName = parsedTransaction.bankName,
            accountLast4 = AccountBalanceEntity.WALLET_ACCOUNT_MARKER,
            balance = balance,
            timestamp = entity.dateTime,
            transactionId = if (rowId != -1L) rowId else null,
            creditLimit = null,
            isCreditCard = false,
            smsSource = parsedTransaction.smsBody.take(500),
            sourceType = "TRANSACTION",
            accountType = BalanceCalculator.preservedAccountType(existingAccount),
            // The balance is in the account's currency, which can differ from the spend's.
            currency = parsedTransaction.accountCurrency ?: parsedTransaction.currency,
            profileId = existingAccount?.profileId ?: ProfileEntity.PERSONAL_ID,
            alias = existingAccount?.alias,
            lowBalanceThreshold = existingAccount?.lowBalanceThreshold
        )

        accountBalanceRepository.insertBalance(balanceEntity)
        Log.d(TAG, "Saved wallet balance for ${parsedTransaction.bankName}")
    }

    /**
     * A bank's hold or release of funds for a UPI mandate names no account ("your account
     * has been blocked …"), so it is given the bank's only non-card account — when there
     * is exactly one. Anything else keeps the account it was parsed with.
     */
    private suspend fun withHeldAccount(parsed: ParsedTransaction): ParsedTransaction =
        if (parsed.accountLast4 == null && com.moneytracker.parser.core.bank.FundsHold.isHold(parsed.smsBody)) {
            resolveActiveAccountForBank(parsed.bankName)?.let { parsed.copy(accountLast4 = it) } ?: parsed
        } else {
            parsed
        }

    private suspend fun resolveActiveAccountForBank(bankName: String): String? {
        val accounts = accountBalanceRepository.getAllLatestBalancesOnce()
            .filter { it.bankName.equals(bankName, ignoreCase = true) && !it.isCreditCard }
        return if (accounts.size == 1) {
            accounts.first().accountLast4
        } else {
            null
        }
    }
}
