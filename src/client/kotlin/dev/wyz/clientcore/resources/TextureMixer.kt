//? if >=1.21.11 && <26.1 {
package dev.wyz.clientcore.resources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.wyz.clientcore.config.ConfigService
import dev.wyz.clientcore.resources.TextureCategories.Category
import dev.wyz.clientcore.resources.TextureCategories.Look
import dev.wyz.clientcore.ui.screen.RiverTexturePickerScreen
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.packs.PackSelectionScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.PackRepository
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The texture picker's engine. Every installed pack (and vanilla) is read for which files it
 * has in each category. Picks say which pack each category comes from, and applying them
 * copies those files, plus the models that draw them, into one generated folder pack, "River
 * Picks", which goes on top of everything else so the picks win.
 */
object TextureMixer {
    const val VANILLA = "vanilla"
    private const val OUTPUT_NAME = "River Picks"
    const val OUTPUT_ID = "file/$OUTPUT_NAME"

    /** One pack as the picker sees it: which files it has, per category. */
    class Source(val id: String, val name: String, val pack: Pack, val files: Map<String, List<String>>)

    data class Preset(val name: String, val picks: Map<String, String>)

    /** The packs that were read, vanilla first. Null while they are being read. */
    @Volatile var sources: List<Source>? = null
        private set

    /** Goes up with every read, so card images from an older read are not reused. */
    @Volatile var generation = 0
        private set

    @Volatile var applying = false
        private set

    /** Category id to pack id, as last applied. */
    val picks = LinkedHashMap<String, String>()
    val favorites = LinkedHashSet<String>()
    val presets = ArrayList<Preset>()
    private var loaded = false

    /** Adds the Texture picker button to the vanilla resource pack screen. */
    fun install() {
        ScreenEvents.AFTER_INIT.register { client, screen, width, _ ->
            if (screen !is PackSelectionScreen) return@register
            // The same screen class lists data packs; only the resource pack one gets the button.
            if ((screen.title.contents as? TranslatableContents)?.key != "resourcePack.title") return@register
            Screens.getButtons(screen).add(
                Button.builder(Component.literal("Texture picker")) {
                    // Closing commits whatever was changed in the vanilla list first.
                    screen.onClose()
                    client.setScreen(RiverTexturePickerScreen(client.screen))
                }.bounds(width - 112, 6, 104, 20).build()
            )
        }
    }

    // ------------------------------------------------------------------ reading packs

    /** Reads every pack again on a background thread. [sources] is null until it is done. */
    fun rescan() {
        load()
        closePreviews()
        sources = null
        val packs = Minecraft.getInstance().resourcePackRepository.availablePacks
            .filter { it.id == VANILLA || (it.id.startsWith("file/") && it.id != OUTPUT_ID) }
        Thread({
            // Items, Blocks and Other come from vanilla's own texture list, so they match this version.
            if (TextureCategories.perTexture.isEmpty()) {
                packs.firstOrNull { it.id == VANILLA }?.let { vanilla ->
                    runCatching { vanilla.open().use { TextureCategories.buildPerTexture(list(it, "textures")) } }
                }
            }
            val read = packs.mapNotNull { pack -> runCatching { readSource(pack) }.getOrNull() }
            sources = read.filter { it.id == VANILLA } + read.filter { it.id != VANILLA }.sortedBy { it.name.lowercase() }
            generation++
        }, "River-Textures").apply { isDaemon = true }.start()
    }

    private fun readSource(pack: Pack): Source = pack.open().use { resources ->
        // One listing of the whole textures folder, checked against every category.
        val listed = list(resources, "textures").toHashSet()
        val files = LinkedHashMap<String, List<String>>()
        TextureCategories.every().forEach { category ->
            val found = filesIn(listed, category)
            if (found.isNotEmpty()) files[category.id] = found
        }
        val name = if (pack.id == VANILLA) "Vanilla" else pack.title.string.removeSuffix(".zip")
        Source(pack.id, name, pack, files)
    }

    /** The files of [category] among [listed], with their .mcmeta files (animations) included. */
    private fun filesIn(listed: Set<String>, category: Category): List<String> {
        val found = LinkedHashSet<String>()
        category.files.forEach { path ->
            if (path in listed) found += path
            if ("$path.mcmeta" in listed) found += "$path.mcmeta"
        }
        category.folders.forEach { folder ->
            val prefix = "textures/${folder.dir}/"
            listed.forEach { path ->
                if (!path.startsWith(prefix)) return@forEach
                val name = path.substringAfterLast('/').removeSuffix(".mcmeta")
                if (name.endsWith(".png") && folder.accepts(name)) found += path
            }
        }
        return found.toList().sorted()
    }

    private fun id(path: String): Identifier = Identifier.withDefaultNamespace(path)

    private fun has(resources: PackResources, path: String): Boolean =
        resources.getResource(PackType.CLIENT_RESOURCES, id(path)) != null

    private fun read(resources: PackResources, path: String): ByteArray? =
        resources.getResource(PackType.CLIENT_RESOURCES, id(path))?.get()?.use { it.readBytes() }

