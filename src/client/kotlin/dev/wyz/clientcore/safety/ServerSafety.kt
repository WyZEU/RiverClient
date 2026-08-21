package dev.wyz.clientcore.safety

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.module.Module
import net.minecraft.client.Minecraft

/**
 * Server safety gate. Every module River ships is legit by design — there is no
 * "unsafe mode" to toggle. A module runs when it is enabled and not on the disable
 * list for the server you are currently on. Rules live in the active profile config.
 */
object ServerSafety {

    // currentServerKey is on the hot path (Module.active checks it per module, per frame
    // AND per tick), so the trim/lowercase result is cached until the connection changes.
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

    /** Human-readable label for the current connection, or null when not in a world. */
    fun currentServerLabel(client: Minecraft = Minecraft.getInstance()): String? {
        if (client.level == null) return null
        val server = client.currentServer ?: return "Singleplayer"
        return server.name.ifBlank { server.ip }
    }

    fun isBlockedOn(serverKey: String, moduleId: String): Boolean =
        ClientCore.config.serverBlockedMap()[serverKey]?.contains(moduleId) == true

    fun isBlockedHere(module: Module): Boolean {
        val key = currentServerKey() ?: return false
        return isBlockedOn(key, module.id)
    }

    fun setBlockedHere(module: Module, blocked: Boolean) {
        val key = currentServerKey() ?: return
        setBlocked(key, module.id, blocked)
    }

    fun setBlocked(serverKey: String, moduleId: String, blocked: Boolean) {
        val map = ClientCore.config.serverBlockedMap()
        if (blocked) {
            map.getOrPut(serverKey) { mutableListOf() }.let { if (!it.contains(moduleId)) it.add(moduleId) }
        } else {
            map[serverKey]?.remove(moduleId)
            if (map[serverKey]?.isEmpty() == true) map.remove(serverKey)
        }
        // Persist immediately rather than waiting for the screen to close. RiverRuntime.tick
        // reloads the config from disk every 10 ticks and REPLACES the object, so an
        // in-memory-only rule is thrown away the moment any other save rewrites the file -
        // which is why "Disable on this server" appeared to do nothing.
        RiverRuntime.saveConfig()
    }

    /** All stored per-server rules: server key -> blocked module ids. */
    fun allRules(): Map<String, List<String>> = ClientCore.config.serverBlockedMap()

    /** Cinematic mode: every module pauses and the watermark hides, for clean footage. */
    var cinematicMode: Boolean
        get() = ClientCore.config.cinematicMode
        set(value) {
            ClientCore.config.cinematicMode = value
        }

    fun allows(module: Module): Boolean = !cinematicMode && !isBlockedHere(module)
}
