# Money Journal 1.3.0 — verification

Validated on 25 September 2026 on an Android 15 / API 35 emulator.

- All 16 automated tests passed: seven JVM tests and nine Android instrumentation tests. Eight instrumentation tests passed in the full run; the UI test passed after correcting its calendar date-label selector.
- The screen test creates an expenses-only profile, verifies income totals and choices are absent, selects a date from the calendar, and confirms the saved entry has that date. It also covers the existing entry, editing, deletion, recovery and theme flows.
- Database tests enforce expense-only mode, prevent disabling income while income records exist, and verify profile mode survives an encrypted backup. Migration and restore checks preserve existing profiles and accept backup versions 1, 2 and 3.
- Expenses-only PDF output was rendered and visually inspected; extracted text confirms the spending total and absence of income/remaining totals.
- Release build and lint passed: 0 errors, 36 warnings. APK signature verification and 16 KB ZIP alignment passed. The signing certificate matches version 1.2.0.
- Installed signed version 1.2.0, updated it in place to signed 1.3.0, and cold-launched successfully (605 ms).

The device coverage limits in the previous verification still apply.

---
# Money Journal 1.2.0 — verification

Validated on 7 September 2026 on Android 15 / API 35.

- All 15 automated tests passed: seven JVM tests and eight Android instrumentation tests. This includes the description-before-amount assertion, full entry/edit/delete/recovery flow, database isolation and migration, old/new backups, exports, and security checks.
- The new PDF test generated a one-page family statement, a four-page statement with 43 entries including a long note and the maximum individual amount, and an empty-month statement. Android PdfRenderer opened every page. All six pages were rendered with Poppler and visually inspected for alignment, wrapping, totals, headings, page breaks and footers. PDF text extraction confirmed sample totals and presence of the stress-test entries.
- `assembleRelease` and `lintRelease` passed (0 errors, 36 warnings). No suppression was added. The warnings are dependency/target, style and icon-resource suggestions.
- APK v2 signature and 16 KB ZIP alignment passed. The signing certificate matches Expenses 1.1.0.
- Installed Expenses 1.1.0, updated it in place to Money Journal 1.2.0, and cold-launched successfully (544 ms). Manifest label is Money Journal; application ID, encrypted database and backup formats stay compatible. Minimum Android version remains 8.0.
- New adaptive journal/arrows icon verified on the launcher. Renamed dashboard inspected in dark mode. The entry field order is verified by the UI test; the secure dialog blocks ordinary screenshot capture.
- A sample PDF with synthetic records was inspected during testing but is not committed. The icon master and generation prompt are in `artwork/money-journal-icon.png` and `artwork/money-journal-icon.md`.

The scope limits in the previous verification below still apply. No claim is made that every phone model has been tested.

---

# Verification record — Expenses 1.1.0

Validated on 7 September 2026 with JDK 17, Gradle 8.11.1 and an Android 15 / API 35 x86-64 emulator.

## Release checks

- `assembleRelease` succeeded with R8 code/resource shrinking.
- `lintRelease` succeeded: 0 errors, 35 warnings. Warnings concern dependency/target updates, maintainability and adaptive-icon lint suggestions; no warnings were suppressed for this update.
- APK Signature Scheme v2 verification passed. The signing certificate matches version 1.0.1.
- `zipalign -c -P 16 4` passed.
- Installed signed version 1.0.1 on the emulator, updated it in place to signed 1.1.0, and cold-launched successfully (716 ms).
- Manifest verified: version code 3, minimum API 26, target API 35; ARM64, ARMv7, x86 and x86-64 libraries included.
- Permissions remain biometric/fingerprint and an app-private signature permission only. No internet or broad storage permission.

## Automated tests — 14 passed

Seven JVM tests passed: income/expense/remaining calculations (including no income, no expenses and negative remaining), exact currency parsing, invalid inputs, calendar boundaries, CSV escaping, authenticated encryption and credential wrapping.

Seven Android tests passed:

1. UI flow: security setup, Expense/Income choice, expense entry, two income payments, income editing and totals, income filtering, both themes, wrong-password deletion rejection, locked-state data clearing, offline recovery, authenticated expense deletion and authenticated income deletion.
2. Database CRUD, month history, encryption, currency freezing and profile-scoped deletion.
3. Mixed income/expense encrypted-backup round trip, tamper rollback, CSV contents/totals, PDF signature and XLSX contents/structure.
4. Income filtering/search/date ordering, multiple payments, profile isolation, income-to-expense correction across months, invalid-type rejection and deletion totals.
5. Encrypted schema-1 database upgrade to schema 2: expenses/IDs/timestamps retained, salary converted once into same-month income, zero salary handled, budget columns removed, foreign keys valid, and currency locks retained.
6. Version-1 backup import with salary conversion; authenticated but malformed version-2 backup rolls back without replacing existing records.
7. Android Keystore credential/recovery operations and persisted authentication rate limiting.

The six database/security/compatibility Android tests passed in the initial run. The UI test was then corrected to scroll list items/action buttons into view and wait for deletion dialogs; its final separate run passed. Production code was unchanged during these test-harness corrections. No tests were skipped. The UI test resets only emulator test data and must not be used on a phone containing real records.

## Visual checks

Inspected the Expense/Income selection dialog and updated dashboard in both themes. The tested example shows income 85.00, expenses 12.34 and remaining 72.66. Budget and separate salary controls are absent. Test screenshots contained synthetic records and were kept as local release artifacts rather than committed. Screenshots were captured by the test with FLAG_SECURE temporarily cleared and then restored; the release app always enables it. The adaptive launcher icon from 1.0.1 is retained.

## Compatibility and limits

Positive legacy salary becomes an income entry dated the first day of its original month and labeled “Salary (from previous version)”. Zero/unset salary adds no entry. Budget settings are removed. Version-1 backups remain readable; new version-2 backups need this version or newer. Remaining is a monthly calculation and does not carry forward.

Runtime verification used Android 15, not every supported Android version or manufacturer. Physical biometric enrollment, manufacturer-specific behavior, full TalkBack/large-font acceptance, very-large-dataset performance and third-party report viewers still need broader device testing. No independent security audit was performed. The encrypted-backup restore limit remains 64 MB.
