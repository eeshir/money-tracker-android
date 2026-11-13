# Privacy

**Last updated: November 2026**

Money Tracker is a community fork of PennyWise AI (see [`NOTICE.md`](NOTICE.md)). It is
**not affiliated with** the original project or its author. This project runs **no servers
and no accounts**, and it collects nothing. This page describes what the app does on your
phone and the few places it can talk to the network. If you build it yourself, you are
the only one who ever holds your data.

## Your data stays on your phone

- SMS are read **on the device**, parsed on the device, and stored in the app's private
  storage. There is no account to create and nothing is uploaded.
- There are **no analytics, advertising, crash-reporting or telemetry SDKs**, and the
  advertising-ID permission is removed.
- Android's `allowBackup` is **off**, so the app's data is not copied into Google's cloud
  backup. The database file is not separately encrypted; Android's device encryption
  protects it while the phone is locked.
- The optional **app lock** (biometric/PIN) locks the app on every cold start and
  whenever it is closed.

## Permissions

| Permission | Why |
|---|---|
| `READ_SMS`, `RECEIVE_SMS` | Reading bank transaction messages is how the app works. Read-only; the app never sends or deletes SMS. |
| `POST_NOTIFICATIONS` | Reminders, summaries and background-job status. |
| `READ_CONTACTS` *(optional)* | Only for the opt-in feature that turns a UPI address such as `9876543210@paytm` into a name from your address book. |
| `USE_BIOMETRIC` | The app lock. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | The limited network use listed below. |

If you would rather not grant the SMS permission, you can add transactions by hand or
import PDF statements.

## The only network use

1. **Gmail API (only if you connect it).** Read-only (`gmail.readonly`). The app searches
   for statement PDFs from the last 3 months and downloads them to your phone. The access
   token is kept in memory only. The Gmail connection uses an OAuth client in **your own**
   Google Cloud project, so no one else is involved; see [`docs/SETUP.md`](docs/SETUP.md).
2. **Exchange rates.** `open.er-api.com`, only when you use more than one currency or open
   the currency picker. No personal data is sent.
3. **Google Play services** *(the standard build)*. Google's own libraries, which do their
   own connectivity to Google and are covered by Google's privacy policy.
4. **Links** (user guide, issue tracker) open in your browser.
5. **Webhooks (optional, off by default).** If **you** create a webhook profile, the app
   sends the data types you choose to the URL you entered, and nowhere else.

Nothing else leaves your phone. In particular there is no in-app way to send an SMS to
anyone: to report a parsing problem, open an issue with an **invented** example.

## Backups

Backups are written only where you save or share them, and they are encrypted with your
passphrase (PBKDF2 + AES-GCM). They cannot be opened without it, and the app cannot recover
a forgotten passphrase. Saved statement PDFs on the phone are encrypted with a key kept in
the Android Keystore.

## Deleting your data

Uninstalling the app deletes its data. In the app you can delete individual transactions
or clear all of them (Settings). Backup files you made are yours to delete.

## Questions

Open an issue at <https://github.com/eeshir/money-tracker-android/issues>. Please never
include real SMS, balances or account numbers in an issue. For security problems see
[`SECURITY.md`](SECURITY.md).
