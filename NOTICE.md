# Notice

## Origin and licence

Money Tracker is a modified version of **PennyWise AI**
(<https://github.com/sarim2000/pennywiseai-tracker>), copyright © Sarim Ahmed and the
PennyWise AI contributors, released under the **GNU Affero General Public License v3.0**.

This work as a whole, including all modifications, is distributed under the same
licence: see [`LICENSE`](LICENSE). Copyright and attribution notices in individual source
files are preserved. If you give anyone a build of this app, or run it as a network
service, you must offer them the corresponding source (AGPL §6 and §13).

This project is **not affiliated with, sponsored by or endorsed by** the PennyWise AI
project or its author. "PennyWise" and its logo and artwork are the original author's
unregistered trademarks and are not licensed for use here ([`TRADEMARKS.md`](TRADEMARKS.md)).
They are mentioned only to say where this code comes from. The Kotlin packages were renamed
to `com.moneytracker.*`; some internal class names that were not worth a risky mass rename
(for example `PennyWiseDatabase`, `PennyWiseTheme`) and the database file name
`pennywise_database` still carry the old name. They are not shown to users.

## Statement of modifications (AGPL §5(a))

Modified in 2026 from the upstream project. The changes include, among others:

- Renamed to **Money Tracker**; new app id `com.moneytracker.app`; new launcher, in-app
  and notification icons; the original artwork, store listing text and screenshots were
  removed.
- Removed the on-device chat assistant and model download, all paid-tier ("Pro") upsell
  screens and the advertising-id permission; features formerly behind a paywall are
  unlocked.
- Added Gmail statement fetch (read-only), statement readers for several banks, saved
  encrypted statements, correct credit-card limit and balance handling, UPI-mandate hold
  handling, own-account payee rules, card-bill de-duplication and merchant grouping.
- Added encrypted passphrase backups with selectable contents (receipt photos, statement
  PDFs, card rewards, webhooks), card reward programs and cashback-cap tracking, refund
  links, and a stricter app lock.

For the detail see [`docs/FORK_HANDOFF.md`](docs/FORK_HANDOFF.md) and the per-version
changelogs in `fastlane/metadata/android/en-US/changelogs/`. This repository starts from
a single snapshot commit; the full history of the original project, with every
contributor credited, is at the upstream address above.

## Third-party

Third-party libraries keep their own licences. Bundled font and code licences are kept
next to the files they cover (for example under `pennywise-web/`).
