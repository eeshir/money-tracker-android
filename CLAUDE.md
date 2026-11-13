# Money Tracker — Agent Guide

Money Tracker is a privacy-first expense tracker that extracts transactions from
bank SMS **entirely on-device**. The primary app is Android (Jetpack Compose +
Material 3); the repo also contains a Kotlin Multiplatform `shared` module, an
`iosApp`, and the original project's `pennywise-web` / `relay` (not used by this
fork's app). It is a fork of PennyWise AI (AGPL-3.0, see `NOTICE.md`): the
**PennyWise name, logo and artwork must not be used** here (`TRADEMARKS.md`). Packages are
`com.moneytracker.app` (app), `com.moneytracker.parser` (parser-core) and
`com.moneytracker.shared`; a few classes still say `PennyWise…` (e.g. `PennyWiseDatabase`)
and the DB file is `pennywise_database` — do not rename the DB file (it would orphan data).

**This file is a router, not a manual.** Read the linked topic doc when a task
touches its area. Everything under **Hard Constraints** is non-negotiable.

**Apply judgment to feature/bug requests — an issue is input, not a spec.** When
an issue lists several asks, evaluate each on its own merit: ship the clear wins,
and leave out (with a short note on the issue/PR saying why) the parts that
reshape a core figure/flow or aren't clearly worth it. Don't implement everything
an issue lists by default — bias to the smallest change that solves the real
complaint.

## This is a fork — start here
This is **Money Tracker**, a community fork of PennyWise AI. Setup order for a new
person (or a new Claude session with no earlier context):
1. `docs/SETUP.md` — prerequisites, `local.properties`, debug/release builds, signing,
   app ids, **Gmail OAuth with your own Google Cloud client**, backup/restore.
2. `docs/FORK_HANDOFF.md` — what the fork added, decisions, open items, gotchas.
3. `docs/ONBOARDING_AND_VERIFICATION.md` — first run, and the checklist to verify the
   amounts on your own phone before trusting them.

Real financial data lives only on phones and in git-ignored `local-notes/` — never in
the repo, prompts you share, issues, or commits. Nothing in the repo is a credential:
keystores, `RELEASE_*` signing values, OAuth clients and backups are yours, created
locally (`local.properties` is git-ignored) — never commit them.

**Quick facts** (verify against `app/build.gradle.kts` before relying on them):
- App ids: release `com.moneytracker.app`, debug `com.moneytracker.app.debug`. They
  are different apps with separate data; moving between them is a backup + restore.
- Flavors: `standard` (Google Play services, Gmail fetch) and `fdroid` (no Google).
  Build/install: `./gradlew :app:installStandardDebug`.
- Release signing reads `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`,
  `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` from `local.properties`; unsigned if absent.
- Gmail fetch works only if the build's app id + signing SHA-1 are registered as an
  Android OAuth client in the person's own Google Cloud project (see `docs/SETUP.md` §6).
- Backup contents are selectable (`BackupOption`: receipts, statements, rewards,
  webhooks; `data/backup/BackupOptions.kt`, `BackupExtras.kt`). A new optional backup
  section goes through `BackupExtras`, gets a defaulted wrapper model in
  `BackupModels.kt`, a `BackupOption` and a string in the dialog
  (`ui/screens/settings/BackupContentDialog.kt`) — and a note in `docs/backup-format.md`.
- Launcher icons are vector drawables (`ic_money_bg/fg/mono`); the debug build overrides
  bg/fg in `app/src/debug/res/drawable`. In-app logo: `ic_money_logo`; notification
  icon: `ic_stat_money` (must stay one flat colour).