    private fun list(resources: PackResources, dir: String): List<String> {
        val out = ArrayList<String>()
        resources.listResources(PackType.CLIENT_RESOURCES, "minecraft", dir) { found, _ -> out += found.path }
        return out
    }

    // ------------------------------------------------------------------ card images

    /*
      Cards are drawn on one background thread, only when they come into view: with a few
      thousand textures across many packs, drawing them all up front would take seconds and a
      lot of memory. The packs a card reads from stay open on that thread until the picker
      closes or the packs are read again.
    */
    private val previewThread = Executors.newSingleThreadExecutor { Thread(it, "River-Previews").apply { isDaemon = true } }
    private val previewBytes = ConcurrentHashMap<String, ByteArray>()
    private val previewQueued = ConcurrentHashMap.newKeySet<String>()
    private val openPacks = HashMap<String, PackResources>()

    /** The card for [source]'s version of [category] as PNG bytes, or null until it has been drawn. */
    fun preview(source: Source, category: Category): ByteArray? {
        val key = "$generation:${source.id}:${category.id}"
        previewBytes[key]?.let { return it }
        // Queued once: a card that cannot be drawn stays a plain tile instead of being retried every frame.
        if (previewQueued.add(key)) {
            val look = lookFor(source, category)
            previewThread.execute {
                runCatching { ModelPreview.render(look) { path -> readForPreview(source, path) } }
                    .getOrNull()?.let { previewBytes[key] = it }
            }
        }
        return null
    }

    /**
     * The category's own look, unless this pack lacks the texture it is built around (a pack
     * with only a netherite sword under Sword); then the first texture the pack does have.
     */
    private fun lookFor(source: Source, category: Category): Look {
        val files = source.files[category.id].orEmpty()
        val main = when (val look = category.look) {
            is Look.Flat -> look.texture
            is Look.Cube -> look.side
            is Look.Item -> look.fallback
            Look.Crystal -> "textures/entity/end_crystal/end_crystal.png"
        }
        if (source.id == VANILLA || main in files) return category.look
        return files.firstOrNull { it.endsWith(".png") }?.let { Look.Flat(it) } ?: category.look
    }

    /** Runs on the preview thread: the file from the card's pack, or vanilla's when the pack does not have it. */
    private fun readForPreview(source: Source, path: String): ByteArray? {
        val own = runCatching { read(openPacks.getOrPut(source.id) { source.pack.open() }, path) }.getOrNull()
        if (own != null || source.id == VANILLA) return own
        val vanilla = sources?.firstOrNull { it.id == VANILLA } ?: return null
        return runCatching { read(openPacks.getOrPut(VANILLA) { vanilla.pack.open() }, path) }.getOrNull()
    }

    /** Closes the packs the preview thread holds open. Called when the picker closes. */
    fun closePreviews() {
        previewBytes.clear()
        previewQueued.clear()
        previewThread.execute {
            openPacks.values.forEach { runCatching { it.close() } }
            openPacks.clear()
        }
    }

    // ------------------------------------------------------------------ applying

    /**
     * Writes [newPicks] into the River Picks pack and puts it on top, or removes it when there
     * are no picks. The copying runs off the render thread; [done] gets null on success, or
     * what went wrong.
     */
    fun apply(newPicks: Map<String, String>, done: (String?) -> Unit) {
        val client = Minecraft.getInstance()
        val repository = client.resourcePackRepository
        val chosen = newPicks.mapNotNull { (categoryId, packId) ->
            val category = TextureCategories.byId(categoryId) ?: return@mapNotNull null
            val pack = repository.getPack(packId) ?: return@mapNotNull null
            category to pack
        }
        val meta = runCatching { packMeta(repository) }.getOrElse { done(it.message ?: "Could not read River's pack info"); return }
        val folder = client.resourcePackDirectory.resolve(OUTPUT_NAME)
        applying = true
        Thread({
            val error = runCatching { writePack(folder, chosen, meta) }.exceptionOrNull()
            client.execute {
                applying = false
                if (error != null) {
                    done(error.message ?: "Could not write the River Picks pack")
                    return@execute
                }
                picks.clear()
                picks.putAll(newPicks)
                save()
                select(client, chosen.isNotEmpty())
                done(null)
            }
        }, "River-Textures-Apply").apply { isDaemon = true }.start()
    }

    private fun writePack(folder: Path, chosen: List<Pair<Category, Pack>>, meta: String) {
        folder.toFile().deleteRecursively()
        if (chosen.isEmpty()) return
        Files.createDirectories(folder)
        Files.writeString(folder.resolve("pack.mcmeta"), meta)
        chosen.groupBy({ it.second }, { it.first }).forEach { (pack, categories) ->
            pack.open().use { resources ->
                val listed = list(resources, "textures").toHashSet()
                val textures = categories.flatMap { filesIn(listed, it) }
                withModels(resources, textures).forEach { path ->
                    val bytes = read(resources, path) ?: return@forEach
                    val target = folder.resolve("assets/minecraft/$path")
                    Files.createDirectories(target.parent)
                    Files.write(target, bytes)
                }
            }
        }
    }

