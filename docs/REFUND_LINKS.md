# Refund Links — Plan, Rules and Examples

Written for co-developers and their Claude. Read this before touching refunds, totals, or anything that
sums expenses. Code lives in `data/refunds/RefundLinks.kt` (pure rules), `RefundLinkRepository`,
`presentation/refunds/*` and `utils/TransactionFilters.kt` (`countedAmount()`). Backup shape: `docs/backup-format.md`
("Refund links"). Shipped in v2.46.0 and refined through the changelogs for versionCodes 151+.

## The problem

A refund used to be treated as a plain credit: it landed in the month it arrived, and the original expense kept its
full cost in its own month. So a ₹10,000 purchase in October refunded in November showed October as ₹10,000 too
high and November as ₹10,000 "income". The refund also had no relation to *which* purchase it paid back, so
Analytics and category totals were wrong, and a refund that was only part of a purchase could not be expressed.

## The model

- A **refund** is an `INCOME` transaction with `budgetImpactType = DEDUCT_SPENT` and no category of its own
  (`RefundLinks.isLinkManaged`). Its effect on totals comes **only from its links**.
- A **link** (`RefundAllocationEntity`, table `refund_allocations`) puts `amount` of a refund on one expense, or on
  one category part of a *split* expense (`category`; `null` = the whole expense).
- The part of a refund not linked anywhere is its **floater**. A floater counts for nothing: not income, not a
  reduction of spending. Linking it is what makes it count.
- **Net cost shows in the expense's own month.** Linking a November refund to an October purchase lowers October's
  spend; November is unchanged.
- The allocations table is the source of truth. `TransactionEntity.refundedTotal` and
  `TransactionSplitEntity.refundedAmount` are stored copies so every total can read them cheaply:
  `countedAmount()` subtracts them. They are rebuilt by `RefundLinks.stored(...)` whenever links change and after
  a backup restore. Do not write them by hand.

## Rules (enforced in `RefundLinks.plan`)

1. An expense can never be refunded for more than it cost. Requests are cut down to the room left
   (`Problem.CutToRoom`).
2. A refund can never put out more than its own amount (`Problem.OverRefund`).
3. On a split expense, room is tracked per category *and* for the whole: a category part can't take more than its
   split amount, and the parts together can't take more than the expense.
4. Same currency only (`expense.currency == refund.currency`). Never mix currencies (Hard Constraint 2).
5. Not linkable: deleted or excluded-from-analytics expenses, the refund itself, loan-linked expenses
   (`loanId != null`) and shared-bill expenses (`lentSharesTotal != null`), because another rule already cuts
   their counted amount. Only `EXPENSE` and `CREDIT` transactions can be paid back.
6. Floater is never negative, even if the refund was edited down below what it had linked.
7. Requests are applied in the order given; the first ones win if the refund runs out.

## Worked examples

Amounts are made up.

**A. Whole refund of one purchase.** Oct 5: shoes ₹4,000 (Shopping). Nov 2: refund ₹4,000 (Refund).
Link ₹4,000 refund → shoes. October spend for the shoes becomes ₹0 (`refundedTotal` = 4,000);
November shows no income from it. Floater = ₹0, so the Home refund card hides.

**B. Partial refund.** Oct 5: shoes ₹4,000. Nov 2: refund ₹1,500 (one item returned).
Link ₹1,500 → shoes. October counts ₹2,500. Floater ₹0. The list row says "₹1,500 paid back".

**C. One refund, two purchases.** Refund ₹5,000. Request 1: ₹3,000 on a ₹3,000 order. Request 2: ₹2,000 on a
₹6,000 order. Both accepted; floater ₹0.

**D. Over-ask is cut.** Refund ₹1,000, request ₹1,500 on a ₹4,000 expense. `plan` accepts ₹1,000 and reports
`OverRefund(asked = 1,500, given = 1,000)`. If the expense already had ₹3,500 refunded by another refund and the
request is ₹1,000, it is cut to ₹500 (`CutToRoom`).

**E. Split expense, per category part.** Oct 10: ₹10,000 split Groceries ₹7,000 + Household ₹3,000.
Refund ₹2,000 for household goods: link `category = "Household"`, ₹2,000. Household's split shows
`refundedAmount` = 2,000 and the category total falls by 2,000; Groceries is untouched. Asking for ₹3,500 on
Household would be cut to ₹3,000 (the part's capacity).

**F. Floater.** Refund ₹800 arrives, nothing linked yet. It counts for nothing and shows on the Home refund card
and as "Floater ₹800" on its list row. Tap the card to open the link sheet directly.

**G. Edit after linking.** Expense edited down from ₹4,000 to ₹3,000 with ₹3,500 linked: `stored()` clamps
`refundedTotal` to ₹3,000, so it never goes below zero spend. A link to a category that no longer exists still
counts toward the total, just not toward any split.

## Where it appears in the UI

- **From the refund:** transaction detail → `RefundLinkSection` / `RefundLinkSheet` (pick expenses, enter amounts).
  Linking an unsaved Refund works straight away and is reverted if the edit is abandoned.
- **From the expense:** expense page → `ExpenseRefundLinkSheet` (link a refund, per part for splits).
- **Home:** `RefundFloaterCard` lists refunds with a floater; hides when there are none.
- **List rows:** "₹X paid back" / "Floater ₹X" / "linked".
- **Totals:** `TransactionTotalsCard`, Analytics, category and merchant totals all read net-of-refund amounts via
  `countedAmount()`.

## Related change in the same branch

Transactions totals: card purchases count in Expenses and Net for the month they were made when "count credit card
as expense" is on (the default), and sums are exact. This matters for refunds because a refund to a card nets
against a card purchase in that purchase's month.

## Backup and restore

`refund_allocations` is a defaulted list in `DatabaseSnapshot` (old backups decode to no links). Both transaction
ids are remapped on restore; a link is skipped if either end did not restore, the expense can't be linked, or it
would exceed the expense. Exported in FULL backups only, and only when both ends are in the backup. Stored copies
are rebuilt after import.

## Tests

- `RefundLinksTest` — rules (`plan`, `floater`, `canLink`, `stored`).
- `RefundTotalsTest` — totals across months and splits.
- `ExpenseLinkStateTest` — expense-side sheet state.
- `RefundLinkBackupTest` — old-shaped JSON decodes with defaults; round trip.
- `RefundLinkDatabaseTest` — instrumented; run on the `Api26_Migration` AVD.

## Guidance for changes

- Any new place that sums expenses must go through `countedAmount()`; otherwise refunds will silently not net.
- Keep `RefundLinks` pure (no database). Put persistence in `RefundLinkRepository`.
- New fields on the allocation entity need Kotlin defaults (Hard Constraint 3) and a backup regression test.
- Don't put real amounts, merchants or names in tests or docs (Hard Constraint 4).

## Open questions / not done

- Cross-currency refunds are deliberately not linkable; a converted-link design would need the unified-currency work
  in `docs/prd-unified-currency.md`.
- Loan-linked and shared-bill expenses can't be refunded through links yet.
