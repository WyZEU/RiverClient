# River Client

A Fabric client for Minecraft Java, plus the Windows launcher that installs and
runs it. Made by WyZ_EU.

Downloads: **https://riverclient.xyz**

---

## Why this is public

River signs you into your Microsoft account, talks to a server, and attaches a
Java agent to your game. You should be suspicious of software that does that, so
here it is, check it your seld

River is **source-available**, not open source. Read it and build it to verify
it; do not use it in your own project. See [LICENSE](LICENSE).

---

## What people want to check

| Question | Where |
| --- | --- |
| What happens to my Microsoft login? | `launcher/src/main.js`, search `microsoft` |
| Where is my token stored? | `launcher/src/main.js` - local appdata, never uploaded |
| What gets sent to the server? | `src/client/kotlin/dev/wyz/clientcore/net/` and `cloudflare-updates/src/` |
| How are friends verified? | `cloudflare-updates/src/social.js` - Mojang-signed certs, checked offline |
| What gets injected into the game? | `src/client/java/dev/wyz/clientcore/mixin/` |
| Does it touch my other launchers? | Only *reads* instances you import - `detectExternalMinecraftInstances` |

Nothing is obfuscated. Don't take my word for any of it, that's the point.

---

## Layout

```
src/                  the mod, 1.21.11
clientcore-1.21.4/    the mod, 1.21.4
river-bootstrap/      Java agent that loads the mod
launcher/             the launcher (Electron + React)
cloudflare-updates/   Worker: updates, friends, presence
```

2 mod trees instead of one because Minecraft changed too much between the 2 versions.

---

## Reporting something

**Open a ticket in the [Discord](https://discord.gg/BV5rMr5Mrr).** Usually same
day.

- **Security:** ticket, not a public issue. Email works
  (support@riverclient.xyz) but takes me about five days.
- **Bug:** ticket or a GitHub issue, with the launcher log attached.

---

Not affiliated with Mojang Studios or Microsoft.
