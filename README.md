# River Client

A Fabric client for Minecraft Java, and the Windows launcher that installs and runs it.
Written by WyZ_EU.

Downloads are at **https://riverclient.xyz**.

## Why this is public

River signs you into your Microsoft account and attaches a Java agent to your game.
That's a lot to ask anyone to trust, so the code is here. Read it, build it, check it
does what I say it does.

Source-available, not open source. Don't lift it into your own client. See
[LICENSE](LICENSE).

## Layout

```
src/                  the mod
versions/             per-version build config
river-bootstrap/      fallback Java agent for loading the mod
launcher/             the launcher (Electron + React)
cloudflare-updates/   the Worker
```

One source tree builds the Minecraft targets listed in `settings.gradle.kts`.
The 26.x builds need Java 25, while the older main-tree targets use Java 21.
The separate `legacy/` project is not included in that build.

## Windows releases

Set the release version only in `launcher/package.json` (`riverVersion`). Every
push to `master` builds and signs a Windows candidate automatically. Publishing
that candidate to the updater remains a separate manual workflow; a push alone
does not change the public release.

See [signed Windows builds](docs/WINDOWS_RELEASE.md) for the Supro Nivelo signing
workflow and first-run setup, [automatic updates](docs/AUTOUPDATE.md) for the
current update protocol, and [signed update publication](docs/UPDATE_PUBLICATION.md)
for the separate review/publish workflow and GitHub environment secrets.

## Something's broken

Open a ticket in the [Discord](https://discord.riverclient.xyz). Bugs can go in a GitHub
issue too, attach the launcher log if you can.

---

Not affiliated with Mojang Studios or Microsoft.
