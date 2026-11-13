# Setup guide

Everything you need to build Money Tracker, put it on your phone, set up Gmail statement
fetch with **your own** Google credentials, and move your data between installs.

Nothing in this repository is secret. There are no API keys, no `google-services.json`
and no signing keys in it. Everything personal — your keystore, your OAuth client, your
data — is created by you and stays on your machine and phone.

- [1. Prerequisites](#1-prerequisites)
- [2. Get the code and verify the build](#2-get-the-code-and-verify-the-build)
- [3. App ids and build variants](#3-app-ids-and-build-variants)
- [4. Install a debug build](#4-install-a-debug-build)
- [5. Build a release (signed) app](#5-build-a-release-signed-app)
- [6. Gmail statement fetch: create your own OAuth client](#6-gmail-statement-fetch-create-your-own-oauth-client)
- [7. First run on the phone](#7-first-run-on-the-phone)
- [8. Backup, restore and moving between installs](#8-backup-restore-and-moving-between-installs)
- [9. Make it your own (rename, new app id)](#9-make-it-your-own-rename-new-app-id)
- [10. Troubleshooting](#10-troubleshooting)

## 1. Prerequisites

| Need | Version / note |
|---|---|
| JDK | **21** (Android Studio's bundled JBR works). A JDK 17 or 11 fails with cryptic errors. |
| Android Studio | Current stable, with **Android SDK 37** (compile) and platform-tools. |
| Phone | Android 8.0+ (API 26+), USB debugging on (Settings → Developer options). |
| Git | Any recent version. |
| Windows only | Use Git Bash for the scripts. Prefix `adb` shell paths with `MSYS_NO_PATHCONV=1`. |

Check the phone is seen: `adb devices` must list it as `device` (not `unauthorized`).

## 2. Get the code and verify the build

```bash
git clone https://github.com/eeshir/money-tracker-android.git
cd money-tracker-android
```

Create `local.properties` in the repo root (it is git-ignored) with your SDK path:

```properties
# macOS / Linux
sdk.dir=/Users/<you>/Library/Android/sdk
# Windows (note the escaped backslashes)
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

Run the verification gate. It is the same compile + unit-test check CI uses, and it
finds a JDK 21 for you (`JAVA_HOME`, macOS `java_home`, Android Studio's JBR, SDKMAN):

```bash
./init.sh app        # app compile + unit tests (what you run before every change)
./init.sh parser     # parser-core only (fast, for SMS parser work)
./init.sh            # everything
```

It must pass before you change anything. The first run downloads Gradle and
dependencies and takes several minutes.

## 3. App ids and build variants

| Variant | App id | Launcher name | Icon |
|---|---|---|---|
| `standardRelease` | `com.moneytracker.app` | Money Tracker | green, gold coin |
| `standardDebug` | `com.moneytracker.app.debug` | Money Tracker Debug | orange, silver coin |
| `fdroidRelease` / `fdroidDebug` | same ids | same | same (no Google sign-in, so no Gmail fetch). The F-Droid release is left unsigned, because F-Droid signs it itself. |

The **debug and release apps are separate apps on the phone.** They do not share data.
That is deliberate (you can test on debug while keeping real data on release), but it
means moving from one to the other is a backup and restore (section 8).

Use the **standard** flavor unless you specifically need an F-Droid build. Only
`standard` has Gmail statement fetch (it uses Google Play services).

## 4. Install a debug build

```bash
./gradlew :app:installStandardDebug
```

or build the APK and install it yourself:

```bash
./gradlew :app:assembleStandardDebug
adb install -r app/build/outputs/apk/standard/debug/app-standard-arm64-v8a-debug.apk
```

`-r` replaces an installed copy **and keeps its data**, as long as it is signed with the
same key. Debug builds are signed with your machine's debug keystore
(`~/.android/debug.keystore`), so a debug app built on another machine has a different
signature and cannot be installed over yours.

## 5. Build a release (signed) app

A release APK must be signed or Android refuses to install it. Make a keystore **once**
and keep it forever.

```bash
# 1) Create the keystore (outside the repo). Pick a strong password and keep it safe.
keytool -genkeypair -keystore ~/money-tracker-release.jks -storetype PKCS12 \
  -alias money-tracker -keyalg RSA -keysize 2048 -validity 10000
```

```properties
# 2) Add to local.properties (git-ignored). Never commit these.
RELEASE_STORE_FILE=/absolute/path/to/money-tracker-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=money-tracker
RELEASE_KEY_PASSWORD=...
```

```bash
# 3) Build and install
./gradlew :app:assembleStandardRelease
adb install -r app/build/outputs/apk/standard/release/app-standard-arm64-v8a-release.apk
```

> **Back up the keystore and its password** (a password manager or private cloud
> storage). If you lose it, you can never update the installed release app again: you
> would have to uninstall it (losing its data unless you made a backup) and install a
> newly signed one. A different keystore also needs a different Gmail OAuth client
> (section 6).

Without the four `RELEASE_*` lines the release build is produced **unsigned** and will
not install. To check which key signed an APK:

```bash
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs <apk>
```

Use the `arm64-v8a` APK on nearly every modern phone; `universal` works on any.

## 6. Gmail statement fetch: create your own OAuth client

The app can fetch your card and account statement PDFs from Gmail (read-only) and import
them. This is optional. Without it you can still import PDFs from storage
(**Import Statement → Select PDF**).

The app asks Google, on the phone, for a read-only token (`gmail.readonly`). Google only
hands one out to an app it recognises, so **you create an OAuth client in your own Google
Cloud project** that names your app id and signing key. Nothing about this lives in the
repository, and nobody else's data or credentials are involved.

### 6.1 Get the SHA-1 of your signing key

```bash
# Debug key (the standard Android debug keystore)
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA1

# Release key (your keystore from section 5)
keytool -list -v -keystore ~/money-tracker-release.jks | grep SHA1
```

`./gradlew :app:signingReport` prints the debug one as well.

### 6.2 Create the project and consent screen

1. Open the [Google Cloud console](https://console.cloud.google.com/) and **create a project**
   (any name).
2. **APIs & Services → Library →** enable the **Gmail API**.
3. **APIs & Services → OAuth consent screen** (shown as *Google Auth platform* in newer
   consoles). Choose **External**, fill in the app name and your email, and add the scope
   `https://www.googleapis.com/auth/gmail.readonly`.
4. Leave the publishing status on **Testing** and add **your own Google account** (and
   anyone else who will use your build) under **Test users**.

### 6.3 Create the Android OAuth client(s)

**APIs & Services → Credentials → Create credentials → OAuth client ID → Android.**
You need **one client per app id + key pair**:

| Client | Package name | SHA-1 |
|---|---|---|
| Release | `com.moneytracker.app` | your release keystore's SHA-1 |
| Debug | `com.moneytracker.app.debug` | your debug keystore's SHA-1 |

Changes can take from a few minutes to a few hours to apply.

### 6.4 Good to know

- While the project is in **Testing**, Google expires consent about **weekly**. The app
  posts a notification when it needs your approval again. Publishing the app to
  Production would need Google's verification for a Gmail scope, which is not worth it
  for personal use.
- Only accounts listed as **test users** can sign in. A different Google account gets a
  "this app is blocked / not verified" style error.
- Anyone who builds the app needs their **own** client (their app id, their key's SHA-1).
  Your client works only for builds signed with the key whose SHA-1 you registered.
- The access token is kept in memory only, and the app only searches for statement PDFs
  from the last 3 months. See the privacy section of the README.
- If you change the app id or the signing key, add a new client for the new pair.

## 7. First run on the phone

1. Open the app, finish onboarding, and grant **SMS** permission (and notifications).
   The SMS permission is read-only and is the whole point of the app.
2. Scan SMS (about the last 3 months). Wait for it to finish and note the totals.
3. **Accounts → Manage accounts**: check each account/card's type and name. Set a
   starting balance only where the app has none.
4. **Statements:** *Import Statement → Gmail → Fetch and import everything now* (needs
   section 6), or pick PDFs. Enter a statement password once; it is remembered
   (encrypted) per bank and card. A bank with no statement reader shows as unsupported.
5. **Own accounts:** add own-payee rules for anything that moves money between your own
   accounts or pays your own card.
6. **Settings → Backup passphrase:** choose one and store it in a password manager.
7. Then follow [`ONBOARDING_AND_VERIFICATION.md`](ONBOARDING_AND_VERIFICATION.md) to confirm
   the amounts match your banks before you trust them.

## 8. Backup, restore and moving between installs

Backups are always encrypted with your **passphrase** (PBKDF2 + AES-GCM). The app cannot
show you the passphrase, and a backup cannot be opened without it. **Write it down.**

**Make a backup:** *Settings → Export Data*. A dialog asks what to include. Transactions,
accounts, budgets, categories, loans, goals and your settings are always included. You
can tick or untick:

| Option | What it adds | Note |
|---|---|---|
| Receipt photos | Photos attached to transactions | Larger file |
| Saved statement PDFs | The fetched/imported statements (still locked by the bank's own password) | Larger file; they are re-encrypted with the new phone's key on restore, and are not imported twice |
| Card rewards | Reward rates and caps per card, rates set on single purchases | |
| Webhooks | Webhook URLs and headers, what was already sent, logs | Headers can hold a token; the backup is encrypted |

Your ticks are remembered. *Automatic Folder Backup* has its own *Choose what to
include* row, so the daily backup can be smaller than a manual one.

**Restore:** *Settings → Import Data*, pick the file, enter the passphrase.

**Moving to a new phone, or from debug to release:**

1. On the old install: set a passphrase and *Export Data*. Save the file somewhere you
   can reach (Drive, Downloads, e-mail it to yourself).
2. Install the new app.
3. In the new app: *Import Data*, choose the file, enter the passphrase.
4. Check balances, card rewards and saved statements before deleting the old install.

The format, compatibility rules and what each section does on restore are in
[`backup-format.md`](backup-format.md).

## 9. Make it your own (rename, new app id)

To publish your own variant under your own name:

1. Change `applicationId` in `app/build.gradle.kts` (debug adds `.debug` automatically).
   `namespace` (the Kotlin package, `com.moneytracker.app`) does not have to match it; you can
   leave it, which avoids a mass rename. Do not rename the database file.
2. Change `app_name` in `app/src/main/res/values/strings.xml` (debug has its own in
   `app/src/debug/res/values/strings.xml`).
3. Replace the launcher icon: `app/src/main/res/drawable/ic_money_*.xml` (release) and
   `app/src/debug/res/drawable/ic_money_*.xml` (debug).
4. Create a new Gmail OAuth client for the new id (section 6.3).
5. Bump `versionName` / `versionCode` and add a changelog file (see
   [`../CLAUDE.md`](../CLAUDE.md)).

The code is **AGPL-3.0**: if you give anyone a build, they are entitled to its source,
and your changes must stay AGPL. Keep [`../NOTICE.md`](../NOTICE.md) and the licence.
The original project's name, logo and artwork are **not** licensed for reuse
([`../TRADEMARKS.md`](../TRADEMARKS.md)): do not use them for your own variant.

## 10. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `adb devices` shows nothing or `unauthorized` | Re-plug the cable, accept the prompt on the phone; retry (USB drops now and then). |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | The installed copy is signed with a different key. Uninstall it (back up first) or install under another app id. |
| Release APK "not installed" / parse error | It is unsigned: add the `RELEASE_*` lines (section 5). |
| Gradle fails with a Java version error | Use JDK 21 (`./init.sh` picks one for you). |
| Gmail: sign-in refused / "invalid" client | The OAuth client's package name or SHA-1 does not match the installed build (section 6.1 and 6.3), your account is not a test user, or the change has not propagated yet. |
| Gmail worked, then stopped | Weekly consent expiry in Testing mode: approve again when the app asks. |
| SHA-1 field says invalid in the console | Paste exactly 20 colon-separated pairs, no spaces, and use the key for *that* package (debug vs release). |
| Restore asks for a passphrase you do not remember | There is no recovery. The backup cannot be opened without it. |
| Old and new app both on the phone | Different app ids are different apps. Delete the old one after you have verified the restore. |

Handy commands:

```bash
./init.sh app                                   # compile + unit tests (the gate)
./gradlew :app:installStandardDebug             # build + install debug
./gradlew :app:signingReport                    # SHA-1 of the debug key
MSYS_NO_PATHCONV=1 adb shell uiautomator dump /sdcard/ui.xml   # read the screen as text
```
