# Fork handoff — read this first

This repo is a **community fork** of PennyWise AI (app name in this fork: **Money Tracker**).
It is not a product: no store release, no payments, no hosted service. Everything that
used to be "Pro" is unlocked. Start with `SETUP.md` to build and install it, then read
`../CLAUDE.md` for the hard constraints (they still apply), then this file for what the
fork changed and why. Then do `ONBOARDING_AND_VERIFICATION.md` before trusting any
number on screen.

> Written for a new developer **and** for a new Claude session that has none of the
> original conversation. If you are that Claude: the earlier chat lives only on the
> original developer's machine and holds their real financial data, so it is not
> shared. Everything you need to continue is here and in the code.

## Standing rules

- **No personal data in the repo, ever** — no real names, account numbers, balances,
  SMS text, statements or e-mail addresses in code, tests, comments or docs. Tests use
  invented values (see `docs/parser-test-standards.md`). Keep your own ground-truth
  notes in `local-notes/` (git-ignored).
- The database and statements on a phone are personal. Never paste real SMS, balances
  or statement text into a prompt, issue, commit or screenshot you share.
- Each change set bumps the version and adds a changelog file by hand (CLAUDE.md, Hard Constraint 5); don't run `scripts/release.sh` for ordinary commits.
- Money is always currency-tagged and never summed across currencies (CLAUDE.md #1–2).
- Every backup-serialized field needs a Kotlin default (CLAUDE.md #3).
- Gate before calling anything done: `./init.sh app` (or `parser`). Runtime/UI changes
  also need a check on a real phone.

## What this fork added or changed

| Area | What it does | Where |
|---|---|---|
| Gmail statement fetch | Signs in read-only (`gmail.readonly`), finds statement PDFs from the last 3 months, keeps the newest per account, skips investment senders (Zerodha, NSE alerts…). Button **Fetch and import everything now** plus an optional daily background run with notifications. | `data/statement/gmail/*`, `presentation/statement/GmailStatementsViewModel.kt`, flavor modules `src/standard` / `src/fdroid` (`GmailModule.kt`) |
| Statement readers | Credit-card PDFs (HDFC, HSBC, ICICI) and account PDFs (SBI, ICICI, HDFC, IDBI). They produce transactions plus a card summary (limit, available, due). Other banks need a new reader. | `data/statement/*PdfParser.kt`, `CardStatement.kt`, `PdfParserFactory.kt` |
| Saved statements | Fetched/imported PDFs are kept (encrypted at rest with a Keystore key) so they are never imported twice; passwords remembered per bank/card (Keystore-encrypted). | `SavedStatementStore.kt`, `StatementFileCipher.kt`, `StatementPasswordStore.kt` |
| Card balances | Outstanding = limit − available limit from SMS; statement application is idempotent and never overwrites a newer SMS balance. | `ImportStatementUseCase.applyBalance`, `StatementBalanceCheck.kt`, `BalanceCalculator.kt` |
| UPI mandate holds | A hold/release SMS pair must not move the balance twice. | `parser-core/.../FundsHold.kt`, `ICICIBankParser.kt` |
| Own payees | "This payee is one of my accounts" rules pair a debit with its credit on another account (or record the missing one) so own-account transfers are not spending/income. | `data/payees/*`, `presentation/payees/*` |
| Card bill payments | A payment to your own card is excluded from analytics (but still moves balances) so spend is not counted twice (once at the shop, once as the bill). | `ParsedTransactionMapper.isCreditCardPayment`, `isIciciCardBillPay`, `TransactionDao.excludeCardBillPayments` |
| Merchant grouping | Spellings of one brand collapse to one merchant (Blinkit, Amazon, Myntra, Swiggy, Zomato, Instamart). Repair of stored rows runs once per launch. | `canonicalizeMerchantName`, `TransactionRepository.canonicalizeKnownMerchants` |
| Analytics | Credit-card spend counted by default; tapping a merchant/category/account/tag opens an in-place sheet of its transactions. | `ui/screens/analytics/*`, `UserPreferencesRepository.countCreditCardAsExpense` |
| Encrypted backups | Backups are always encrypted with a **passphrase** (PBKDF2 + AES-GCM) and include statement passwords and own-payee rules. Restore always asks for the passphrase. The passphrase is also remembered (Keystore) so the daily folder backup runs unattended. | `data/backup/BackupCrypto.kt`, `BackupPassphraseStore.kt`, `docs/backup-format.md` |
| App lock | Locks on every cold start and whenever the main screen is closed, regardless of the timeout. Minimising still follows the timeout. | `PennyWiseApplication.kt` (`AppLockLifecycleObserver`), `AppLockRepository` |
| Refund links | Link a refund (income marked Refund) to the expenses it pays back, per category part of a split expense. Stored copies (`refunded_total`, `refunded_amount`) make every total see the net cost; an unlinked refund counts for nothing. | `data/refunds/RefundLinks.kt`, `RefundLinkRepository`, `presentation/refunds/*`, `utils/TransactionFilters.kt` |
| Removed | Chat tab, on-device LLM and model download, all Pro upsell cards, `AD_ID`. | gone from `ui/`, `data/`, `di/`, manifest |

## Decisions worth knowing (and why)

- **Only bank/card-reported money is tracked.** Wallet balances (Swiggy/Zomato cash,
  Amazon Pay balance) produce no bank SMS, so top-ups count once as spend and wallet
  spending is invisible — no double counting, but also no cashback income.
- **SMS is the source of truth for balances; statements correct and complete it.**
  A statement's "Review before importing" screen should show nearly all rows as
  "Already in" — that agreement is the main correctness signal.
- **Database is not encrypted.** Android file-based encryption covers a locked phone;
  SQLCipher would add a risky one-time conversion for little gain. Backups are the part
  that leaves the phone, so they are the part that is encrypted.
- **The chat message table stays in the DB and in the backup schema** so old backups
  restore and no Room migration is needed.
- Migrations must run on SQLite 3.18 (Android 8) — see CLAUDE.md #8.

## Known open items (good first tasks)

1. **Deleted transactions are not exported**, so after a restore plus SMS re-scan they
   can reappear. Fix: export soft-deleted rows (hidden) and keep their hashes.
2. **Investment debits** (e.g. broker transfers) count as spending.
3. Possible duplicate: a card payment seen both on the statement and as an SMS row (HSBC).
4. Wallet awareness / cashback tracking (see "Only bank/card-reported money").
5. Statement readers exist only for the banks listed above; a new bank = new reader
   (use the `parser-author` subagent, then add a fixture-free test with invented text).
6. A cloud assistant (Gemini/Claude) was considered after removing the local chat. If
   built, decide first what data may leave the phone — today nothing does.

## Environment gotchas (Windows)

- Git Bash mangles device paths: prefix adb with `MSYS_NO_PATHCONV=1`.
- Some files are CRLF; multi-line `sed`/`perl` replacements silently fail — use an editor.
- `parser-core` must stay iOS-compatible (no `java.time`): `IosCompatibilityTest` enforces it.
- Gmail sign-in works only for Google accounts listed as **test users** of *your own*
  Google Cloud project, whose Android OAuth client matches the build's app id and
  signing SHA-1, and consent expires about weekly while the project is in testing (the
  app notifies you). Steps: `SETUP.md` §6.
- Debug and release are **different apps** (`.debug` suffix) with separate data; a new
  OAuth client is needed per app id + key.
- adb over USB drops now and then; retry before assuming a code problem.

## Working with Claude on this repo

Start a new session in the repo root (Claude Code reads `CLAUDE.md` automatically) and
say: *"Read docs/FORK_HANDOFF.md and docs/ONBOARDING_AND_VERIFICATION.md, then help me
set up and verify on my phone."* Useful habits that worked here:

- Reproduce on the phone with adb (`uiautomator dump` + `input tap`) rather than guessing UI bugs.
- For "are we missing transactions?", export the SMS inbox via
  `adb shell content query --uri content://sms/inbox`, replay it through the parsers in a
  throw-away test, and compare counts per bank and amount. Delete the harness afterwards.
- Never commit exported inboxes, DB snapshots or statements; keep them outside the repo.
