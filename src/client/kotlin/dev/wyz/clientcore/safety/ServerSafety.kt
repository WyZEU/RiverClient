package dev.wyz.clientcore.safety

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.module.Module
import net.minecraft.client.Minecraft

/**
 * Cinematic mode, and the key identifying the connection you are on.
 *
 * This used to also hold per-server module rules: a list, per server address, of modules
 * to keep switched off there. That is gone. Every module River ships is legit by design,
 * so the feature only ever earned its place if people actually used it, and it asked
 * someone to think about which server they were on before toggling anything.
 *
 * The server key stays because waypoints are stored per connection, which is unrelated.
 */
object ServerSafety {

    // Cached until the connection changes. This was on the hot path when Module.active
    // consulted it per module per frame; now only waypoints ask for it, so the cache is
    // no longer load-bearing - it is kept because the answer genuinely cannot change
    // between connections and recomputing a trim/lowercase per lookup buys nothing.
    private var cachedKeyServer: Any? = null
    private var cachedKeyInLevel = false
    private var cachedKey: String? = null

    /** Stable key for the current connection: server address, or "singleplayer" for local worlds. */
    fun currentServerKey(client: Minecraft = Minecraft.getInstance()): String? {
        val inLevel = client.level != null
        val server = client.currentServer
        if (inLevel != cachedKeyInLevel || server !== cachedKeyServer) {
            cachedKeyInLevel = inLevel
            cachedKeyServer = server
            cachedKey = when {
                !inLevel -> null
                server == null -> "singleplayer"
                else -> server.ip.trim().lowercase().ifEmpty { "singleplayer" }
            }
        }
        return cachedKey
    }

    /** Cinematic mode: every module pauses and the watermark hides, for clean footage. */
    var cinematicMode: Boolean
        get() = ClientCore.config.cinematicMode
        set(value) {
            ClientCore.config.cinematicMode = value
        }

    /**
     * Whether a module may run right now. Cinematic mode is the only thing that stops one
     * these days, so [module] is not consulted; the parameter stays because the call sites
     * read as a question about that module and the next per-module condition belongs here.
     */
    @Suppress("UNUSED_PARAMETER")
    fun allows(module: Module): Boolean = !cinematicMode
}
