# Money Tracker

A private, SMS-powered expense tracker for Android. It turns the transaction SMS your
bank already sends — plus your card and account statements from Gmail — into one
accurate, searchable money timeline. **Everything is stored on your phone**: no account,
no cloud, no analytics.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-green)

> **Derived work.** Money Tracker is a fork of
> [PennyWise AI](https://github.com/sarim2000/pennywiseai-tracker) by Sarim Ahmed and
> contributors (AGPL-3.0). The SMS parser engine and most of the app come from that
> project, and the credit is theirs. This fork is **not affiliated with or endorsed by**
> it, uses its own name and artwork, and is not published on any store. See
> [`NOTICE.md`](NOTICE.md) for attribution and what was changed, and
> [`TRADEMARKS.md`](TRADEMARKS.md) for the naming rules that apply to forks.

## Quick start

You build it yourself (there is no store release) and install it on your phone.

```bash
git clone https://github.com/eeshir/money-tracker-android.git
cd money-tracker-android
# create local.properties with your Android SDK path (see docs/SETUP.md), then:
./init.sh app                        # compile + unit tests
./gradlew :app:installStandardDebug  # install on a connected phone (USB debugging on)
```

You need **JDK 21**, Android Studio with **SDK 37**, and an Android 8.0+ phone.
The full guide, including **release signing**, **Gmail statement fetch with your own
Google OAuth client**, and **backup / restore**, is in
**[`docs/SETUP.md`](docs/SETUP.md)**.

| I want to… | Read |
|---|---|
| Build, sign and install the app | [`docs/SETUP.md`](docs/SETUP.md) |
| Set up Gmail statement fetch | [`docs/SETUP.md` §6](docs/SETUP.md#6-gmail-statement-fetch-create-your-own-oauth-client) |
| Check the numbers are right on my phone | [`docs/ONBOARDING_AND_VERIFICATION.md`](docs/ONBOARDING_AND_VERIFICATION.md) |
| Back up, restore or move to a new phone | [`docs/SETUP.md` §8](docs/SETUP.md#8-backup-restore-and-moving-between-installs) |
| Change the code (humans or AI agents) | [`CLAUDE.md`](CLAUDE.md), [`docs/FORK_HANDOFF.md`](docs/FORK_HANDOFF.md) |
| Add support for my bank's SMS | [`docs/adding-bank-parsers.md`](docs/adding-bank-parsers.md) |

## What it does

1. **Reads bank SMS** (read-only permission) and extracts amount, merchant, category,
   date and balance in real time. Parsers cover <!-- BANKS_SUMMARY -->170 banks across 26 countries<!-- /BANKS_SUMMARY -->.
2. **Reads statements from Gmail** (optional, read-only, last 3 months) and imports the
   latest credit-card and account statements, then reconciles them with the SMS — so
   card outstanding, limits and anything the SMS missed are right.
3. **Shows where the money went**: analytics by merchant, category, account and tag,
   with a tap-through list of the underlying transactions.

## What this fork adds

- **Gmail statement import** — sign in read-only, fetch the newest statement per card
  or account, import automatically (button or daily background run), with a
  notification when Google needs approval again.
- **Statement readers** for HDFC, HSBC and ICICI credit cards and SBI / ICICI / HDFC / IDBI accounts;
  saved statements are encrypted on the phone and never imported twice; statement
  passwords are remembered per bank and card (Keystore-encrypted).
- **Correct card maths** — outstanding from the card's available limit, idempotent
  statement balances, UPI-mandate holds that don't move balances twice.
- **No double counting** — paying a card bill is excluded from spending, own-account
  transfers can be paired with "own payee" rules, and brand variants are merged
  (Amazon, Swiggy, Zomato, Blinkit, Myntra…).
- **Encrypted backups you can tune** — every backup is encrypted with your passphrase
  (PBKDF2 + AES-GCM); restore always asks for it. Tick what to include: receipt photos,
  saved statement PDFs, card rewards, webhooks. Local file, share sheet, or a daily
  folder backup with its own choices.
- **Card rewards** — per-card reward rates and cashback caps, with the cap used and
  left in the current statement cycle; refunds give the reward back.
- **Refund links** — link a refund to the expense it pays back, so totals show the net
  cost.
- **Stricter app lock** — biometric/PIN on every cold start and whenever the app is
  closed, not only after a timeout.
- Everything formerly behind a paywall is unlocked. The on-device chat assistant and
  all upsell screens were removed.

## Core features

- **Multi-currency**, budgets, goals, loans and split bills, subscriptions detection
- **Rules** to auto-categorise, modify or block transactions (below)
- **Multi-account balances** with history, account merging, ignored accounts
- **Widgets**, Material You theming, manual and recurring transactions, CSV export
- **Search and filters** by category, merchant, period, currency and type

### Smart Rules Engine

Rules let you auto-categorize, modify, or block transactions based on configurable **conditions**. A rule consists of one or more "When" conditions and a "Then" action.

**Available condition fields:**

| Field | Description | Example value | Supported operators |
|---|---|---|---|
| `AMOUNT` | Transaction amount | `200` | `<`, `>`, `=` |
| `TYPE` | Income/Expense/Transfer | `INCOME` | `contains`, `equals`, `starts with` |
| `CATEGORY` | Assigned category | `Food & Dining` | `contains`, `equals`, `starts with` |
| `MERCHANT` | Merchant/vendor name | `Amazon` | `contains`, `equals`, `starts with` |
| `NARRATION` | Transaction description | `Monthly rent` | `contains`, `equals`, `starts with` |
| `SMS_TEXT` | Raw SMS body | `OTP` | `contains`, `equals`, `starts with` |
| `BANK_NAME` | Bank name from SMS | `HDFC Bank` | `contains`, `equals`, `starts with` |
| `TIME` / `HOUR` | Transaction time | `09:00` / `9` | `before`, `after`, `at or after`, `at or before` |
| `DAY_OF_WEEK` | Day (1=Mon .. 7=Sun) | `1` | `is`, `is any of`, `is not` |
| `DAY_OF_MONTH` | Day of month (1-31) | `15` | `is`, `is any of`, `before`, `after` |
| `DATE` | Transaction date | `2026-03-21` | `is`, `before`, `after` |
| **`ACCOUNT`** | **Bank account composite** | Select from picker | `is`, `is not` |

**Account condition (ACCOUNT)** — Restricts the rule to transactions from a specific bank account. The value is stored as a `BankName||Last4` composite key (e.g. `HDFC Bank||1234`). When selected, the UI shows an account picker populated from your tracked accounts. Only `EQUALS` and `NOT_EQUALS` operators are available.

Conditions can be combined with **AND** / **OR** logic, and multiple conditions per rule are supported.

**Available actions:**
- **Set** — Override a field (category, merchant, type, description)
- **Append / Prepend** — Add text to a field (merchant, description)
- **Clear** — Remove a field value
- **Block** — Prevent the transaction from being saved

**JSON representation** (stored locally, not exposed via API):
```json
{
  "conditions": [
    {"field": "ACCOUNT", "operator": "EQUALS", "value": "HDFC Bank||1234"},
    {"field": "AMOUNT", "operator": "GREATER_THAN", "value": "500"}
  ],
  "actions": [
    {"field": "CATEGORY", "actionType": "SET", "value": "Food & Dining"}
  ]
}
```

## Supported Banks & Countries

<!-- SUPPORTED_BANKS:START (generated by scripts/update-supported-banks.sh — do not edit by hand) -->
Supporting **170 banks & services** across **26 countries** with **multi-currency** capabilities:

### 🇮🇳 India — INR ₹ (57)
**AU Small Finance Bank**, **Airtel Payments Bank**, **Amazon Pay**, **American Express**, **Axis Bank**, **Bandhan Bank**, **Bank of Baroda**, **Bank of India**, **CRED**, **Canara Bank**, **Cashfree**, **Central Bank of India**, **City Union Bank**, **DBS Bank**, **Department of Post**, **Dhanlaxmi Bank**, **Equitas Small Finance Bank**, **Federal Bank**, **Greater Bank**, **HDFC Bank**, **HDFC Mutual Fund**, **HSBC Bank**, **ICICI Bank**, **IDBI Bank**, **IDFC First Bank**, **India Post Payments Bank**, **Indian Bank**, **Indian Overseas Bank**, **IndusInd Bank**, **JK Bank**, **Jana Small Finance Bank**, **Jio Payments Bank**, **JioPay**, **Jupiter**, **Karnataka Bank**, **Kerala Bank**, **Kerala Gramin Bank**, **Kotak Bank**, **LazyPay**, **NSDL Payments Bank**, **Navi Mutual Fund**, **OneCard**, **Pluxee**, **Punjab & Sind Bank**, **Punjab National Bank**, **SVC Co-operative Bank**, **Saraswat Co-operative Bank**, **Slice**, **South Indian Bank**, **Standard Chartered Bank**, **State Bank of India**, **Suryoday Small Finance Bank**, **Tamilnad Mercantile Bank**, **UCO Bank**, **Union Bank of India**, **Utkarsh Bank**, **Yes Bank**

### 🇳🇵 Nepal — NPR ₨ (13)
**Citizens Bank**, **Everest Bank**, **Laxmi Sunrise Bank**, **Lumbini Bikash Bank**, **Machchhapuchchhre Bank**, **Manjushree Finance**, **NMB Bank**, **Nabil Bank**, **Nepal Bank Limited**, **Nepal SBI Bank**, **Prime Commercial Bank**, **Siddhartha Bank**, **Standard Chartered Bank Nepal**

### 🇹🇭 Thailand — THB ฿ (11)
**BAAC**, **Bangkok Bank**, **CIMB Thai**, **Government Savings Bank**, **KTC**, **Kasikorn Bank**, **Krungsri**, **Krungthai Bank**, **Siam Commercial Bank**, **TTB**, **UOB Thailand**

### 🇺🇸 United States — USD $ (10)
**ALECU**, **AdelFi**, **Altana Federal Credit Union**, **Charles Schwab**, **Chase**, **Citi Bank**, **Discover Card**, **Huntington Bank**, **Navy Federal Credit Union**, **Old Hickory Credit Union**

### 🇪🇹 Ethiopia — ETB ብር (9)
**Apollo**, **Awash Bank**, **Bank of Abyssinia**, **Commercial Bank of Ethiopia**, **Dashen Bank**, **Siket Bank**, **Telebirr**, **ZamZam Bank**, **Zemen Bank**

### 🇳🇬 Nigeria — NGN ₦ (9)
**Access Bank**, **GTBank**, **Jaiz Bank**, **Keystone Bank**, **Moniepoint**, **Opay**, **Standard Chartered Bank Nigeria**, **VFD Microfinance Bank**, **Zenith Bank**

### 🇸🇦 Saudi Arabia — SAR ﷼ (7)
**Al Rajhi Bank**, **Alinma Bank**, **Banque Saudi Fransi**, **D360 Bank**, **SABB**, **STC Bank**, **Saudi National Bank**

### 🇹🇿 Tanzania — TZS TSh (7)
**CRDB Bank**, **Diamond Trust Bank**, **M-Pesa Tanzania**, **Mixx by Yas**, **NMB Bank Tanzania**, **Selcom Pesa**, **Tigo Pesa**

### 🇦🇪 UAE — AED د.إ (7)
**Abu Dhabi Commercial Bank**, **Dubai First**, **Emirates Islamic**, **Emirates NBD**, **First Abu Dhabi Bank**, **Liv Bank**, **Mashreq Bank**

### 🇮🇷 Iran — IRR ﷼ (6)
**Bankino**, **Mellat Bank**, **Melli Bank**, **Parsian Bank**, **Pasargad Bank**, **blu Bank**

### 🇯🇴 Jordan — JOD JD (5)
**Bank al Etihad**, **Islamic International Arab Bank**, **Jordan Islamic Bank**, **Orange Money**, **Safwa Islamic Bank**

### 🇲🇿 Mozambique — MZN MT (4)
**M-Pesa Mozambique**, **Millennium BIM**, **Standard Bank Mozambique**, **eMola**

### 🇱🇰 Sri Lanka — LKR Rs (4)
**National Development Bank**, **National Savings Bank**, **Nations Trust Bank**, **Sampath Bank**

### 🇪🇬 Egypt — EGP E£ (3)
**Arab Bank**, **CIB Egypt**, **National Bank of Egypt**

### 🇸🇻 El Salvador — USD $ (3)
**Banco Agricola**, **Banco Cuscatlan**, **Banco Promerica**

### 🇧🇩 Bangladesh — BDT ৳ (2)
**BRAC Bank**, **bKash**

### 🇪🇺 Eurozone — EUR € (2)
**BPCE**, **Sparkasse Rhein-Maas**

### 🇰🇪 Kenya — KES Ksh (2)
**Equity Bank**, **M-PESA**

### 🇬🇧 United Kingdom — GBP £ (2)
**Chase UK**, **Trading 212**

### 🇧🇾 Belarus — BYN Br (1)
**Priorbank**

### 🇨🇴 Colombia — COP $ (1)
**Bancolombia**

### 🇨🇿 Czech Republic — CZK Kč (1)
**mBank CZ**

### 🇴🇲 Oman — OMR ر.ع. (1)
**Bank Muscat**

### 🇵🇰 Pakistan — PKR ₨ (1)
**Faysal Bank**

### 🇷🇺 Russia — RUB ₽ (1)
**T-Bank**

### 🇹🇷 Turkey — TRY ₺ (1)
**Enpara**
<!-- SUPPORTED_BANKS:END -->

Need another bank? See [`docs/adding-bank-parsers.md`](docs/adding-bank-parsers.md).

## Privacy

Parsing, storage and analysis happen on the phone. There are no accounts, no
analytics, crash-reporting or telemetry SDKs. The database stays in the app's private
storage; saved statements and backups are encrypted (see below). Network use is limited to:

1. **Gmail API** *(only if you connect it)* — read-only (`gmail.readonly`), searching for
   statement PDFs from the last 3 months and downloading those. The access token is kept
   in memory only.
2. **Exchange rates** — `open.er-api.com`, only when you use more than one currency or
   open the currency picker. No personal data is sent.
3. **Google Play services** *(Play-flavor build)* — Google's own SDKs for updates and
   reviews; they do their own connectivity to Google.
4. **Report a parsing problem** — only when you tap it. It opens the *original project's*
   web page (`pennywise.zynth.dev`) in your browser with the SMS text and sender you
   picked in the link, plus an on-device-encrypted device identifier. That site is run
   by someone else, not by this project: don't use the action on a message you
   wouldn't want them to see. (Removing it is a good first change if you'd rather it
   not exist; see `SmsReportUrlBuilder.kt`.)
5. **Links** open your browser.

Everything else stays on your phone. Backups never leave unless you share or save them,
and are unreadable without your passphrase. The database file itself is not separately
encrypted; Android's device encryption protects it while the phone is locked.

## Getting started

1. **Build and install:** [`docs/SETUP.md`](docs/SETUP.md) (prerequisites, `local.properties`,
   debug and release builds, signing keys, app ids).
2. **Gmail statement fetch (optional):** create your own Google Cloud project and
   Android OAuth client. Nothing is shared and the repo holds no credentials. Steps:
   [`docs/SETUP.md` §6](docs/SETUP.md#6-gmail-statement-fetch-create-your-own-oauth-client).
   Skip it and you can still import statement PDFs from storage.
3. **First run and verification:** grant the SMS permission, scan, then work through
   [`docs/ONBOARDING_AND_VERIFICATION.md`](docs/ONBOARDING_AND_VERIFICATION.md), a checklist
   for confirming the amounts match your banks before you trust them.
4. **Make a backup** (Settings → Export Data) and keep the passphrase somewhere safe.

The debug and release builds are **separate apps** (`com.moneytracker.app.debug` and
`com.moneytracker.app`) and do not share data; move between them with a backup.

## Repository layout

| Path | What it is |
|---|---|
| `app/` | The Android app: Jetpack Compose UI, Room database, ViewModels, WorkManager jobs |
| `parser-core/` | Pure-Kotlin bank SMS parsers (no Android dependencies, iOS-compatible) |
| `shared/` | Kotlin Multiplatform rules: category mapping, shared use cases |
| `iosApp/` | iOS client of the original project (not maintained in this fork) |
| `pennywise-web/`, `relay/` | The original project's website and AI-access relay (not used by this fork's app) |
| `docs/` | Architecture, design, backup format, migrations, parser guides, setup |
| `scripts/` | Release and maintenance scripts |
| `init.sh` | The verification gate: run it before calling anything done |

## Tech stack

Kotlin · Jetpack Compose + Material 3 · MVVM + Clean Architecture · Hilt · Room ·
WorkManager · Kotlin Multiplatform shared module · pure-Kotlin `parser-core`.
See [`docs/architecture.md`](docs/architecture.md).

## Contributing

Work on a branch, open a pull request into `main`, and keep `./init.sh app` green.
Every change set bumps `versionName` / `versionCode` and adds a changelog file (see
[`CLAUDE.md`](CLAUDE.md) for the rules). **Never put real financial data** (SMS,
balances, statements, names, account numbers, e-mail addresses) in code, tests, issues,
commits or screenshots: tests use invented values. To add a bank, see
[`docs/adding-bank-parsers.md`](docs/adding-bank-parsers.md). More in
[`CONTRIBUTING.md`](CONTRIBUTING.md).

This repo is set up for AI coding agents as well as people: [`CLAUDE.md`](CLAUDE.md) is
the agent guide (hard constraints, verification, topic docs), and
[`docs/FORK_HANDOFF.md`](docs/FORK_HANDOFF.md) explains what this fork changed and why.
In Claude Code, open the repo and say: *"Read CLAUDE.md and docs/FORK_HANDOFF.md, then
help me set up and verify on my phone."*

## License and attribution

[GNU AGPL v3](LICENSE). Money Tracker is derived from
[PennyWise AI](https://github.com/sarim2000/pennywiseai-tracker) by Sarim Ahmed and
contributors; the licence and copyright notices carry over, and all changes are also
AGPL. If you give someone a build, they are entitled to the source. See
[`NOTICE.md`](NOTICE.md) for the attribution and a summary of the modifications. The
original project's name, logo and artwork are not licensed for reuse
([`TRADEMARKS.md`](TRADEMARKS.md)); this fork does not use them.
