package dev.wyz.clientcore.perf

/**
 * Whether tr7zw's Entity Culling is installed. The River launcher always installs it, and
 * it does the same job as [EntityCuller] and [BlockEntityCuller]'s occlusion test, only on
 * a background thread. Running River's raycasts as well doubled the work and put all of it
 * on the render thread, which showed up as stutter in bases and sign-heavy lobbies. When
 * the mod is there River leaves occlusion to it and keeps only its own distance limits.
 */
object ExternalCulling {
    val present: Boolean by lazy {
        runCatching { net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("entityculling") }.getOrDefault(false)
    }
}
