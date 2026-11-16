# Current automatic updater

Inspected at upstream commit `9b90535c74a690c0352257acb433691549579e24` on 2026-11-16.
This is River's own updater in `launcher/src/main.js`, with file planning and blockmap
downloads in `launcher/src/update-files.js` and the apply script in `launcher/src/update-apply.js`.

## Discovery and policy

The launcher checks on startup, subscribes to
`wss://updates.riverclient.xyz/ws/updates`, and has a 30-minute polling fallback.
A push message only triggers a fresh manifest fetch; it is not an install command.
The main source is `https://updates.riverclient.xyz/latest.json`, with website
fallbacks. Allow-listed beta testers have an authenticated `/beta.json` route.
The bundled manifest is a fallback when no remote manifest can be accepted.

A newer release is offered to the user. Play is blocked only if the running
version is below `minimumVersion`, or an available update is marked `required`.
The launcher uses the minimum from the signed manifest. Set the signed publication
workflow's explicit `minimum_version` input (`RIVER_MINIMUM_VERSION` in the legacy
publisher) to change it; the resulting manifest must be signed with the existing
update key. The Worker serves `/` and `/latest.json` without altering any signed fields.

The separate `/version-status.json` endpoint applies the Worker's dynamic policy to
the standalone mod: the latest three releases remain allowed by default, with a forced
minimum from R2 configuration. Dashboard changes apply to that endpoint; they cannot
override the launcher's signed minimum. A push only requests another manifest check.

## Trust and transfer

The client verifies the remote top-level manifest with the embedded Ed25519
public key. That signed manifest contains the hashes/locations of the ZIP and
the file manifest. The file manifest lists individual files and SHA-256 hashes.
The launcher compares those with the installed files and downloads changed files
into an OS temporary staging directory. Full-package ZIP download/extraction is
the fallback; large ZIPs can be delivered in several parts.

There is also code for content-defined blockmaps and HTTP Range requests, intended
to reuse unchanged chunks of large EXE/ASAR files. A reconstructed file must match
its whole-file SHA-256; otherwise the code falls back to downloading that file.

## Applying the update

Windows runs a separate updater process/window. A generated VBS/CMD/PowerShell
handoff waits for the launcher to close, copies the staged files, removes obsolete
resources/locales, and relaunches `RiverClient.exe`. A per-user installation should
not need elevation; a non-writable install location triggers UAC. Linux and macOS
instead open their platform's download URL; this Windows updater is not used there.
Mac downloads use `macUrls.arm64` / `macUrls.x64` or `macUrl`, with the River
website as fallback. See [macOS builds](MACOS_RELEASE.md).

Both delta and full-package paths plan replacements and obsolete resources/locales,
then copy and SHA-256 verify every existing affected file into a unique recovery
directory before changing the installation. A journal records each operation before
it starts. Failure restores only those operations, including partially overwritten
files and removed resources; untouched files are never removed as rollback.
Failure to restore one file does not stop the remaining restores. Recovery files
are retained and an incompletely restored launcher is not automatically started.
Only completed backups and old version staging directories are cleaned up. There
is no post-launch health check or automatic recovery after a power loss/process kill.

The first hop from public 0.3.0 still executes the updater already installed in
0.3.0. Its successful delta and full-package paths are tested against the candidate,
but its old rollback defects cannot be repaired by code that only arrives inside
the new app. If that first copy fails, reinstall from the approved installer while
keeping user data. The new rollback protection applies to updates initiated by the
corrected launcher.

## Fixes verified locally

- The Worker no longer rewrites `minimumVersion` in signed manifests. Route tests
  cover forced and release-count policies on both manifest URLs, an omitted minimum,
  the signed built-in fallback, and independent standalone-mod policy enforcement.
  Tampering with a minimum still fails Ed25519 verification.
- `buildDifferentialUpdatePlan()` now carries `blockmapUrl` through to the downloader.
  Tests run the actual planner and downloader against files in a temporary directory
  and mocked HTTP responses. They verify chunk reuse, byte-for-byte reconstruction,
  SHA-256 rejection of corrupt bytes or an untrusted map's hash, and fallback when
  the server ignores a Range request or no blockmap exists.

Run these regressions together with the existing suites:

```sh
node --test launcher/tests/*.test.js cloudflare-updates/tests/*.test.mjs
```

`update-apply.test.js` executes the generated script with actual PowerShell filesystem
operations and injected backup, partial-copy, corruption, deletion and restoration
failures. It uses Windows PowerShell 5.1 on Windows; elsewhere set
`RIVER_TEST_POWERSHELL` to a PowerShell executable. Without PowerShell these tests skip.

The `windows-upgrade` CI job builds an unsigned candidate without publishing. It
downloads the pinned public 0.3.0 ZIP (SHA-256 verified), executes that ASAR's own
planner/apply script for delta and package upgrades, verifies every candidate file
and starts the installed Electron executable in Node mode. The test also verifies
login migration/refresh/restart through the updated main process and renderer IPC
with real Windows DPAPI and simulated Microsoft responses. Process wait/relaunch
commands in apply-script tests are intercepted; VBS/UAC, code signing and a live
Microsoft authorization still require release acceptance.

## Connectivity investigation on 2026-11-16

The earlier certificate failure on the inspected network was traced to DNS
filtering: River hostnames resolved to `159.100.251.128`, which presented a
self-signed Whalebone certificate for `sad.certificate.that.cannot.be.valid.com`.
Its HTTP blocking page returned the Nette/Tracy 500 error seen in the browser.

Cloudflare's actual endpoints presented valid certificates. Encrypted DNS restored
ordinary system lookups, Chrome access and Node.js HTTPS requests: the root and
`www` returned 200, `/latest.json` on `updates` returned 200, and `discord` returned
the expected 302. TLS verification remained enabled. This verifies connectivity;
it does not validate the manifest signature or an installed client's full update.
Whalebone accepted a domain/subdomain classification review; its exact reason for
blocking and global removal remain pending. No Cloudflare configuration was changed.

## Prepared publication and remaining release checks

The new `publish-windows-update.yml` workflow consumes the immutable signed
Windows artifact, computes compatible hashes/blockmaps and signs with the existing
Ed25519 key. It defaults to local preparation; live publication verifies R2 bytes
before conditionally changing the channel. Stable website downloads follow the
same manifest through an immutable installer redirect. Partial and beta uploads
do not advance standalone-mod history. See [UPDATE_PUBLICATION.md](UPDATE_PUBLICATION.md)
for owner secrets, review, explicit publication and failure recovery.

Before the first live run, the owner must configure the original key and R2 secrets
in the master-only environment and resolve GitHub's billing lock. The publication
Worker routes must be deployed and the launcher fix included in a new version.
Actual Windows signing and an update from an installed older client still require
release acceptance checks. This publication preparation does not deploy the Worker,
replace the update key or change a production channel.
