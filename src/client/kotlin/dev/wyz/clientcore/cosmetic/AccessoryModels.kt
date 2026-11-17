package dev.wyz.clientcore.cosmetic

import com.google.gson.JsonParser
import dev.wyz.clientcore.compat.McId
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.PartPose
import net.minecraft.client.model.geom.builders.CubeListBuilder
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.client.model.geom.builders.MeshDefinition

/** Local prototypes. Selections never enter presence, entitlements or the saved cape list. */
object AccessoryModels {
    class Item(val id: String, val name: String, val slot: String, val model: ModelPart)
    private val enabled = java.lang.Boolean.getBoolean("river.cosmetics.preview")
    private val palette = McId.fromNamespaceAndPath("clientcore", "textures/cosmetics/palette.png")
    @JvmStatic fun isEnabled() = enabled
    var headId = System.getProperty("river.cosmetics.head", "none")
    var wingsId = System.getProperty("river.cosmetics.wings", "none")

    val items: List<Item> by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/assets/clientcore/cosmetics/accessories.json"))
        val document = stream.reader().use { JsonParser.parseReader(it).asJsonObject }
        document.getAsJsonArray("items").map { entry ->
            val value = entry.asJsonObject
            val mesh = MeshDefinition()
            val parts = value.getAsJsonArray("parts")
            require(parts.size() <= 256)
            parts.forEachIndexed { index, raw ->
                val part = raw.asJsonObject
                fun v(key: String, i: Int) = part.getAsJsonArray(key)[i].asFloat.also { require(it.isFinite()) }
                val cube = CubeListBuilder.create().texOffs(v("uv", 0).toInt(), v("uv", 1).toInt())
                    .addBox(v("origin", 0), v("origin", 1), v("origin", 2), v("size", 0), v("size", 1), v("size", 2))
                mesh.root.addOrReplaceChild("part$index", cube,
                    PartPose.offsetAndRotation(v("pivot", 0), v("pivot", 1), v("pivot", 2), v("rotation", 0), v("rotation", 1), v("rotation", 2)))
            }
            Item(value["id"].asString, value["name"].asString, value["slot"].asString,
                LayerDefinition.create(mesh, document["textureSize"].asInt, document["textureSize"].asInt).bakeRoot())
        }
    }

    @JvmStatic fun texture() = palette
    @JvmStatic fun headModel(): ModelPart? = if (isEnabled()) items.firstOrNull { it.id == headId && it.slot == "head" }?.model else null
    @JvmStatic fun wingsModel(): ModelPart? = if (isEnabled()) items.firstOrNull { it.id == wingsId && it.slot == "wings" }?.model else null
    fun selectedName(slot: String) = items.firstOrNull { it.id == if (slot == "head") headId else wingsId }?.name ?: "None"
}
