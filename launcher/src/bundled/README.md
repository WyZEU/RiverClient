# Bundled River runtime jars

Everything in this folder is copied verbatim into the packaged launcher
(`build-app.js` → `app/bundled`, then electron-builder ships `app/bundled` → `bundled`)
and resolved at runtime by `resolveRiverRuntimeJars(mcVersion)` in `src/main.js`.

## clientcore (River's own client mod) — per Minecraft version

The launcher installs the clientcore jar whose **Minecraft version matches the instance**.
The version is read from a Fabric-style `+<mc>` suffix in the file name:

| File name                          | Targets  |
| ---------------------------------- | -------- |
| `clientcore-<modver>.jar`          | 1.21.11 (legacy/default — no suffix) |
| `clientcore-<modver>+1.21.11.jar`  | 1.21.11  |
| `clientcore-<modver>+1.21.4.jar`   | 1.21.4   |

Drop each version's jar here and rebuild the launcher (`npm run dist`). The launcher:

- installs the jar matching the selected instance's Minecraft version, and
- **removes** any clientcore jar built for a *different* version from that instance
  (a mismatched jar crashes the game at launch), and
- if no jar exists for the selected version, launches Fabric + the optimization/support
  suite **without** clientcore (River's own mod simply stays out until its build lands).

So a 1.21.4 build "drops in" the moment `clientcore-<modver>+1.21.4.jar` is placed here —
no launcher code change required. Fabric loader/API, Kotlin, and the optimization/PvP mod
suite already resolve per Minecraft version through Modrinth.

> The 1.21.4 build (`clientcore-1.21.4/` at the repo root) is a fork of the 1.21.11 source
> tree, not a shared/preprocessed one — Mojang renamed enough (ResourceLocation vs
> Identifier, Player vs Avatar, the PoseStack/RenderType-vs-RenderPipelines render-pipeline
> split) between these versions that a preprocessor wasn't worth it. Fixes and features
> that apply to both versions need to be ported by hand between the two trees. Rebuild it
> with `./gradlew :clientcore-1.21.4:build` and copy the jar from
> `clientcore-1.21.4/build/libs/` here.