    /**
     * [textures] plus what this pack draws them with: the item definition and model of an item
     * texture, the model and blockstate of a block texture, then every model and texture those
     * point to that the pack itself has. That is what carries a pack's 3D models across.
     */
    private fun withModels(resources: PackResources, textures: List<String>): Set<String> {
        val out = LinkedHashSet(textures)
        val queue = ArrayDeque<String>()
        textures.filter { it.endsWith(".png") }.forEach { path ->
            val rel = path.removePrefix("textures/")
            val name = rel.substringAfterLast('/').removeSuffix(".png")
            when {
                rel.startsWith("item/") -> listOf("items/$name.json", "models/item/$name.json")
                rel.startsWith("block/") -> listOf("models/block/$name.json", "blockstates/$name.json")
                else -> emptyList()
            }.filter { has(resources, it) }.forEach { queue += it }
        }
        while (queue.isNotEmpty()) {
            val path = queue.removeFirst()
            if (!out.add(path)) continue
            val json = read(resources, path)?.let { runCatching { JsonParser.parseString(it.toString(Charsets.UTF_8)) }.getOrNull() } ?: continue
            references(json, false) { ref, isTexture ->
                val clean = ref.removePrefix("minecraft:")
                if (':' in clean || clean.startsWith("#")) return@references
                if (isTexture) {
                    val texture = "textures/$clean.png"
                    if (has(resources, texture)) {
                        out += texture
                        if (has(resources, "$texture.mcmeta")) out += "$texture.mcmeta"
                    }
                } else {
                    val model = "models/$clean.json"
                    if (model !in out && has(resources, model)) queue += model
                }
            }
        }
        return out
    }

    /** Calls [found] for each model ("parent", "model") and texture (inside "textures") a JSON file names. */
    private fun references(element: JsonElement, inTextures: Boolean, found: (String, Boolean) -> Unit) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    when {
                        inTextures -> found(value.asString, true)
                        key == "parent" || key == "model" -> found(value.asString, false)
                    }
                } else {
                    references(value, key == "textures", found)
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { references(it, inTextures, found) }
        }
    }

    /** River's own pack.mcmeta has the right format numbers for this version; only the description changes. */
    private fun packMeta(repository: PackRepository): String {
        val raw = repository.getPack("river_builtin")?.open()?.use { resources ->
            resources.getRootResource("pack.mcmeta")?.get()?.use { it.readBytes().toString(Charsets.UTF_8) }
        } ?: error("River's own pack.mcmeta could not be read")
        val root = JsonParser.parseString(raw).asJsonObject
        root.getAsJsonObject("pack").addProperty("description", "Textures picked in River")
        return root.toString()
    }

    /** Puts River Picks on top of the stack (or takes it off) and reloads resources. */
    private fun select(client: Minecraft, on: Boolean) {
        val repository = client.resourcePackRepository
        repository.reload()
        val ids = repository.selectedIds.filter { it != OUTPUT_ID }.toMutableList()
        if (on && repository.isAvailable(OUTPUT_ID)) ids += OUTPUT_ID
        repository.setSelected(ids)
        client.options.updateResourcePacks(repository)
        client.options.save()
        client.reloadResourcePacks()
    }

    // ------------------------------------------------------------------ saved state

    private fun file(): Path = ConfigService.riverDirectory().resolve("texture-picks.json")

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val root = JsonParser.parseString(Files.readString(file())).asJsonObject
            root.getAsJsonObject("picks")?.entrySet()?.forEach { (key, value) -> picks[key] = value.asString }
            root.getAsJsonArray("favorites")?.forEach { favorites += it.asString }
            root.getAsJsonArray("presets")?.forEach { element ->
                val obj = element.asJsonObject
                val presetPicks = obj.getAsJsonObject("picks").entrySet().associate { (key, value) -> key to value.asString }
                presets += Preset(obj.get("name").asString, presetPicks)
            }
        }
    }

    /** False when the file could not be written. */
    fun save(): Boolean = runCatching {
        val root = JsonObject()
        root.add("picks", JsonObject().apply { picks.forEach { (key, value) -> addProperty(key, value) } })
        root.add("favorites", JsonArray().apply { favorites.forEach { add(it) } })
        root.add("presets", JsonArray().apply {
            presets.forEach { preset ->
                add(JsonObject().apply {
                    addProperty("name", preset.name)
                    add("picks", JsonObject().apply { preset.picks.forEach { (key, value) -> addProperty(key, value) } })
                })
            }
        })
        val path = file()
        Files.createDirectories(path.parent)
        Files.writeString(path, root.toString())
    }.isSuccess

    /** A name not taken yet: "Preset 1", "Preset 2", and so on. */
    fun nextPresetName(): String {
        var n = 1
        while (presets.any { it.name == "Preset $n" }) n++
        return "Preset $n"
    }
}
//?}
