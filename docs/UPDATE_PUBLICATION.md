# Publishing a signed Windows and Linux update

The `Publish signed Windows and Linux update` workflow consumes the exact Windows
and Linux artifacts from a successful `Signed Windows release` run. It does not
rebuild the application. The Linux job builds all 14 Minecraft targets, packages
the x64 archive and checks the packaged version, source commit, runtime JARs and
executable permissions. The same Linux job also runs in PR CI.
By default it only prepares a review artifact. Setting `publish` to `true`
explicitly enables R2 uploads and promotion of `latest.json` or `beta.json`.

## Owner setup

In [repository environments](https://github.com/WyZEU/RiverClient/settings/environments),
create **`river-update-production`**. Under **Deployment branches and tags**,
choose **Selected branches and tags**, and add exactly one **branch** rule:
`master`. Do not add a tag rule or wildcard. This must be configured before a
live publication; GitHub can otherwise create an unrestricted empty environment
when a workflow first references its name.

Add these **environment secrets** directly in GitHub:

| Secret | Value |
| --- | --- |
| `RIVER_UPDATE_SIGNING_KEY_PEM` | Complete original `river-update-signing.key`, including its PEM BEGIN/END lines. |
| `RIVER_R2_ACCESS_KEY_ID` | R2 S3 access key ID with Object Read & Write limited to `river-client-updates`. |
| `RIVER_R2_SECRET_ACCESS_KEY` | Corresponding R2 S3 secret access key. |

Use the WyZ Cloudflare account `11788fa19a00381561b1e06a6a0bee8c`.
These are S3 credentials, not a general Cloudflare API token. No Worker deployment,
account administration, or access to other buckets is required by the publisher.

WyZEU holds the existing update key. Paste it into GitHub Secrets; it does not
need to pass through a chat, email, this Mac, or a repository commit. Do not
generate a replacement: existing installations trust the public key in
`launcher/src/config/update-public-key.txt`. The signing step derives the public
key from the supplied private key and refuses a mismatch. This Ed25519 key is
separate from Windows Authenticode signing through Azure.

An admin with a local copy of the original key can alternatively use
`node launcher/scripts/configure-update-publishing.js --apply --key-file /absolute/path/river-update-signing.key`,
with the two R2 credentials already in the process environment. That helper
validates the key and read access to the signed stable manifest before changing
GitHub. It refuses an existing broader/different environment policy. It never
prints secret values. Without `--apply` it checks policy and secret names only.

## Prerequisites for the first publication

1. Merge the reviewed workflows into `master` after CI passes. A branch or PR
   cannot sign or publish a production update.
2. Deliberately deploy the reviewed `cloudflare-updates` Worker before publishing.
   `GET https://updates.riverclient.xyz/release-capabilities.json` must return
   both `publicationProtocol: 1` and `linuxPublicationProtocol: 1`. The public stable manifest must verify and match
   the signed manifest in R2 byte-for-byte in canonical form. An older Worker
   that changes signed fields is rejected before any upload.
3. The prepared candidate is **0.3.1**. For subsequent releases, advance only
   `riverVersion` in `launcher/package.json`; the launcher and all active Minecraft
   targets derive their version from it. The published release must be newer than
   stable and the selected channel; recheck live versions before release.
   New releases and hotfixes use canonical `MAJOR.MINOR.PATCH` (for example, a
   subsequent hotfix uses `0.3.2`, not `0.3.1.1`). Historical four-part versions such
   as `0.2.8.1` remain readable and comparable, including reviewed minimum versions.
4. Wait for the automatic [signed Windows build](WINDOWS_RELEASE.md) from the push
   to `master`, including its successful Linux job, then review its native
   validation results and perform the Windows login/game launch/update acceptance
   checks. Local unit fixtures are not real signed Windows binaries.
5. Review the [credential rollback limitation](AUTH_STORAGE.md#rollback-after-migration).
   A manual downgrade to 0.3.0 after encryption requires new sign-in. Prefer a
   forward hotfix; record the owner's acceptance before publishing the migration.

## What manual publication means

Pushing to `master` automatically builds and signs a candidate. Users keep the
currently published update until an operator explicitly publishes a chosen build:

1. Open **Actions → Signed Windows release → the successful run**. Download and
   test its signed Windows artifact and Linux archive. Copy `build_run_id` and `source_commit` from the run
   summary; the summary also links to the publication workflow.
2. Open **Actions → Publish signed Windows and Linux update → Run workflow** and select
   **master**. Enter the copied values, choose `stable` or `beta`, add approved
   notes, the reviewed minimum, and the current target channel version.
3. Leave **publish unchecked** for a preview. Download the resulting plan artifact
   and check the version, files, notes and policy. This run changes no public files.
4. Run the publication workflow again with the same build/inputs and **publish
   checked**. The workflow signs the update manifest, uploads/verifies the exact
   build and changes the selected update channel. No manual file upload or local
   signing-key handling is needed.

This is an explicit workflow dispatch by an operator, not an automatic follow-up
to a successful build or a separate reviewer-approval queue. It does not create a
GitHub Release. Existing clients discover the promoted update on startup or their
30-minute poll; `required_update` controls whether they must take it.

## Workflow inputs and review

Dispatch `publish-windows-update.yml` on `master` with `publish=false` first:

| Input | Meaning |
| --- | --- |
| `build_run_id` | Successful Windows release run ID, including its Linux job, from a master push or manual retry, not a PR CI run. |
| `source_commit` | Full 40-character source commit from that build. |
| `channel` | `stable` or `beta`; beta's manifest remains access-controlled. |
| `minimum_version` | Explicit reviewed launcher minimum; do not raise it automatically. |
| `expected_previous_version` | Current target channel version, or `none` for an empty beta channel. |
| `release_notes` | Approved JSON for the exact artifact version, as below. |
| `required_update` | Whether clients must take this update; defaults to false. |
| `publish` | Defaults to false; true uploads and promotes the release. |

Example release notes (replace with reviewed release content):

```json
{"version":"0.3.1","approved":true,"title":"River Client 0.3.1","summary":"Signed Windows release and updater fixes.","items":["Windows binaries signed by Supro Nivelo s.r.o.","Fixed differential update blockmap handling."]}
```

The workflow validates the source repository, master-only build workflow, successful
run, master ancestry, artifact SHA-256 digest, file inventories and smoke-test
evidence. It extracts only listed regular files, rejects unsafe Windows paths,
and rechecks the actual Authenticode signatures on Windows. It generates hashes
and blockmaps from these signed bytes, without running the old publisher.

Linux is mandatory: the downloader verifies its separate immutable Actions
artifact digest from that same run, and preparation rejects a mismatched commit,
version, Minecraft target inventory or archive SHA-256. The prepared manifest
includes `linuxUrl` and `files.linux` pointing at
`releases/<version>-<commit12>/River-Client-Linux-<version>.tar.gz`. These fields and
the archive checksum are covered by the existing Ed25519 signature.

Download `river-update-plan-<run>-<attempt>` and review the source record,
publication plan, unsigned manifest, file manifest, notes and minimum. Only
metadata is retained in this review artifact; it is not a runnable application.
For publication, dispatch again with the **same build and inputs**, `publish=true`,
and a freshly reviewed expected previous version. The artifact creation time is
used as a deterministic manifest timestamp so retries of the same inputs agree.

## Promotion, failures and retries

Assets use immutable prefixes such as `releases/0.3.1-<commit12>/` and
`releases/0.3.1-beta-<commit12>/`. Each upload is create-only, with multipart
transfer for large objects. Existing objects must match the expected bytes.
Every object is read back from R2 and hashed; the file manifest is also checked
through the public Worker before channel promotion. Its compact JSON encoding
matches the existing launcher's `SHA256(JSON.stringify(parsedManifest))` check.

A conditional write against the previously read ETag promotes the channel once
all files are verified. Another publisher cannot silently overwrite that change.
The website's rolling `River-Client-Setup.exe` and `River-Client-Linux.tar.gz`
routes redirect to the immutable downloads selected by that same stable manifest.
The root Linux object is not overwritten independently: the redirect changes at
the channel commit, while old manifests still serve their legacy root objects.
A stable history marker under
`releases/<version>/published.json` is written only after promotion, so partial
uploads and beta uploads cannot advance the standalone mod's version policy.
Beta does not change stable, its rolling installer, or stable history. As in the
existing service, versioned download objects are public; beta is not private
storage for confidential binaries.

Inspect `river-update-result-<run>-<attempt>` on success **and failure**. Its
`publication-result.json` records whether the channel committed, assets were
verified, and stable history was marked. A lost network response can leave the
commit result uncertain. A later failure can occur after a successful commit.
Check live state and rerun the **same build and inputs** to finish verification
or the marker; do not rebuild, change notes/minimum, delete staged objects, or
assume that a failed workflow means the old release is still active.

Publication refuses downgrades and replacement of an existing version. There is
no automated rollback to an older signed version. Interrupted uploads can leave
unused immutable objects, which are harmless and can be reviewed for cleanup.

## Local preparation

From the repository root, with `launcher` dependencies installed:

```sh
node launcher/scripts/download-signed-build.js --run-id RUN_ID --commit FULL_SHA --output dist/signed-build --record dist/build-source.json
node launcher/scripts/download-signed-build.js --platform linux --run-id RUN_ID --commit FULL_SHA --output dist/linux-build --record dist/linux-source.json
node launcher/scripts/prepare-signed-update.js --artifact dist/signed-build --linux-artifact dist/linux-build --output dist/publication --commit FULL_SHA --notes /absolute/path/approved-notes.json --minimum-version REVIEWED_MINIMUM --channel stable --published-at ARTIFACT_CREATED_AT_ISO_UTC
node launcher/scripts/publish-signed-update.js --prepared dist/publication
```

The last command only validates locally; it loads no R2 credentials and performs
no upload. Obtain the timestamp from `dist/build-source.json`. Signing additionally
requires `RIVER_UPDATE_SIGNING_KEY` to point to the original key file and
`prepare-signed-update.js --sign dist/publication`. Live local promotion requires
the two R2 environment variables and the explicit flags
`--publish --expect-current-version REVIEWED_CURRENT_VERSION`; prefer the
environment-scoped Windows workflow for production, including its native
signature checks. Never use `publish:update` or `publish:update:fast` for these
artifacts: those legacy paths can rebuild an unsigned application.

## Scope and verification

This path publishes Windows and Linux together. It does not deploy the Worker or
website, post a Discord announcement, or send an update WebSocket broadcast.
Existing clients discover a promoted update at startup or the 30-minute fallback
poll. Linux's update button opens the verified versioned archive for manual
replacement. macOS uses its separate signed manifest and manual publication flow;
see [MACOS_RELEASE.md](MACOS_RELEASE.md).

The channel and required/minimum policy are shared by Windows and Linux. Keep
`required_update=false` unless the owner explicitly approves a forced update, and
do not raise the minimum automatically. Before release, perform a real Windows
Microsoft sign-in, launch 1.21.11 and 26.3, update an installed public 0.3.0 client,
and export/import a `.rvr`. The automated Windows upgrade tests simulate Microsoft
responses; passing them does not replace live login/gameplay acceptance.

Local tests cover immutable bytes, archive validation, key mismatch, client
blockmap reconstruction, missing/mismatched Linux builds, R2 transfer/readback failures, conditional promotion,
concurrent writers, retries after commit, beta isolation and Worker redirects.
Run `node --test launcher/tests/*.test.js cloudflare-updates/tests/*.test.mjs`.
No production publication or original-key handoff was performed by this preparation.
On 2026-11-16, the live capability route still returned 404 and the served `0.3.0`
manifest failed signature verification. Deploying the reviewed Worker and checking
its live responses is therefore still required before a production run.

References: [R2 conditional operations](https://developers.cloudflare.com/r2/api/s3/conditional-operations/),
[R2 with the AWS SDK](https://developers.cloudflare.com/r2/examples/aws/aws-sdk-js-v3/).
