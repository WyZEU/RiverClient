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
river-bootstrap/      Java agent that loads the mod
launcher/             the launcher (Electron + React)
cloudflare-updates/   the Worker
```

One source tree builds 1.21.4, 1.21.6, 1.21.7, 1.21.8, 1.21.11, 26.1.2 and 26.2. The
26.x builds need Java 25, everything older runs on 21.

## Something's broken

Open a ticket in the [Discord](https://discord.riverclient.xyz). Bugs can go in a GitHub
issue too, attach the launcher log if you can.

---

Not affiliated with Mojang Studios or Microsoft.
