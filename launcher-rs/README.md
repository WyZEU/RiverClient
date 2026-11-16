# River launcher, native Rust

The launcher is being rewritten in Rust, with no web view: a smaller, faster program than
the Electron one. This folder holds the core, the parts with no user interface. The
Electron launcher in `launcher/` stays the one that ships until this can do everything it
does.

## The rule that matters

The Rust launcher reads and writes the **same files in the same folders** as the Electron
launcher. A player who updates into it keeps their accounts, settings and instances and
notices nothing. Anything that breaks that is a bug, not a design choice.

| What | Where |
| --- | --- |
| Launcher files | `%APPDATA%\river-client-launcher` (macOS `~/Library/Application Support`, Linux `~/.config`) |
| Settings | `settings.json` |
| Instance list | `instances.json` |
| Signed-in account | `minecraft-auth.json`, `profiles.json`, `river-session.json` |
| Game data and instances | `%APPDATA%\River Client` |

Update manifests are signed with the **same Ed25519 key** the Electron launcher trusts, in
`launcher/src/config/update-public-key.txt`. The canonical bytes in `update.rs` must stay
identical to `launcher/src/update-signature.js`; if they ever drift, every manifest River
has published would look forged.

## Layout

- `crates/river-core` — settings, instances, file storage, update verification.
- `crates/river-cli` — a read-only command line used to check the core against a real
  installation.

## Commands

```
cargo test --workspace          # everything
cargo run -p river-cli -- status            # read this machine's launcher files
cargo run -p river-cli -- verify <file>     # is this update manifest signed by River
```

## Done so far

- Data folders matching Electron on Windows, macOS and Linux.
- Settings and instances read and written without losing fields the core does not know.
- Crash-safe writes: a new file is written and then renamed into place.
- Ed25519 manifest verification, byte-identical to the JavaScript one. Checked against the
  live 0.3.0 manifest: the website copy verifies, and the copy the Worker rewrites does not,
  which is the same answer the JavaScript verifier gives.

## Next

Microsoft sign-in, Java discovery, Fabric and version manifests, downloads with hash
checks, launching the game, then the differential updater. The user interface comes after
the core can launch a game.
