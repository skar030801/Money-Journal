# Security design

## Reporting a vulnerability

Use GitHub's private vulnerability reporting feature if it is enabled for this repository. Please do not publish credentials, personal financial data, recovery codes, backup files, or detailed exploit instructions in a public issue.

## At rest and while locked

SQLCipher encrypts the relational Room database with its standard page encryption and authentication. The database has a random 256-bit key, not a human PIN. The random key is wrapped with AES-256-GCM using a PBKDF2-HMAC-SHA256 key derived from the app credential (600,000 iterations and a fresh 128-bit salt). That envelope is wrapped again with a non-exportable Android Keystore AES key. Ordinary preferences contain ciphertext, salts, non-secret lockout bookkeeping and appearance only. Income and expense drafts are AES-GCM-encrypted with the database key.

An independent high-entropy recovery code wraps the same database key. The code is shown at setup and protected as an encrypted pending item until acknowledged. The app does not store the acknowledged code itself. Recovery derives a new app-password envelope while retaining existing financial data.

Biometric unlock uses a separate Keystore key requiring authentication for each use and a BiometricPrompt CryptoObject. Enrollment changes invalidate that key; the application credential remains available. Biometrics do not authorize permanent deletion.

The foreground session holds the unwrapped key while it needs database access. On leaving the foreground, public UI state is immediately replaced with locked state. Database access is serialized, then closed and the application's byte-array copy of the key wiped. Generation checks prevent late background query results from publishing private UI state after locking. Managed runtimes and native libraries cannot guarantee that every historical copy has been zeroized. No claim is made to defend a live unlocked session on a rooted or otherwise compromised operating system.

## Authentication and destructive actions

The same application credential protects all profiles. Incorrect credentials cannot unwrap the key. After five failures, persistent exponential backoff starts at 30 seconds and is capped at 16 minutes. This is defense in depth; password-derived encryption and Keystore protection are the primary protections.

Income/expense/profile deletion requires an explicit irreversible-deletion confirmation and fresh app credential authentication. Restore additionally requires the backup password and replaces financial records in a Room transaction. A failed restore rolls back all imported changes.

## Portable backups and reports

Backups use a versioned binary record stream encrypted with AES-256-GCM, with an independent password, 128-bit random salt, 96-bit random nonce and PBKDF2-HMAC-SHA256 at 600,000 iterations. The version header is authenticated as additional data. The entire GCM tag is verified with `doFinal` before parsing/import, rather than relying on CipherInputStream's authentication behavior. Restore is bounded to 64 MB of encrypted input and validates names, currencies, dates, monetary limits and database relationships.

CSV, XLSX and PDF are deliberately readable exports. Files go only to a location explicitly chosen through Android's Storage Access Framework. The app does not write plaintext staging files. CSV cells escape quotes and neutralize formula-leading text; XLSX uses explicit inline strings, so descriptions cannot become spreadsheet formulas. Export or document-provider failure is reported; a provider may leave a partial destination file, which should be deleted or overwritten by the user.

## Permissions and release

The app requests biometric authentication support only; dependencies may contribute compatibility fingerprint and app-private signature permissions. It does not request internet, contacts, location, camera, microphone or broad storage access. Automatic Android backup is disabled because Keystore-protected data cannot safely migrate through it. No telemetry, analytics, ads or sensitive logs are included. FLAG_SECURE protects screens and recents. Release code is shrunk and obfuscated with R8 and signed with a private locally generated release key.

This implementation uses platform/JCA cryptographic primitives and SQLCipher, not a newly invented cipher. It has not undergone an independent penetration test. Follow Android and SQLCipher security updates before distributing future versions.

References: [SQLCipher for Android](https://www.zetetic.net/sqlcipher/sqlcipher-for-android/), [Android biometric cryptographic operations](https://developer.android.com/identity/sign-in/biometric-auth).
