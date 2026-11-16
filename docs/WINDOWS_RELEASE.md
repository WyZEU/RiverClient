# Signed Windows releases

River Client is published by Supro Nivelo s.r.o. with the copyright holder's
authorization. Source copyright and the existing River product/application IDs
are preserved. The Windows Authenticode publisher is **Supro Nivelo s.r.o.**

## Prepared on 2026-11-16

- Azure account: `supro-nivelo-signing`, North Europe.
- Existing Public Trust profile: `tunnel-brawl-windows`. Its certificate represents
  the company, not a specific product. The existing Basic tier refused a second
  Public Trust profile; no pricing-tier upgrade was made.
- Separate Entra application/service principal: `river-client-github-signing`.
- No client secret or private certificate is stored in this repository or in GitHub.
- Its only Azure role is **Artifact Signing Certificate Profile Signer**, assigned
  on that certificate profile, not on the account/resource group/subscription.
- Federation accepts issuer `https://token.actions.githubusercontent.com`, audience
  `api://AzureADTokenExchange`, and exactly
  `repo:WyZEU/RiverClient:ref:refs/heads/master`.
- The Tunnel Brawl application and its federation were not modified.

Azure configuration and RBAC were read back after creation. GitHub OIDC login and
an actual signing operation still need the first Windows Actions run. Reusing the
profile means the two products share its signing quota and revocation boundary.
If the repository owner/name or release branch changes, update both federation
and the workflow guard deliberately.

GitHub write access was verified on 2026-11-16. The repository secrets
`AZURE_CLIENT_ID`, `AZURE_TENANT_ID` and `AZURE_SUBSCRIPTION_ID` have been configured
and their metadata read back. The Azure federation and certificate-profile signer
role were checked again before setting them. No client secret was created.

## First signed build

1. Resolve/check the repository owner's GitHub billing lock. When inspected on
   2026-11-16, the latest CI run `43709926791` (from November 15) reported that it
   had been rejected before any runner started for that reason.
2. The repository secrets are already configured. To check Azure federation,
   certificate-profile access and GitHub access again, run:

   ```sh
   python3 launcher/scripts/configure-github-signing.py
   ```

   Use `--apply` only when configuring or deliberately refreshing the three
   identifier secrets. Their values cannot be read back from GitHub; this command
   verifies their names after uploading them.
3. Merge the reviewed PR into `master`, then dispatch:

   ```sh
   gh workflow run windows-release.yml --repo WyZEU/RiverClient --ref master
   ```

The workflow only starts manually on that repository's `master`. It uploads
GitHub Actions artifacts with 14-day retention; it does not create a public
GitHub Release, upload to Cloudflare, or change the update channel.

If new upstream code arrives before review is complete, merge the updated
`origin/master` into the same PR branch and rerun CI and the release version check.
Confirm that any new Minecraft targets are included in runtime staging. Branch
and PR CI do not have an Azure federation subject and cannot perform this signed
release. Keep the release workflow and federation restricted to `master`.

## Build and signing contract

The Windows 2022 runner installs Node 22, JDK 21 and JDK 25. Gradle builds every
active Stonecutter Minecraft target and `river-bootstrap`. `legacy/` is a separate
project and is not part of this release. Staging refuses missing target JARs,
version disagreement and unrelated bundled JARs, and records source commit and
SHA-256 hashes. Both Java versions are explicitly discoverable by Gradle.

`npm run dist:windows:signed` uses `electron-builder.windows.cjs`. It requires
`RIVER_RELEASE_BUILD=1`, `GITHUB_SHA`, staged runtime metadata and
`RIVER_SIGNING_LOG`. In release mode the Windows media helper must compile from
its C# source; a missing compiler or failed compilation cannot silently omit it
or substitute an old executable.

The generated application metadata and Windows file version use `riverVersion`.
All mod version declarations must agree. Before a new public update, advance the
version; rebuilding the same version is not enough to trigger an update.

