# Bundled River runtime

Build every active Minecraft target and the bootstrap from the repository root:

```sh
./gradlew build --no-daemon
node launcher/scripts/prepare-windows-release.js
```

Gradle needs both JDK 21 and JDK 25 installed/discoverable. The Windows workflow
sets them up explicitly; see [WINDOWS_RELEASE.md](../../../docs/WINDOWS_RELEASE.md).

The preparation script reads active targets from `settings.gradle.kts`, copies
`versions/<minecraft>/build/libs/clientcore-<riverVersion>+<minecraft>.jar` and
`river-bootstrap/build/libs/river-bootstrap-<version>.jar`, and records their
SHA-256 hashes and source commit in `river-build-info.json`. It fails if a target
is missing or the launcher/mod release versions disagree. Generated JARs and
build metadata are ignored by Git.

Only the launcher's default Minecraft version (`DEFAULT_MC_VERSION` in `main.js`)
and the bootstrap are copied here. The other targets go to `launcher/release-mods/`
and are downloaded by the launcher when an instance needs them.

`build-app.js` copies this directory into `app/bundled`; electron-builder also
places it outside ASAR at `resources/bundled` so Java can open the real files.
The launcher chooses the client JAR matching the instance's Minecraft version.
It normally installs that JAR as a Fabric mod, retaining agent loading as a
fallback. `legacy/` is a separate project and is not staged by this workflow.
