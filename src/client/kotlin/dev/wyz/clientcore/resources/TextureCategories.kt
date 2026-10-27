//? if >=1.21.11 && <26.1 {
package dev.wyz.clientcore.resources

/**
 * What the texture picker can swap. A category is a set of texture files under
 * assets/minecraft/: some named one by one, some every file in a folder that passes a name
 * test. A pack counts for a category when it has at least one of its files, and picking that
 * pack copies every one of them it has, with the models that go with them (TextureMixer).
 *
 * The PvP and Extras groups are fixed. Items, Blocks and Other are one category per texture,
 * built from vanilla's own list of textures the first time the packs are read, so they always
 * match the running Minecraft version.
 */
object TextureCategories {

    enum class Group(val label: String) { PVP("PvP"), EXTRAS("Extras"), ITEMS("Items"), BLOCKS("Blocks"), OTHER("Other") }

    /** How a category's card is drawn (ModelPreview). Paths are under assets/minecraft/. */
    sealed class Look {
        /** The texture as it is, first frame only. */
        class Flat(val texture: String) : Look()

        /** A block: [top] on the top face, [side] on the four sides and the bottom. */
        class Cube(val top: String, val side: String = top) : Look()

        /** The item as the game draws it in a slot: its own model, flat or 3D. */
        class Item(val id: String, val fallback: String) : Look()

        /** The end crystal: its glass frames and core, from the entity texture. */
        object Crystal : Look()
    }

    /** Every file in [dir] (under textures/) whose name passes [accepts]. */
    class Folder(val dir: String, val accepts: (String) -> Boolean)

    class Category(
        val id: String,
        val label: String,
        val group: Group,
        val look: Look,
        files: List<String> = emptyList(),
        val folders: List<Folder> = emptyList(),
        /** Extra words the category search matches, such as the folder a texture is in. */
        val keywords: String = ""
    ) {
        /** Full paths under assets/minecraft/. */
        val files: List<String> = files.map { "textures/$it" }

        fun matches(query: String): Boolean =
            label.contains(query, ignoreCase = true) || keywords.contains(query, ignoreCase = true)
    }

    private val TIERS = listOf("wooden", "stone", "copper", "iron", "golden", "diamond", "netherite")
    private val ARMOR = listOf("leather", "chainmail", "copper", "iron", "golden", "diamond", "netherite")
    private val PIECES = listOf("helmet", "chestplate", "leggings", "boots")

    private fun t(path: String) = "textures/$path"
    private fun item(id: String, texture: String = "item/$id.png") = Look.Item(id, t(texture))
    private fun cube(top: String, side: String = top) = Look.Cube(t(top), t(side))
    private fun flat(path: String) = Look.Flat(t(path))