Signing runs **inside** electron-builder through a custom SHA-256 hook. This
includes the main EXE, native helper, NSIS installer/uninstaller and portable
wrapper before they are embedded in final artifacts. The hook uses the pinned
Microsoft `ArtifactSigning` PowerShell module and Azure CLI's OIDC session. The
version-26 built-in Azure provider rejects OIDC-only configuration because it
requires a client secret/certificate; the hook avoids that limitation.

Every hook call verifies the Authenticode chain, exact company subject and
timestamp. The final verifier checks the EXEs again, silently installs into a
unique temporary directory, verifies the installed app/helper/uninstaller, runs
the Electron runtime and media helper, and tests uninstall. It does **not** prove
UI rendering, Microsoft sign-in, Minecraft launch, gameplay or an actual update
from an older installed release. Those remain release acceptance checks on a
Windows machine.

The `river-windows-<full-commit>` artifact contains:

- `River-Client-Setup.exe` and `River-Client-Portable.exe`;
- `River-Client-App.zip`, containing the signed unpacked application;
- `WINDOWS-BUILD-INFO.json`, `WINDOWS-FILES.json`, `signatures.jsonl`, `SHA256SUMS.txt`.

The app ZIP is retained because the custom updater needs the signed app files,
not only the NSIS installer. The publication workflow generates file manifests/blockmaps
from these exact files **after signing**. Modifying or rebuilding them afterward
invalidates the relationship between signatures, hashes and tested artifacts.
`WINDOWS-FILES.json` records the final file hashes; ZIP contents are checked
against them before upload.

## Publishing an automatic update is a separate step

Use the separate `publish-windows-update.yml` workflow, documented in
[UPDATE_PUBLICATION.md](UPDATE_PUBLICATION.md). It defaults to preparation only,
checks the successful source build and artifact hashes, verifies native signatures,
and generates update hashes/blockmaps from the exact signed bytes. Live mode uses
the protected `river-update-production` environment, verifies immutable R2 uploads,
then conditionally promotes the signed channel manifest.

The legacy `publish:update` script can rebuild through the ordinary unsigned
`dist` command, even after `--skip-build` when its freshness check fails. Do not
use that path for verified signed artifacts.

Publication also needs the existing Ed25519 update private key matching
`launcher/src/config/update-public-key.txt` and access to the owner's Cloudflare
account/bucket. Do not replace this public key with a new one casually: installed
clients trust the old key and would reject the next manifest. Azure Authenticode
does not replace this separate update trust chain.

See [AUTOUPDATE.md](AUTOUPDATE.md) for the current update flow and identified
pre-publication issues.

References: [electron-builder v26 signing hooks](https://www.electron.build/v26/docs/features/code-signing/code-signing-win/),
[Microsoft Artifact Signing action](https://github.com/Azure/artifact-signing-action).

## Local preparation verification

On 2026-11-16, the full Gradle build completed in **9m 25s** with **192 executed
tasks**. All 14 client JARs and the bootstrap were staged; ZIP integrity, mod
metadata and compiled entrypoint classes were independently checked for all 15.
The launcher JS/CSS bundle built with those runtimes. All **90 JavaScript tests**
passed after both updater fixes, including four release-preparation tests.
Actionlint, electron-builder's
configuration schema and PowerShell parser checks passed. Both documented
autoupdate defects were reproduced locally without changing production state.

The checks ran on macOS with local Temurin JDKs 21/25. Native Windows compilation
of the C# helper, Azure OIDC token exchange/signing, NSIS install/uninstall and
packaged runtime checks are prepared in the workflow but have not run yet.

After adding immutable update publication and the concurrent Tebex fixes on
2026-11-16, all **114 JavaScript tests** passed on Node **22.23.5**. The launcher
bundle, release configuration, Actionlint and PowerShell parsing passed again.
Publication tests use generated test keys and simulated storage/HTTP; they do not
substitute for an original-key signature, live R2 upload or Windows acceptance.