## Hard Constraints (never violate)
1. **Money is always currency-tagged.** Format with
   `CurrencyFormatter.formatCurrency(amount, currencyCode)` (or the
   `formatBalance()` / `formatAmount()` entity extensions) — never the
   currency-less overload (it defaults to ₹ and is `@Deprecated(ERROR)`, so it
   won't compile).
2. **Never sum amounts across currencies.** "₹500 + $10" is not 510 of anything.
   Total a possibly-mixed list with `Iterable.sumByCurrency({ it.currency },
   { it.amount })` (`utils/MoneyTotals.kt`) → `Map<currency, Money>`, and render
   with `CurrencyFormatter.formatByCurrency(...)`. `Money.+` throws on a currency
   mismatch. A single-figure total is only valid after filtering/converting to
   one currency. (Both rules guarded by `CurrencyFormatterTest`.)
3. **Every backup-serialized field must have a Kotlin default value** — Room
   entity columns *and* the wrapper models in `BackupModels.kt`. Dropping a
   default re-introduces the "can't restore old backup" bug (#414);
   `BackupSchemaGuardTest` enforces this. Use `@Contextual` for
   `BigDecimal`/`LocalDate`/`LocalDateTime`, `@Serializable` for enums. See
   `docs/backup-format.md` or the **backup-maintainer** subagent before touching
   entities or `data/backup/`.
4. **Never put PII in code, comments, tests, or anywhere.**
5. **Every change ships with a changelog entry and a version bump (this fork).**
   Work on a feature branch. Per change set: bump `versionName` (MINOR for
   features, PATCH for fixes) and `versionCode` (+1) in `app/build.gradle.kts`,
   and add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (short
   `•` bullets, under 500 characters). Do this by hand; don't run
   `scripts/release.sh` for ordinary commits (see Releasing).
6. **Parser tests use the shared `ParserTestUtils` helpers** — see
   `docs/parser-test-standards.md`.
7. **New bank parsers** extend the correct base class (`BaseIndianBankParser` /
   `UAEBankParser` / `BankParser`) and are registered in `BankParserFactory` —
   see `docs/adding-bank-parsers.md`.
8. **Migration SQL must run on the oldest SQLite we support.** minSdk 26 ships
   SQLite 3.18; Android 13 ships 3.32. The JVM and our new-Android emulators
   are newer and accept syntax old phones reject: 2.22.0's `HAVING` without
   `GROUP BY` (3.39+) crashed every launch on Android ≤13. Before merging or
   releasing a migration, run the update path on the `Api26_Migration` AVD —
   see `docs/database-migrations.md`.

## Verification — Definition of Done
Run **`./init.sh`** before claiming a change is done — it runs the same
compile/test gate CI applies (CI's App Compile job additionally assembles a
debug APK, so a change that compiles but breaks assembly will still surface
there). Scope it to what you touched:

| Change | Command |
|---|---|
| Parser (`parser-core`) | `./init.sh parser` |
| App (Kotlin) | `./init.sh app` |
| Everything | `./init.sh` (default) |
| Runtime / UI | plus an on-device check (use the **emulator-verify** skill) |
| DB migration | plus the old-Android update check in `docs/database-migrations.md` |

`init.sh` sets `JAVA_HOME` to a JDK 21 (it tries `JAVA_HOME`, macOS `java_home`,
Android Studio's JBR, then SDKMAN). Run the raw `./gradlew` tasks it wraps only
if you need finer control. `./gradlew build` and `lint` are heavier and **not**
part of the gate — don't use them to verify.

## Architecture at a glance
- **Stack:** Kotlin · Jetpack Compose + Material 3 · MVVM + Clean Architecture ·
  StateFlow (unidirectional data flow) · Hilt DI · Room · WorkManager (background
  SMS scanning) · Google AI Edge LiteRT-LM (Qwen 2.5) for on-device AI.
- **Modules:** `app` (Android) · `parser-core` (pure-Kotlin bank SMS parsers, no
  Android deps) · `shared` (KMP) · `iosApp` · `pennywise-web`.
- **UI:** Material You dynamic color (Android 12+), full light/dark with semantic
  color roles, 8dp grid, Material 3 type scale. All screens use
  `PennyWiseScaffold` for consistent system-bar / TopAppBar handling. Always
  check both light and dark themes.
- **Versioning:** SemVer (MAJOR breaking / MINOR features / PATCH fixes). The
  live version is in `app/build.gradle.kts`.

## Topic Docs — read when the task touches the area
| Area | Doc | Read when |
|---|---|---|
| Setup, signing, Gmail OAuth, backup/restore for users | `docs/SETUP.md` | Build/install/signing/OAuth questions; changing app id or name |
| Architecture / layers | `docs/architecture.md` | Changing layer responsibilities, DI, data flow |
| Design system | `docs/design.md` | Any UI work — colors, typography, components |
| State management | `docs/state-management.md` | ViewModels, StateFlow, UI state |
| Backup & Restore | `docs/backup-format.md` | Touching backed-up entities or `data/backup/` |
| Adding a bank parser | `docs/adding-bank-parsers.md` | New/changed parser in `parser-core` |
| Parser tests | `docs/parser-test-standards.md` | Writing parser tests |
| DB migrations | `docs/database-migrations.md` | Room schema changes |
| Supported banks | `docs/BANK_SUPPORT.md`, `docs/supported-banks.json` | Which banks/patterns are covered (144 banks, 23 countries) |
| AI access (MCP relay, dashboard) | `docs/ai-access.md`, `relay/README.md`, `docs/adr/0002-*` | Touching `aiaccess/`, the relay or the dashboard |
| Refund links | `docs/REFUND_LINKS.md` | Refunds, floaters, `countedAmount()`, anything that sums expenses |
| Roadmap / requirements | `docs/planned-features.md`, `docs/prd-unified-currency.md` | Feature scope / intent |

**Subagents:** `backup-maintainer` (backup-serialized changes) ·
`parser-author` (write/fix a parser end-to-end) · `parser-triage` (assess a
fresh `[BANK]` issue).

## Releasing
In this fork, versions and changelogs are bumped by hand with each feature branch (Hard Constraint 5); `release.sh` is only for cutting a published release. For a routine release: merge the PRs, then — from an up-to-date `main` with a
clean tree — run `./scripts/release.sh patch|minor|major --yes` (preview first
with `--dry-run`). It commits/tags the current branch and pushes `main`, so the
branch state matters.
`scripts/release.sh` is the single source of truth — it bumps the version,
generates changelogs + release notes, builds the APKs (standard flavor signed;
the F-Droid APK stays unsigned for IzzyOnDroid), tags, pushes, and cuts the
GitHub release. Full flag reference in `docs/RELEASE.md`.
`--web` needs Docker (OrbStack) running and `bun install` done in
`pennywise-web/` — a fresh worktree has no `node_modules`.
