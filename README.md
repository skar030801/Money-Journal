# Money Journal

A native Android app for recording income and expenses. Money Journal works offline, stores its database encrypted on the device, supports multiple profiles, and can move data between phones through password-protected backups.

Current version: **1.3.0**. The app was previously named Expenses. It keeps the same Android application ID so existing users can install Money Journal as an update without losing their records or security settings.

## Features

- Record expenses, income, dates, and optional descriptions.
- Create profiles that track both income and expenses or expenses only.
- Review monthly totals, history, search results, and insights.
- Export readable CSV, XLSX, and paginated PDF reports.
- Protect local data with a PIN/password, optional biometrics, and an offline recovery code.
- Create encrypted backups for transfer to another Android phone.
- Use the app without an account, ads, analytics, or internet access.

## Profile modes and calendar dates

Choose Income & expenses or Expenses only when creating or editing a profile. Expenses-only profiles open Add expense directly and omit income/remaining totals from the dashboard, history, insights and reports. Income can be enabled later. Switching to expenses-only is blocked while income entries exist. Existing profiles and older backups default to both modes enabled.

Tap the date button in Add/Edit entry to open the calendar, choose a day and tap Use date. Cancel leaves the current date unchanged. The selected date determines the entry month. Calendar dates are converted using UTC day counts to prevent time-zone shifts.

## Install

Download the signed APK from this repository's **Releases** page, copy it to your Android phone, open it, and allow installation from your chosen file manager when Android asks. Android 8.0 (API 26) or newer is required. ARM 32-bit, ARM 64-bit, x86, and x86-64 devices are supported. This is an Android phone/tablet app.

1. Create a PIN of at least six digits or a password of at least ten characters.
2. Write down the generated offline recovery code and keep it separately from your phone.
3. Create a profile, choose whether it tracks **Income & expenses** or **Expenses only**, and select its currency. INR is the default; another ISO currency code can be selected before entering financial data.
4. Tap **Add entry** and enter the description, date, and amount. Profiles that track both types ask whether the entry is an expense or income.

The dashboard shows total income, total expenses and **Remaining = Income − Expenses** for the selected month. A negative remaining amount means recorded expenses exceed recorded income. There is no budget or separate salary setting. Monthly remaining amounts do not carry forward and are not a bank-account balance. The Entries screen can filter income, expenses or both; entries remain editable and require app authentication for permanent deletion.

## Updating from version 1.0 / 1.0.1

Install the new APK over the existing app without uninstalling it. Existing expense records and security settings are retained. Each positive monthly salary becomes an income entry named “Salary (from previous version)” in that month. Because the old salary had no payment date, the converted entry uses the first day of the month; you can edit it. Zero/unset salary adds no income entry. Budget settings are removed. Encrypted backups from version 1 remain restorable with the same conversion. Version 3 backups require Money Journal 1.3.0 or newer and cannot be restored by older versions.

The app locks when it leaves the foreground, including when you use the Android document picker. Unlock again to finish saving or restoring the selected file. Screenshots and recent-app previews are protected.

## Move to another phone

Open **Settings → Save encrypted backup**. Choose a destination and a separate password of 12–128 characters. Transfer the `.expensesbackup` file yourself. On the new phone, install Money Journal, complete security setup, then select **Settings → Restore encrypted backup**. Enter the backup password and the new phone's app PIN/password, and confirm replacement.

Backups include every profile, month, income and expense. Restore replaces current financial data atomically; malformed or unauthenticated backups are rejected. App PIN, biometrics and recovery code stay specific to the receiving installation. Restore supports backup files up to 64 MB. CSV, PDF and XLSX reports include income/expense types and monthly totals; they are readable reports, not restorable backups.

Keep backups: uninstalling, clearing app storage, losing the phone or losing Android Keystore keys can make local data unavailable. The recovery code resets the application credential on the original installation; it cannot recover a lost phone or reset a backup password.

## Build in Android Studio

Open this project directory. Use JDK 17, Android SDK platform 35 and build tools 35. Gradle 8.11.1 is pinned by the wrapper; Android Gradle Plugin is 8.9.1 and Kotlin is 2.1.10. Internet is needed to download build dependencies on a new development machine; the installed app itself has no internet permission.

```text
gradlew.bat assembleDebug testDebugUnitTest
gradlew.bat connectedDebugAndroidTest
gradlew.bat assembleRelease lintRelease
```

On macOS/Linux use `./gradlew` (enable executable permission if needed). Android Studio supplies the SDK path in `local.properties`; this machine-specific file is ignored by Git.

Release signing reads the local `signing.properties` file:

```properties
storeFile=expenses-release.jks
storePassword=YOUR_PRIVATE_STORE_PASSWORD
keyAlias=expenses
keyPassword=YOUR_PRIVATE_KEY_PASSWORD
```

The generated signing key and password file are excluded by `.gitignore`. Never commit or publish them. Keep both private and backed up: future APK updates must use the same key. Without local signing configuration, release builds are unsigned; debug builds remain installable for development.

## Structure

- `Money.kt`: exact minor-unit parsing, formatting and centralized financial summaries.
- `Database.kt`: Room entities, composite profile/month relationship, constraints, triggers, indexed queries and transactional repository operations.
- `Security.kt`: password and recovery wrapping, Android Keystore protection, rate limiting and biometric cryptographic operations.
- `ExpensesViewModel.kt`: session lifecycle, serialized background operations and screen state.
- `Exports.kt`: streaming CSV/XLSX generation and versioned authenticated backups.
- `PdfReport.kt`: paginated A4 PDF statements.
- `Ui.kt`, `MainActivity.kt`: Compose/Material 3 screens, app lifecycle and Android document/biometric integration.
- `src/test`, `src/androidTest`: calculation, cryptography, database, backup/export and application-flow tests.

Currency is frozen after financial data has first been entered, even if it is later removed. Entry dates select their owning month, including when editing moves income or an expense into a different month. Amounts must be greater than zero. Money accepts the selected currency's number of fraction digits and rejects rounding or excess precision. Each amount is limited to 9 trillion minor units.

History and entries load bounded pages; exports query batches. Description search is case-insensitive for SQLite's supported case folding and treats search punctuation literally. Theme persists between launches. An unfinished entry is encrypted as a draft; reopen the same Add/Edit form after unlocking to continue it.

## Verification and limitations

See `VERIFICATION.md` for actual build and test results. A successful build and emulator tests do not constitute an independent security audit or testing on every manufacturer's hardware. Real-device fingerprint/face behavior and accessibility with assistive technology should be checked on your target phones before public distribution.

Database schema and new archive format are version 3. Explicit Room migrations upgrade schemas 1 and 2, and the backup reader accepts archive versions 1, 2 and 3. Destructive migration fallback is not enabled.

## License

Money Journal is released under the [MIT License](LICENSE). Copyright © 2026 Shaik Abdul Razak. Third-party license notices are kept in `licenses/` and in the app assets.
