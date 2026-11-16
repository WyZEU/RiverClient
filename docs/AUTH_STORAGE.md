# Launcher account storage

Microsoft refresh tokens, Minecraft access tokens and the River session stay in the Electron main process. Status events and the login, refresh, Microsoft status and saved-account IPC responses expose only public account/profile fields. The Microsoft device-code flow, scopes, token expiry checks and 30-day refresh-session policy are unchanged.

## Migration and failure handling

After Electron is ready, the launcher reads `minecraft-auth.json`, `profiles.json` (all saved accounts) and `river-session.json` in its existing user-data directory. It accepts the old JSON contents and replaces each file with a versioned `river-safe-storage` envelope containing ciphertext from Electron `safeStorage`. No new login is needed for a readable existing session.

Migration verifies encryption/decryption, writes a sibling temporary file with mode `0600`, flushes it, reads and decrypts it again, then atomically renames it over the original. A failed migration preserves the original; temporary files are cleaned up. Successful migration keeps no plaintext backup. Decrypted data is cached only in the main process; a launcher restart decrypts the persisted file again.

If secure OS storage is unavailable, an **existing legacy file** remains readable and writable so refresh-token rotation does not discard an existing login. Settings explicitly reports that its account files are still unencrypted. Reads retry migration after 30 seconds. Linux `basic_text` and `unknown` backends are treated as unavailable. New files and files already encrypted never fall back to plaintext.

A locked keychain, unreadable ciphertext or unsupported format does not become an empty saved-account list on disk. Login and account mutations fail without overwriting that file. Unlocking the original OS storage allows it to be read again. An already decrypted account remains usable in memory; refresh checks that storage is available before requesting a new token. Filesystem failures retain the previous persisted session.

Refreshed tokens are copied into the matching saved account. Switching accounts saves the outgoing account's current token first and discards the outgoing River session. Explicit logout removes the active Microsoft and River sessions, while keeping accounts the user explicitly saved.

The synchronous API matches the launcher's pinned Electron 43 runtime and existing synchronous main-process auth callers. Keep the application identity stable between signed updates. Older launcher versions do not understand the encrypted format, and copying it to a different OS account/machine does not transfer its OS key. Downgrade or cross-machine login portability is not provided by this migration.

## Verification

`node --test launcher/tests/credential-store.test.js launcher/tests/auth-session.test.js` exercises migration/restart, active and multiple saved accounts, refresh-token rotation, switching/logout/new login, public IPC projections, failed refresh, locked/different OS keys, Linux fallback backends, corrupt files, encryption/read-back failures and filesystem failures. Microsoft/Xbox/Minecraft responses and the OS-provider boundary are simulated; these tests never use real user credentials.

The separate Electron smoke test exercises the real OS keychain in two independent processes, using synthetic credentials in a temporary directory. From the repository root in a POSIX shell:

```sh
auth_test_dir=$(mktemp -d "${TMPDIR:-/tmp}/river-auth-smoke-XXXXXX")
env -u ELECTRON_RUN_AS_NODE node launcher/node_modules/electron/cli.js launcher/tests/electron-auth-migration.cjs "$auth_test_dir" seed
env -u ELECTRON_RUN_AS_NODE node launcher/node_modules/electron/cli.js launcher/tests/electron-auth-migration.cjs "$auth_test_dir" restart
```

The smoke test uses the separate application name `River Auth Migration Test`; it never reads the installed launcher's account files. On 2026-11-16 both processes passed with Electron 43.8.10 and macOS Keychain. Native Windows/Linux keychains and a live Microsoft login/game launch still require release-platform verification.