    val fixed: List<Category> = listOf(
        Category(
            "crystal", "Crystal", Group.PVP, Look.Crystal,
            listOf("item/end_crystal.png", "entity/end_crystal/end_crystal.png", "entity/end_crystal/end_crystal_beam.png")
        ),
        Category(
            "anchor", "Anchor", Group.PVP, cube("block/respawn_anchor_top.png", "block/respawn_anchor_side4.png"),
            listOf("block/respawn_anchor_top.png", "block/respawn_anchor_top_off.png", "block/respawn_anchor_bottom.png") +
                (0..4).map { "block/respawn_anchor_side$it.png" }
        ),
        Category("obsidian", "Obsidian", Group.PVP, cube("block/obsidian.png"), listOf("block/obsidian.png", "block/crying_obsidian.png")),
        Category("totem", "Totem", Group.PVP, item("totem_of_undying"), listOf("item/totem_of_undying.png")),
        Category("pearl", "Pearl", Group.PVP, item("ender_pearl"), listOf("item/ender_pearl.png")),
        Category("gapple", "Gapple", Group.PVP, item("golden_apple"), listOf("item/golden_apple.png", "item/enchanted_golden_apple.png")),
        Category("sword", "Sword", Group.PVP, item("diamond_sword"), TIERS.map { "item/${it}_sword.png" }),
        Category("axe", "Axe", Group.PVP, item("diamond_axe"), TIERS.map { "item/${it}_axe.png" }),
        Category("mace", "Mace", Group.PVP, item("mace"), listOf("item/mace.png")),
        Category(
            "armor", "Armor", Group.PVP, item("diamond_chestplate"),
            ARMOR.flatMap { material -> PIECES.map { "item/${material}_$it.png" } },
            listOf(Folder("entity/equipment/humanoid") { true }, Folder("entity/equipment/humanoid_leggings") { true })
        ),
        Category("shield", "Shield", Group.PVP, flat("entity/shield_base_nopattern.png"), listOf("entity/shield_base.png", "entity/shield_base_nopattern.png")),
        Category("bow", "Bow", Group.PVP, item("bow"), listOf("item/bow.png") + (0..2).map { "item/bow_pulling_$it.png" }),
        Category(
            "crossbow", "Crossbow", Group.PVP, item("crossbow", "item/crossbow_standby.png"),
            listOf("item/crossbow_standby.png", "item/crossbow_arrow.png", "item/crossbow_firework.png") +
                (0..2).map { "item/crossbow_pulling_$it.png" }
        ),

        Category("glass", "Glass", Group.EXTRAS, cube("block/glass.png"), folders = listOf(Folder("block") { "glass" in it })),
        Category("wool", "Wool", Group.EXTRAS, cube("block/white_wool.png"), folders = listOf(Folder("block") { it.endsWith("_wool.png") })),
        Category(
            "wood", "Wood", Group.EXTRAS, cube("block/oak_log_top.png", "block/oak_log.png"),
            folders = listOf(Folder("block") { it.endsWith("_planks.png") || it.endsWith("_log.png") || it.endsWith("_log_top.png") })
        ),
        Category(
            "terrain", "Terrain", Group.EXTRAS, cube("block/grass_block_top.png", "block/grass_block_side.png"),
            listOf(
                "dirt", "grass_block_top", "grass_block_side", "grass_block_side_overlay", "stone", "cobblestone",
                "gravel", "sand", "deepslate", "deepslate_top", "netherrack", "end_stone", "bedrock", "andesite",
                "diorite", "granite", "tuff"
            ).map { "block/$it.png" }
        ),
        Category(
            "liquids", "Water and lava", Group.EXTRAS, cube("block/lava_still.png"),
            listOf("water_still", "water_flow", "water_overlay", "lava_still", "lava_flow").map { "block/$it.png" }
        ),
        Category("buttons", "Buttons", Group.EXTRAS, flat("gui/sprites/widget/button.png"), folders = listOf(Folder("gui/sprites/widget") { "button" in it })),
        Category("effects", "Effect icons", Group.EXTRAS, flat("mob_effect/speed.png"), folders = listOf(Folder("mob_effect") { true })),
        Category("particles", "Particles", Group.EXTRAS, flat("particle/critical_hit.png"), folders = listOf(Folder("particle") { true })),
        Category(
            "overlays", "Overlays", Group.EXTRAS, flat("block/fire_0.png"),
            listOf(
                "block/fire_0.png", "block/fire_1.png", "block/soul_fire_0.png", "block/soul_fire_1.png",
                "misc/pumpkinblur.png", "misc/vignette.png", "misc/underwater.png", "misc/powder_snow_outline.png"
            )
        ),
        Category("font", "Font", Group.EXTRAS, flat("font/ascii.png"), folders = listOf(Folder("font") { true })),
        Category("title", "Title screen", Group.EXTRAS, flat("gui/title/minecraft.png"), folders = listOf(Folder("gui/title") { true }))
    )

    /** One category per texture vanilla has: Items, Blocks and Other. Empty until the first read. */
    @Volatile var perTexture: List<Category> = emptyList()

    /**
     * Builds [perTexture] from vanilla's texture list ([paths] are under assets/minecraft/,
     * such as "textures/item/apple.png").
     */
    fun buildPerTexture(paths: Collection<String>) {
        perTexture = paths.filter { it.endsWith(".png") }.sorted().map { path ->
            val rel = path.removePrefix("textures/")
            val dir = rel.substringBeforeLast('/', "")
            val name = rel.substringAfterLast('/').removeSuffix(".png")
            val label = name.replace('_', ' ').replaceFirstChar { it.uppercase() }
            when (dir) {
                "item" -> Category("tex:$rel", label, Group.ITEMS, Look.Item(name, path), listOf(rel))
                "block" -> Category("tex:$rel", label, Group.BLOCKS, Look.Cube(path, path), listOf(rel))
                else -> Category("tex:$rel", "$label ($dir)", Group.OTHER, Look.Flat(path), listOf(rel), keywords = dir)
            }
        }
    }

    fun every(): List<Category> = fixed + perTexture

    private var index: Map<String, Category> = emptyMap()
    private var indexedFrom: List<Category>? = null

    fun byId(id: String): Category? {
        val source = perTexture
        if (indexedFrom !== source) {
            index = every().associateBy { it.id }
            indexedFrom = source
        }
        return index[id]
    }
}
//?}
