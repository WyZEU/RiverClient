package dev.wyz.clientcore.config

import com.google.gson.GsonBuilder
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.reader
import kotlin.io.path.writer

/**
 * Profile-based config storage. Every profile is a full [ClientCoreConfig] JSON file under
 * `config/river-client/profiles/`. The active profile name lives in `config/river-client/state.json`.
 * The pre-profile config (`config/clientcore.json`) is migrated into the "default" profile once.
 */
object ConfigService {
    const val DEFAULT_PROFILE = "default"

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var lastKnownWriteMs = 0L
    private var activeProfileName: String = DEFAULT_PROFILE

    private data class ProfileState(var active: String = DEFAULT_PROFILE)

    private fun configRoot(): Path = Minecraft.getInstance().gameDirectory.toPath().resolve("config")

    private fun riverDir(): Path = configRoot().resolve("river-client")

    private fun profilesDir(): Path = riverDir().resolve("profiles")

    private fun statePath(): Path = riverDir().resolve("state.json")

    private fun legacyPath(): Path = configRoot().resolve("clientcore.json")

    private fun profilePath(name: String): Path = profilesDir().resolve("${sanitizeName(name)}.json")

    /** Filesystem-safe profile name: letters, digits, space, dash, underscore; max 24 chars. */
    fun sanitizeName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(Regex("\\s+"), " ")
            .take(24)
        return cleaned.ifEmpty { DEFAULT_PROFILE }
    }

    fun activeProfile(): String = activeProfileName

    fun listProfiles(): List<String> = runCatching {
        profilesDir().listDirectoryEntries()
            .filter { it.extension == "json" }
            .map { it.nameWithoutExtension }
            .sortedBy { it.lowercase() }
    }.getOrDefault(emptyList())

    fun load(): ClientCoreConfig {
        migrateLegacyIfNeeded()
        activeProfileName = readState()
        var path = profilePath(activeProfileName)
        if (!path.exists()) {
            val fallback = listProfiles().firstOrNull()
            if (fallback != null) {
                activeProfileName = fallback
                path = profilePath(fallback)
            } else {
                activeProfileName = DEFAULT_PROFILE
                val fresh = ClientCoreConfig()
                writeProfile(activeProfileName, fresh)
                writeState()
                lastKnownWriteMs = fileWriteMs()
                return fresh
            }
        }
        writeState()
        val config = readProfile(path)
        lastKnownWriteMs = fileWriteMs()
        return config
    }

    fun save(config: ClientCoreConfig) {
        writeProfile(activeProfileName, config)
        lastKnownWriteMs = fileWriteMs()
    }

    /** Saves [current] to the active profile, then loads and activates [name]. Null if it doesn't exist. */
    fun switchProfile(name: String, current: ClientCoreConfig): ClientCoreConfig? {
        val target = sanitizeName(name)
        val path = profilePath(target)
        if (!path.exists()) return null
        save(current)
        activeProfileName = target
        writeState()
        val config = readProfile(path)
        lastKnownWriteMs = fileWriteMs()
        return config
    }

    /** Creates a new profile. [seed] (usually the current config) is copied in; null seeds defaults. */
    fun createProfile(name: String, seed: ClientCoreConfig? = null): Boolean {
        val target = sanitizeName(name)
        val path = profilePath(target)
        if (path.exists()) return false
        val copied = seed?.let { readBack(it) } ?: ClientCoreConfig()
        writeProfile(target, copied)
        return true
    }

    fun duplicateProfile(source: String, newName: String): Boolean {
        val src = profilePath(source)
        val dst = profilePath(newName)
        if (!src.exists() || dst.exists()) return false
        runCatching { Files.copy(src, dst, StandardCopyOption.COPY_ATTRIBUTES) }.getOrElse { return false }
        return true
    }

    /** The active profile as a shareable JSON string, tagged so imports can be validated. */
    fun exportActive(config: ClientCoreConfig): String {
        val wrapper = mapOf("river_profile" to 1, "name" to activeProfileName, "config" to config)
        return gson.toJson(wrapper)
    }

    /**
     * Imports a `river_profile` JSON string as a new profile. Returns the created
     * profile name, or null if the string is not a valid River profile export.
     */
    fun importProfile(raw: String): String? {
        val json = raw.trim()
        val parsed = runCatching {
            com.google.gson.JsonParser.parseString(json).asJsonObject
        }.getOrNull() ?: return null
        if (!parsed.has("river_profile") || !parsed.has("config")) return null
        val config = runCatching {
            gson.fromJson(parsed.get("config"), ClientCoreConfig::class.java)
        }.getOrNull() ?: return null

        val suggested = parsed.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: "Imported"
        var name = sanitizeName(suggested)
        var i = 2
        while (profilePath(name).exists()) {
            name = sanitizeName("$suggested $i")
            i += 1
        }
        writeProfile(name, config)
        return name
    }

    /** Renames a profile. If it is active, the active pointer follows. */
    fun renameProfile(oldName: String, newName: String): Boolean {
        val src = profilePath(oldName)
        val dst = profilePath(newName)
        if (!src.exists() || dst.exists() || src == dst) return false
        runCatching { Files.move(src, dst) }.getOrElse { return false }
        if (sanitizeName(oldName) == activeProfileName) {
            activeProfileName = sanitizeName(newName)
            writeState()
        }
        return true
    }

    /** Deletes a profile. Refuses to delete the last remaining or the active profile. */
    fun deleteProfile(name: String): Boolean {
        val target = sanitizeName(name)
        if (target == activeProfileName) return false
        if (listProfiles().size <= 1) return false
        return runCatching { Files.deleteIfExists(profilePath(target)) }.getOrDefault(false)
    }

    fun reloadIfChanged(): ClientCoreConfig? {
        val path = profilePath(activeProfileName)
        if (!path.exists()) return null
        val currentWriteMs = fileWriteMs()
        if (currentWriteMs <= 0L || currentWriteMs == lastKnownWriteMs) return null
        val raw = runCatching {
            path.reader().use { gson.fromJson(it, ClientCoreConfig::class.java) ?: ClientCoreConfig() }
        }.getOrNull() ?: return null
        normalizeConfig(raw)
        lastKnownWriteMs = currentWriteMs
        return raw
    }

    private fun migrateLegacyIfNeeded() {
        runCatching {
            Files.createDirectories(profilesDir())
            val hasProfiles = listProfiles().isNotEmpty()
            val legacy = legacyPath()
            if (!hasProfiles && legacy.exists()) {
                Files.copy(legacy, profilePath(DEFAULT_PROFILE), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun readState(): String = runCatching {
        if (!statePath().exists()) return DEFAULT_PROFILE
        statePath().reader().use { gson.fromJson(it, ProfileState::class.java) }?.active
            ?.let { sanitizeName(it) } ?: DEFAULT_PROFILE
    }.getOrDefault(DEFAULT_PROFILE)

    private fun writeState() {
        runCatching {
            Files.createDirectories(riverDir())
            statePath().writer().use { gson.toJson(ProfileState(activeProfileName), it) }
        }
    }

    private fun readProfile(path: Path): ClientCoreConfig {
        val raw = runCatching {
            path.reader().use { gson.fromJson(it, ClientCoreConfig::class.java) ?: ClientCoreConfig() }
        }.getOrDefault(ClientCoreConfig())
        normalizeConfig(raw)
        return raw
    }

    private fun writeProfile(name: String, config: ClientCoreConfig) {
        runCatching {
            Files.createDirectories(profilesDir())
            profilePath(name).writer().use { gson.toJson(config, it) }
        }
    }

    /** Deep copy via JSON so profile duplicates never share mutable state. */
    private fun readBack(config: ClientCoreConfig): ClientCoreConfig =
        gson.fromJson(gson.toJson(config), ClientCoreConfig::class.java) ?: ClientCoreConfig()

    private fun normalizeConfig(config: ClientCoreConfig) {
        config.modules.values.forEach { normalize(it) }
        config.favoritesList()
        config.serverBlockedMap()
        config.waypointsMap()
    }

    private fun normalize(c: ModuleConfig) {
        if (c.scalePercent <= 0) c.scalePercent = 100
        c.style?.let { s ->
            s.backgroundOpacity = s.backgroundOpacity.coerceIn(0, 255)
            s.borderOpacity = s.borderOpacity.coerceIn(0, 255)
            s.borderThickness = s.borderThickness.coerceIn(1, 4)
            s.padding = s.padding.coerceIn(2, 20)
            s.spacing = s.spacing.coerceIn(0, 16)
        }
        c.armorHud?.let { a ->
            a.slotSize = a.slotSize.coerceIn(14, 40)
            a.slotSpacing = a.slotSpacing.coerceIn(0, 12)
            a.slotBackgroundOpacity = a.slotBackgroundOpacity.coerceIn(0, 255)
            a.borderOpacity = a.borderOpacity.coerceIn(0, 255)
            a.borderThickness = a.borderThickness.coerceIn(1, 4)
            a.cornerRadius = a.cornerRadius.coerceIn(0, 8)
            a.durabilityThickness = a.durabilityThickness.coerceIn(1, 5)
            a.outerBackgroundOpacity = a.outerBackgroundOpacity.coerceIn(0, 255)
            a.itemIconScalePercent = a.itemIconScalePercent.coerceIn(60, 130)
            if (a.warnDurabilityPercent !in 5..45) a.warnDurabilityPercent = 20
            a.warnSoundIntervalTicks = a.warnSoundIntervalTicks.coerceIn(15, 200)
        }
        c.zoom?.let { z -> z.zoomFov = z.zoomFov.coerceIn(10, 60) }
    }

    private fun fileWriteMs(): Long = runCatching {
        Files.getLastModifiedTime(profilePath(activeProfileName)).toMillis()
    }.getOrDefault(0L)
}
