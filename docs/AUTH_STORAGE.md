# Launcher account storage

Microsoft refresh tokens, Minecraft access tokens and the River session stay in the Electron main process. Status events and the login, refresh, Microsoft status and saved-account IPC responses expose only public account/profile fields. The Microsoft device-code flow, scopes, token expiry checks and 30-day refresh-session policy are unchanged.

## Migration and failure handling

After Electron is ready, the launcher reads `minecraft-auth.json`, `profiles.json` (all saved accounts) and `river-session.json` in its existing user-data directory. It accepts the old JSON contents and replaces each file with a versioned `river-safe-storage` envelope containing ciphertext from Electron `safeStorage`. No new login is needed for a readable existing session.

Migration verifies encryption/decryption, writes a sibling temporary file with mode `0600`, flushes it, reads and decrypts it again, then atomically renames it over the original. A failed migration preserves the original; temporary files are cleaned up. Successful migration keeps no plaintext backup. Decrypted data is cached only in the main process; a launcher restart decrypts the persisted file again.

If secure OS storage is unavailable, an **existing legacy file** remains readable and writable so refresh-token rotation does not discard an existing login. Settings explicitly reports that its account files are still unencrypted. Reads retry migration after 30 seconds. Linux `basic_text` and `unknown` backends are treated as unavailable. Files already encrypted never fall back to plaintext.

On Linux without an available keyring, a missing file can be created as plaintext with mode `0600`, using the same atomic write and read-back checks. Fresh Microsoft sign-ins, saved accounts and River sessions survive restart, and Settings reports pending encryption. Once a secure provider becomes available, the files migrate to ciphertext. A provider that reports availability but fails encryption uses a temporary session instead; it does not create plaintext.

On Windows and macOS with unavailable secure storage, new sign-ins and saved-account selections use a temporary session in main-process memory. Settings reports that it ends when the launcher closes. Logout clears it, refresh can rotate its token, and storage availability is retried after 30 seconds; once available, the pending value is persisted as ciphertext. Existing encrypted files remain protected from replacement or plaintext fallback on every platform.

A locked keychain, unreadable ciphertext or unsupported format does not become an empty saved-account list on disk. Login and account mutations fail without overwriting that file. Unlocking the original OS storage allows it to be read again. An already decrypted account remains usable in memory; refresh checks that storage is available before requesting a new token. Filesystem failures retain the previous persisted session.

Refreshed tokens are copied into the matching saved account. Switching accounts saves the outgoing account's current token first and discards the outgoing River session. Explicit logout removes the active Microsoft and River sessions, while keeping accounts the user explicitly saved.

Login attempts, explicit account switches and logout invalidate pending work from the previous session. A late refresh, device-code result, skin/profile write or River sign-in cannot restore it. Concurrent refresh callers share one token rotation. Session markers are kept in memory and excluded from JSON and public IPC responses.

The synchronous API matches the launcher's pinned Electron 43 runtime and existing synchronous main-process auth callers. Keep the application identity stable between signed updates. Older launcher versions do not understand the encrypted format, and copying it to a different OS account/machine does not transfer its OS key. Downgrade or cross-machine login portability is not provided by this migration.

## Rollback after migration

Reinstalling 0.3.0 after a successful migration cannot preserve sign-in: 0.3.0 cannot decrypt the envelope and may replace it on its next account write. Do not use that downgrade as the routine rollback procedure or retain plaintext token backups. Prefer a corrected release with a higher three-part version that keeps the credential reader and the application identity. This preserves the current OS-encrypted sessions.

An update-application failure that restores the old files before the new launcher starts is different: credential migration has not run yet. The updater recovery tests cover that case. Any deliberate return to 0.3.0 after the new launcher has run requires owner approval of the sign-in loss and fresh Microsoft authorization. Record that decision before publication; the review alone is not acceptance of the tradeoff.

## Verification

`node --test launcher/tests/credential-store.test.js launcher/tests/auth-session.test.js` exercises migration/restart, active and multiple saved accounts, refresh-token rotation, switching/logout/new login, public IPC projections, failed refresh, locked/different OS keys, Linux fallback backends, corrupt files, encryption/read-back failures and filesystem failures. Microsoft/Xbox/Minecraft responses and the OS-provider boundary are simulated; these tests never use real user credentials.

The separate Electron smoke test exercises the real OS keychain in two independent processes, using synthetic credentials in a temporary directory. From the repository root in a POSIX shell:

```sh
auth_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/river-auth-smoke-XXXXXX")
env -u ELECTRON_RUN_AS_NODE node launcher/node_modules/electron/cli.js launcher/tests/electron-auth-migration.cjs "$auth_test_dir" seed
env -u ELECTRON_RUN_AS_NODE node launcher/node_modules/electron/cli.js launcher/tests/electron-auth-migration.cjs "$auth_test_dir" restart
```

The smoke test uses the separate application name `River Auth Migration Test`; it never reads the installed launcher's account files. On 2026-11-16 both processes passed with Electron 43.8.10 and macOS Keychain.

`electron-auth-upgrade.cjs` additionally runs the built main process and renderer IPC against credentials written by the exact public 0.3.0 code. It covers migration, token refresh, account selection, logout, a fresh device-code login and restart, plus login with unavailable secure storage. Microsoft/Xbox/Minecraft responses are simulated; Electron IPC and the OS credential provider are real. The `windows-upgrade` CI job runs this against the updated ASAR after both delta and full-package upgrades, with Windows DPAPI. Live Microsoft authorization, native Linux keychains and game launch remain separate release acceptance checks.
