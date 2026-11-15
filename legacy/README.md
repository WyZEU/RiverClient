# River for Minecraft 1.8.9

A separate build from the main client (`../src`, Stonecutter, 1.21+). 1.8.9 has no Fabric
API, runs on Java 8, uses LWJGL 2 and renders with fixed-function GL, so the main client's
code cannot be compiled for it; it is ported here, on Legacy Fabric.

    ./gradlew build        # build/libs/river-legacy-<version>.jar
    ./gradlew runClient    # dev client on Java 8 (downloaded by Gradle)

Gradle itself needs Java 17+ (JAVA_HOME=jdk-21). Logging through log4j is broken in the dev
client (1.8.9 ships log4j 2.0-beta9), so dev output uses stdout.

## Plan

1. **Scaffold** - Legacy Fabric 1.8.9 + Kotlin (stdlib bundled), boots in dev. Done.
2. **Launcher** - 1.8.9 instances: Legacy Fabric loader from meta.legacyfabric.net, a Java 8
   runtime, LWJGL 2 natives, the legacy `minecraftArguments` launch format, and this jar
   bundled like the clientcore jars.
3. **Drawing layer** - a small 2D API over Tessellator/GlStateManager (rects, rounded rects,
   text, textures, scissor) so the main client's UI code ports with few changes.
4. **Port, in order** - title screen, Right Shift menu + config/profiles, HUD modules and the
   HUD editor, cosmetics (capes, badge), friends and presence, then the rest.
