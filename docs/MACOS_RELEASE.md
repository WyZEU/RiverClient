# macOS builds

River's Electron launcher can be packaged as separate Apple Silicon (`arm64`)
and Intel (`x64`) DMGs. It requires macOS 12 or newer, matching Electron 43.
The launcher stays on the patched Electron 43 line to retain Monterey support;
Electron 44 and newer require macOS 13.
The application keeps the existing `dev.wyz.river.launcher` bundle identifier
and River branding. Install by dragging **River Client** into **Applications**.

## Build locally

Build on a Mac with Node.js, Xcode Command Line Tools, JDK 21 and JDK 25:

```sh
cd /path/to/RiverClient
./gradlew build --no-daemon
cd launcher
npm ci
npm run dist:mac
```

If Gradle cannot discover both JDKs, provide their `Contents/Home` directories
with `-Dorg.gradle.java.installations.paths=/path/to/jdk21/Contents/Home,/path/to/jdk25/Contents/Home`.
Set `JAVA_HOME` to one of the installed JDKs when Java is not on PATH.

The build stages all active mod targets and the bootstrap, generates the Mac
icon from the existing River artwork, bundles the renderer and creates DMGs in
`launcher/dist/macos/`. JARs are stored once in `Contents/Resources/bundled`,
outside ASAR, where Java can open them. Windows media helpers are omitted.
The packaged app version is `riverVersion`, not the older npm package version.
Build metadata records the base commit and whether the working tree was dirty.
Run Gradle again after changing mod sources; staging does not compile Java/Kotlin.

Pass `-- --arm64` or `-- --x64` to build only one architecture. The default builds
both. `dist:mac` deliberately uses an ad-hoc signature for local development.

## Signed and notarized release

Use a Developer ID Application certificate with its private key in the local
Keychain, and a validated `notarytool` Keychain profile:

```sh
export CSC_NAME='Supro Nivelo s.r.o. (A2A543D72R)'
export APPLE_KEYCHAIN_PROFILE='tunnel-brawl-notary'
npm run dist:mac:release
```

The existing company profile can notarize River as well. No credentials or
private keys are copied into this repository. The certificate is selected by
name, signing is mandatory, and only the JIT entitlement is enabled for Electron.
The release command requires a clean, committed working tree so the DMGs identify
the exact source commit. The script checks access to Apple's service before packaging. Missing credentials,
unaccepted Apple agreements, signing failures and rejected notarization stop it.
It does not silently fall back to an unsigned or unnotarized release.

The builder signs and notarizes each app and staples its ticket. The script also
notarizes and staples each final DMG, then verifies signatures, disk integrity,
stapled tickets and Gatekeeper acceptance. `build-result.json`,
`notarization-<arch>.json` and `SHA256SUMS` record the resulting artifacts.
For an explicitly unnotarized signed preview, use `dist:mac:signed` instead.
Build commands never upload a release to River's website or update channel.

## Runtime and updates

Mac builds select architecture-matching Java and Minecraft native libraries,
discover JDK bundles under `/Library/Java/JavaVirtualMachines` and the user's
equivalent directory, and support Temurin's nested `Contents/Home` layout.
Mojang's `-XstartOnFirstThread` argument is preserved. Native JAR extraction
does not require PowerShell. The mod uses `~/Library/Application Support` for
its desktop bridge, and imports recognize the Mac `minecraft` directory.

River `.rvr` instance exports and imports use streamed ZIP operations inside the
launcher on Windows, macOS and Linux. They preserve the existing archive format
and read older Windows ZIP paths without requiring PowerShell or a system archiver.

Mac updates open the architecture-specific DMG in the browser for manual
replacement. The launcher reads the independently signed `/macos.json` manifest;
a Windows/Linux release cannot offer a Mac build that has not been published.
Both architectures are published together. The Mac publication is optional and
does not change a forced-update minimum. A missing, unreachable or invalidly
signed Mac manifest reports an unavailable update check, with no Windows fallback.
Quit River Client before dragging the replacement app into Applications;
accounts and instances remain in the existing application-data directory.
Windows media-session integration is not available on macOS.

## Manual R2 publication

`.github/workflows/publish-macos.yml` (**Publish signed macOS downloads**) only
runs through `workflow_dispatch` on `master`. Its default `publish=false` prepares
a review artifact. Live publication requires WyZEU to dispatch (or retry) it with
`publish=true`, after approving the exact build and notes. Merging, building,
opening a PR, or completing CI never publishes a Mac release.

The job uses the existing **river-update-production** environment and its original
`RIVER_UPDATE_SIGNING_KEY_PEM`, `RIVER_R2_ACCESS_KEY_ID` and
`RIVER_R2_SECRET_ACCESS_KEY`. It does not need Apple private keys and never rebuilds
or resigns the DMGs. Do not replace the original River update signing key.

1. After merge, wait for **Signed macOS release** to finish successfully for both
   ARM64 and Intel. The two artifacts expire after 14 days; publish within that
   window or create and review a new build.
2. Run **Publish signed macOS downloads** with `publish=false`. Supply its build
   run ID, full source commit, the current Mac version (`none` for the first
   publication), and reviewed `release_notes` JSON containing `version`,
   `approved: true`, `title`, `summary` and `items`.
3. Review `river-macos-plan-<run>-<attempt>`: exact Actions archive digests,
   source commit, Apple verification results, both DMG hashes, publication plan,
   notes and unsigned manifest. The workflow checks master ancestry, clean source,
   accepted notarization receipts, embedded runtime/JAR provenance, architecture,
   app and container signatures for Apple team `A2A543D72R`, stapled tickets and
   Gatekeeper acceptance. Both builds must have the same version and commit.
