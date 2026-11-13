# Onboarding, first-run setup and "are the numbers right?" checks

For a new developer on a **different phone with different data**. Do this once, in
order. Keep your real numbers in `local-notes/` (git-ignored), never in the repo.

## 1. Machine setup

> The full build, signing and Gmail steps are in [`SETUP.md`](SETUP.md); this page is the
> short version plus the verification checklist.

1. Install **JDK 21**, **Android Studio** (current stable) with SDK 37 and platform-tools.
2. Clone the repo, then create `local.properties` with your SDK path:
   `sdk.dir=C\:\Users\<you>\AppData\Local\Android\Sdk` (this file is git-ignored).
3. Run `./init.sh app`. It must pass before you change anything. (`./init.sh parser` is
   the fast loop for parser work; `./init.sh` runs everything.)
4. Phone: enable Developer options → USB debugging, connect, `adb devices` must list it.
5. Install: `./gradlew :app:installStandardDebug`. The debug app id is
   `com.moneytracker.app.debug` and it shows as **Money Tracker Debug**. For a signed
   release app (`com.moneytracker.app`) see `SETUP.md` §5. The two are separate apps.

## 2. Gmail statement fetch (optional but recommended)

The app uses Google's on-device authorization (no secret in the repo). It works only if
an **Android OAuth client in your own Google Cloud project** matches your build. Full
steps: [`SETUP.md` §6](SETUP.md#6-gmail-statement-fetch-create-your-own-oauth-client).
In short:

- Create a project, enable the Gmail API, set up the consent screen in **Testing** with
  scope `gmail.readonly`, and add your Google account as a **test user**.
- Create an **Android** OAuth client per build: package `com.moneytracker.app.debug`
  with the SHA-1 of your debug keystore (`./gradlew :app:signingReport`), and package
  `com.moneytracker.app` with the SHA-1 of your release keystore.
- Consent expires about weekly while the project is in "Testing"; the app posts a
  notification when it needs approval again.
- If you do not want Gmail, skip it: statements can be picked from storage instead
  (Import Statement → Select PDF).
## 3. First run on the phone

1. Open the app, finish onboarding, grant **SMS** permission (and notifications).
2. Scan SMS for the last ~3 months. Wait for it to finish; note the totals shown.
3. **Accounts**: open Manage accounts; for each bank account/card check type and name.
   Set a starting balance only where the app has no balance yet.
4. **Statements**: Import Statement → Gmail → *Fetch and import everything now* (or pick
   PDFs). Enter a statement password once; it is remembered (encrypted) per bank/card.
   A bank with no reader will show as unsupported — that is expected (see FORK_HANDOFF).
5. **Own accounts**: Import Statement → own payees. Add rules for anything that moves
   money between your own accounts or pays your own card (e.g. a card-bill service).
6. **Settings → Backup passphrase**: choose one and store it in a password manager.
7. Optional: app lock, daily folder backup.

## 4. Verification checklist — do all of these

Record the expected value from your bank app/statement, then the app's value.
**Pass = they match, or the difference is explained below.**

| # | Check | How | Pass when |
|---|---|---|---|
| 1 | Bank account balance | Compare each account in the app with the bank app today | Equal, unless a UPI mandate/hold is pending (the app shows balance after holds; the bank may show before) |
| 2 | Card outstanding | Compare each card's outstanding with the card app or latest statement "total due" + anything spent since | Equal after adding unbilled spend; limit and available also match |
| 3 | SMS vs statement agreement | Import the latest statement of each card; open "Review before importing" | Almost all rows "Already in"; "New" rows are only things the SMS never covered. Many "New" rows means an SMS parser gap |
| 4 | No missed SMS | Count bank SMS in the inbox (`adb shell content query --uri content://sms/inbox`), grouped by sender and amount, vs transactions in the app | Equal per bank/amount (duplicates of the same SMS count once) |
| 5 | No double-counted card spend | Pay a card bill from your bank, then look at Analytics for that month | The bank-side payment is listed under *Credit Card Payment* and is **not** in spending; the card's own purchases are counted once |
| 6 | Own-account transfers | Move money between two of your accounts | Neither side appears as spending or income once the own-payee rule exists |
| 7 | Refunds & cashback | A refund to the card or a statement credit | Counts as income/credit once, nets against the merchant in Analytics |
| 8 | Grouping | Analytics → By merchant, for your top merchants | One row per brand (no `Brand Pvt Ltd` / `Brand In E` leftovers) |
| 9 | Month total vs card statement | Sum of "total purchases" on a statement vs the app's spend on that card for the same period | Equal (cash advances/fees aside) |
| 10 | Backup round trip | Export, then on a second phone (or after clearing app data) restore with the passphrase | Same transaction count and balances; no duplicates when you scan SMS again |
| 11 | Re-import is harmless | Import the same statement twice; scan SMS twice | Row counts do not change the second time |
| 12 | App lock | Back out of the app and reopen | Asks for biometric/PIN every time |

### Known, explained differences (not bugs)

- Balance shown by a bank after a UPI mandate may differ from the SMS balance until it is released.
- Unbilled card spend is not on the statement but is in the app.
- Wallet spend (Swiggy/Zomato cash, Amazon Pay balance) is invisible; only top-ups count.
- Investment transfers currently count as spending (open item).
- A transaction you deleted can reappear after restore + re-scan (open item).

## 5. If a number is wrong

1. Find the bank's SMS and the statement row. Is the SMS parsed at all (Unrecognized
   messages screen)? A new SMS format → `docs/adding-bank-parsers.md`.
2. Is it counted twice, or excluded wrongly? Look at `excludedFromAnalytics` and the
   merchant name on the row (detail screen), then at `ParsedTransactionMapper`.
3. Add a test with **invented** data that reproduces it (ParserTestUtils for parsers),
   fix, run `./init.sh app`, and re-check on the phone.
4. Do not "fix" a balance by editing data. If SMS and statement disagree, the cause is
   a parser/mapping gap; find it.

## 6. Handy commands

```bash
./init.sh app                      # compile + unit tests (the gate)
./init.sh parser                   # parser-core only
./gradlew :app:installStandardDebug
./gradlew :app:signingReport       # SHA-1 for the Gmail OAuth client
MSYS_NO_PATHCONV=1 adb shell ...   # Git Bash on Windows
adb shell uiautomator dump /sdcard/ui.xml   # read the screen without a screenshot
```
