package dev.wyz.clientcore.config

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.safety.ServerSafety
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Per-server profiles, the way Badlion had them: link a profile to a server and it
 * switches in when you join that server and back out when you leave.
 *
 * Links live in config/river-client/server-profiles.json as host -> profile, apart from
 * the profiles themselves, so exporting or sharing a profile never carries someone's
 * server list with it. A link to "hypixel.net" also covers "mc.hypixel.net".
 */
object ServerProfiles {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var links: MutableMap<String, String>? = null

    /** The server key seen last tick, so work only happens when it changes. */
    private var lastKey: String? = null
    /** The profile that was active before an automatic switch, to go back to on leaving. */
    private var returnTo: String? = null
    /** The profile switched to automatically; only that one is switched back from. */
    private var switchedTo: String? = null

    private fun path(): Path = Minecraft.getInstance().gameDirectory.toPath()
        .resolve("config").resolve("river-client").resolve("server-profiles.json")

    private fun links(): MutableMap<String, String> {
        links?.let { return it }
        val loaded: MutableMap<String, String> = runCatching {
            Files.newBufferedReader(path()).use { reader ->
                gson.fromJson<MutableMap<String, String>>(reader, object : TypeToken<MutableMap<String, String>>() {}.type)
            }
        }.getOrNull() ?: LinkedHashMap()
        links = loaded
        return loaded
    }

    private fun save() {
        runCatching {
            Files.createDirectories(path().parent)
            Files.newBufferedWriter(path()).use { gson.toJson(links(), it) }
        }
    }

    /** "Play.Hypixel.net:25565" and "play.hypixel.net." are the same server. */
    fun hostOf(serverKey: String): String =
        serverKey.trim().lowercase().removeSuffix(":25565").trimEnd('.')

    /** The server you are on right now, or null in singleplayer and menus. */
    fun currentHost(client: Minecraft = Minecraft.getInstance()): String? {
        val key = ServerSafety.currentServerKey(client) ?: return null
        return if (key == "singleplayer") null else hostOf(key)
    }

    fun hostsFor(profile: String): List<String> = links().filterValues { it == profile }.keys.sorted()

    private fun profileFor(host: String): String? {
        val all = links()
        all[host]?.let { return it }
        // The longest linked domain that this host sits under wins.
        return all.entries
            .filter { host.endsWith(".${it.key}") }
            .maxByOrNull { it.key.length }
            ?.value
    }

    /**
     * Links the server you are on to [profile], or unlinks it if it already was. Returns
     * what happened, for the profiles card to show.
     */
    fun toggleCurrent(profile: String): String {
        val host = currentHost() ?: return "Join a server first, then link it here"
        val all = links()
        return if (all[host] == profile) {
            all.remove(host)
            save()
            "$host no longer switches to $profile"
        } else {
            all[host] = profile
            save()
            "$profile now loads on $host"
        }
    }

    fun onProfileRenamed(oldName: String, newName: String) {
        val all = links()
        var changed = false
        for (entry in all.entries) if (entry.value == oldName) { entry.setValue(newName); changed = true }
        if (changed) save()
        if (returnTo == oldName) returnTo = newName
        if (switchedTo == oldName) switchedTo = newName
    }

    fun onProfileDeleted(name: String) {
        if (links().values.removeAll { it == name }) save()
        if (returnTo == name) returnTo = null
        if (switchedTo == name) switchedTo = null
    }

    fun tick(client: Minecraft) {
        val key = ServerSafety.currentServerKey(client)
        if (key == lastKey) return
        lastKey = key

        val host = if (key == null || key == "singleplayer") null else hostOf(key)
        val wanted = host?.let { profileFor(it) }?.takeIf { ConfigService.listProfiles().contains(it) }
        val active = ConfigService.activeProfile()

        if (wanted != null) {
            if (wanted == active) return
            if (returnTo == null) returnTo = active
            if (RiverRuntime.switchProfile(wanted)) {
                switchedTo = wanted
                tell(client, "Using profile $wanted on $host")
            }
            return
        }

        // Left a linked server (or moved to one with no link): go back, unless the player
        // picked a different profile by hand in the meantime.
        val back = returnTo ?: return
        if (active == switchedTo && ConfigService.listProfiles().contains(back)) {
            RiverRuntime.switchProfile(back)
            if (key != null) tell(client, "Back to profile $back")
        }
        returnTo = null
        switchedTo = null
    }

    private fun tell(client: Minecraft, text: String) {
        if (client.level == null) return
        client.gui.chat.addMessage(
            Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY))
        )
    }
}