4. With WyZ approval, deploy the reviewed Worker. Its
   `/release-capabilities.json` must include `macosPublicationProtocol: 1`.
   The initial `/macos.json` and rolling DMG links return 404 until publication.
   Deploy the separately reviewed website through its manual **Deploy** workflow;
   its Mac links remain unavailable until a complete release exists.
   The website change is in [river-website PR #1](https://github.com/WyZEU/river-website/pull/1).
5. WyZEU dispatches the publisher again with the **same build and notes**,
   `publish=true`, and the reviewed previous Mac version. The artifact timestamps
   make repeated preparation deterministic. No version bump occurs here.

Objects are uploaded create-only under
`releases/<version>-<commit12>/macos/`. Every object is read back from R2 and hashed;
the public checksum file and both DMG routes are verified before a conditional
ETag write activates `macos.json`. The signed manifest contains `macUrls.arm64`,
`macUrls.x64`, sizes, SHA-256 hashes, notes and the source commit. The website reads
it once per page load and only offers a complete pair of matching downloads.

Stable browser links, redirected by the same manifest:

- `https://updates.riverclient.xyz/downloads/River-Client-macOS-arm64.dmg`
- `https://updates.riverclient.xyz/downloads/River-Client-macOS-x64.dmg`

The publisher leaves `latest.json`, `beta.json`, Windows/Linux downloads and
standalone-mod release history unchanged. There is no Mac beta publication in
this workflow. Versioned release objects are public once staged; they must already
have WyZ approval before a live run, even if promotion later fails.

Review `river-macos-result-<run>-<attempt>` on failure as well as success.
`publication-result.json` records whether promotion committed or is uncertain.
A failure after promotion does not undo the release. Check live state and retry
the **same build and inputs** to finish readback; do not replace an existing
version, downgrade, delete objects or rebuild as a blind retry.

No production publication or Worker/website deployment was performed while
preparing this workflow. Native GitHub artifact retrieval and end-to-end live
publication remain checks for the first approved release.

## Automatic GitHub builds

`.github/workflows/macos-release.yml` runs on every push to `master`, with a
manual retry through **Actions → Signed macOS release → Run workflow**. It builds
ARM64 on `macos-15` and Intel on `macos-15-intel`, runs tests and Gradle, signs and
notarizes the application and DMG, and uploads one verified artifact per architecture
with 14-day retention. Every build uses the same `riverVersion` and source commit
as the corresponding Windows candidate. Pushes neither increment the version nor
publish these DMGs to the website/updater. A PR or fork cannot run the signing job.

A repository administrator must first create the **`river-macos-release`**
[environment](https://github.com/WyZEU/RiverClient/settings/environments), choose
**Selected branches and tags**, and add only the **branch** `master` (no tag or
wildcard). Add these five **environment secrets**:

| Secret | Value |
| --- | --- |
| `MACOS_CERTIFICATE_P12_BASE64` | Base64 of the password-encrypted P12 containing the Supro Nivelo Developer ID Application certificate and its private key. |
| `MACOS_CERTIFICATE_PASSWORD` | The P12 export password. |
| `APPLE_API_KEY_P8` | Complete App Store Connect notarization API private key in PEM format. |
| `APPLE_API_KEY_ID` | Key ID for that API key. |
| `APPLE_API_ISSUER` | Issuer ID for the team API key. |

Do not put these values in the source, a PR or chat. The signing step receives them
only after tests and compilation. electron-builder creates a temporary signing
Keychain; the workflow creates a separate temporary notarization Keychain, removes
the plaintext API key immediately after importing it, and removes that Keychain
on exit. No persistent runner is installed on a developer's Mac. The company
certificate is shared with other products, so revoking that certificate affects
them too. River uses a separate notarization API key, which can be revoked
independently; this environment limits which branch can obtain the credentials.

### Owner setup completed on 2026-11-16

- `river-macos-release` exists with exactly one deployment rule: **branch `master`**.
- All five environment secrets above are configured. The encrypted P12 contains
  only the Supro Nivelo Developer ID Application identity, with a random export
  password; its certificate fingerprint was verified after an in-memory import.
- The Supro Nivelo App Store Connect team has a dedicated **RiverClient GitHub
  Actions** key with the **Developer** role. Apple validated its notarization
  credentials before they were uploaded. An encrypted local recovery profile is
  stored in Keychain as `river-macos-github`; the downloaded plaintext P8 was removed.
- The owner updated the failed payment method. GitHub-hosted PR CI then started
  successfully, confirming that the previous account billing lock no longer
  prevents runners from starting.

The release workflows are still in PR #1. After review and merge into `master`,
the push starts both native Mac builds automatically. The first GitHub-hosted
signing/notarization run remains a required check; local credential validation
and PR CI do not substitute for it. To retry a release after merge:

```sh
gh workflow run macos-release.yml --repo WyZEU/RiverClient --ref master
```

Run regression tests with:

```sh
node --test launcher/tests/*.test.js cloudflare-updates/tests/*.test.mjs
```

Before publishing, test Microsoft login and an actual game launch on both
architectures. An Intel build made on Apple Silicon still needs testing on Intel
hardware. A successful launcher smoke check alone does not establish gameplay.

References: [electron-builder v26 macOS configuration](https://www.electron.build/v26/docs/mac/),
[notarization](https://www.electron.build/v26/docs/notarization/),
[LWJGL first-thread requirement](https://javadoc.lwjgl.org/org/lwjgl/glfw/package-summary.html).
