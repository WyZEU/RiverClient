# Current automatic updater

Inspected at upstream commit `9b90535c74a690c0352257acb433691549579e24` on 2026-11-16.
This is River's own updater in `launcher/src/main.js`, with file planning and blockmap
downloads in `launcher/src/update-files.js`, not electron-updater.

## Discovery and policy

The launcher checks on startup, subscribes to
`wss://updates.riverclient.xyz/ws/updates`, and has a 30-minute polling fallback.
A push message only triggers a fresh manifest fetch; it is not an install command.
The main source is `https://updates.riverclient.xyz/latest.json`, with website
fallbacks. Allow-listed beta testers have an authenticated `/beta.json` route.
The bundled manifest is a fallback when no remote manifest can be accepted.

A newer release is offered to the user. Play is blocked only if the running
version is below `minimumVersion`, or an available update is marked `required`.
The launcher uses the minimum from the signed manifest. Set `RIVER_MINIMUM_VERSION`
when publishing to change it; the resulting manifest must be signed with the existing
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
not need elevation; a non-writable install location triggers UAC. Linux instead
opens the download URL; this Windows updater is not used there.

The delta path backs up replaced files and attempts to restore them after a copy
failure. This is not a complete transactional rollback: removed files are not
backed up, the full-ZIP path does not restore a full prior installation, and there
is no post-launch health check that automatically rolls back a bad new release.

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

## Remaining publishing work

1. **Endpoint TLS could not be validated here.** Requests to the update/website
   domains on this machine failed certificate validation; the update hostname
   presented `sad.certificate.that.cannot.be.valid.com`. Local and public DNS
   resolved it to `159.100.251.128`. This is an observed reachability failure, not
   proof of its cause or behavior on every user's connection. No TLS verification
   was disabled and no live manifest was accepted.
2. **Immutable signed publication is not connected.** The old publisher may
   rebuild an unsigned app. Integrate the signed artifact before publishing;
   compute file hashes/blockmaps after Authenticode signing and retain the
   existing Ed25519 key. See [WINDOWS_RELEASE.md](WINDOWS_RELEASE.md).

The two updater fixes are local source changes. The Worker fix must be deployed and
the launcher fix included in a release before users receive them. No production Worker,
update key, or channel has been changed by this preparation.
