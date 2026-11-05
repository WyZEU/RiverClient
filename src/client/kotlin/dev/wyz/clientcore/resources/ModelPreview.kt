//? if >=1.21.11 && <26.1 {
package dev.wyz.clientcore.resources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.wyz.clientcore.resources.TextureCategories.Look
import java.awt.AlphaComposite
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a texture picker card for one pack, the way the game would show it: a block as a
 * shaded cube, an item through its own model (flat, or 3D when the pack gives it elements),
 * the end crystal as its frames and core. Everything is read through [render]'s reader, which
 * looks in the pack first and in vanilla second, so a pack that only changes a texture still
 * gets the vanilla shape around it.
 *
 * It is a small software renderer on purpose: the game only ever has the packs that are on
 * loaded, and these cards show every pack side by side. The view is orthographic, so each
 * face of a box lands on screen as a parallelogram and its texture maps onto it exactly with
 * one affine transform. Faces are drawn back to front.
 */
object ModelPreview {
    const val SIZE = 64

    /** The card for [look] as PNG bytes, or null when there is nothing to draw. */
    fun render(look: Look, read: (String) -> ByteArray?): ByteArray? = runCatching {
        val textures = HashMap<String, BufferedImage?>()
        val texture: (String) -> BufferedImage? = { path -> textures.getOrPut(path) { decode(read(path)) } }
        val image = when (look) {
            is Look.Flat -> flat(texture(look.texture))
            is Look.Cube -> cube(look, texture)
            is Look.Item -> item(look, read, texture)
            Look.Crystal -> crystal(texture)
        } ?: return null
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        out.toByteArray()
    }.getOrNull()

    // ------------------------------------------------------------------ the kinds of card

    private fun flat(texture: BufferedImage?): BufferedImage? {
        texture ?: return null
        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        val scale = min(SIZE.toDouble() / texture.width, SIZE.toDouble() / texture.height)
        val w = (texture.width * scale).toInt().coerceAtLeast(1)
        val h = (texture.height * scale).toInt().coerceAtLeast(1)
        g.drawImage(texture, (SIZE - w) / 2, (SIZE - h) / 2, w, h, null)
        g.dispose()
        return image
    }

    private fun cube(look: Look.Cube, texture: (String) -> BufferedImage?): BufferedImage? {
        val side = texture(look.side) ?: return null
        val top = texture(look.top) ?: side
        val faces = boxFaces(
            doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(16.0, 16.0, 16.0)
        ) { dir -> FaceTexture(if (dir == UP) top else side, null, tintFor(if (dir == UP) look.top else look.side)) }
        return draw(faces, rotation(30.0, 225.0, 0.0))
    }

    private fun item(look: Look.Item, read: (String) -> ByteArray?, texture: (String) -> BufferedImage?): BufferedImage? {
        val model = itemModelRef(look.id, read)?.let { resolve(it, read) }
        if (model == null) return flat(texture(look.fallback))
        val elements = model.elements
        if (elements == null || elements.size() == 0) {
            // A flat item: its first layer, the way the inventory shows it.
            val layer = model.texture("#layer0") ?: return flat(texture(look.fallback))
            return flat(texture(layer) ?: texture(look.fallback))
        }
        val faces = ArrayList<Face>()
        elements.forEach { element -> faces += elementFaces(element.asJsonObject, model, texture) }
        if (faces.isEmpty()) return flat(texture(look.fallback))
        val gui = model.gui ?: doubleArrayOf(30.0, 225.0, 0.0)
        return draw(faces, rotation(gui[0], gui[1], gui[2]))
    }

    /** The end crystal as EndCrystalRenderer stacks it: two glass frames and the core, each turned again. */
    private fun crystal(texture: (String) -> BufferedImage?): BufferedImage? {
        val tex = texture("textures/entity/end_crystal/end_crystal.png") ?: return null
        val turn = axisAngle(doubleArrayOf(0.7071, 0.0, 0.7071), 60.0)
        val faces = ArrayList<Face>()
        fun box(u: Int, v: Int, transform: Array<DoubleArray>) {
            boxFaces(doubleArrayOf(-4.0, -4.0, -4.0), doubleArrayOf(4.0, 4.0, 4.0)) { dir ->
                // Entity UVs are in a 64-wide texture's pixels; HD packs draw the same layout bigger.
                val scale = tex.width / 64.0
                FaceTexture(tex, entityUv(u, v, 8, 8, 8, dir).map { it * scale }.toDoubleArray(), null)
            }.forEach { face -> faces += face.transformed(transform) }
        }
        val outer = turn
        val inner = mul(mul(outer, scale(0.875)), turn)
        val core = mul(mul(inner, scale(0.875)), turn)
        box(0, 0, outer)
        box(0, 0, inner)
        box(32, 0, core)
        return draw(faces, rotation(20.0, 30.0, 0.0))
    }

    // ------------------------------------------------------------------ models

    private class Resolved(val elements: JsonArray?, val textures: Map<String, String>, val gui: DoubleArray?) {
        /** Follows "#name" references to a texture path under assets/minecraft/. */
        fun texture(ref: String): String? {
            var value = ref
            repeat(8) {
                if (!value.startsWith("#")) return "textures/${strip(value)}.png"
                value = textures[value.drop(1)] ?: return null
            }
            return null
        }
    }

    /** The model an item uses: from its item definition (items/<id>.json), else models/item/<id>.json. */
    private fun itemModelRef(id: String, read: (String) -> ByteArray?): String? {
        json(read("items/$id.json"))?.let { definition -> findModel(definition)?.let { return it } }
        return if (read("models/item/$id.json") != null) "item/$id" else null
    }

    /** The first plain model reference in an item definition, whatever conditions wrap it. */
    private fun findModel(element: JsonElement): String? {
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            val model = obj.get("model")
            if (model != null && model.isJsonPrimitive) return model.asString
            obj.entrySet().forEach { (_, value) -> findModel(value)?.let { return it } }
        } else if (element.isJsonArray) {
            element.asJsonArray.forEach { value -> findModel(value)?.let { return it } }
        }
        return null
    }

    /** Walks a model's parents, child values winning, until the elements, textures and gui view are known. */
    private fun resolve(ref: String, read: (String) -> ByteArray?): Resolved? {
        var elements: JsonArray? = null
        val textures = HashMap<String, String>()
        var gui: DoubleArray? = null
        var current: String? = ref
        var found = false
        var depth = 0
        while (current != null && depth++ < 12) {
            val name = strip(current)
            if (name == "builtin/generated" || name == "builtin/entity") break
            val model = json(read("models/$name.json"))?.takeIf { it.isJsonObject }?.asJsonObject ?: break
            found = true
            if (elements == null) elements = model.getAsJsonArray("elements")
            model.getAsJsonObject("textures")?.entrySet()?.forEach { (key, value) ->
                if (value.isJsonPrimitive) textures.putIfAbsent(key, value.asString)
            }
            if (gui == null) {
                model.getAsJsonObject("display")?.getAsJsonObject("gui")?.getAsJsonArray("rotation")?.let { rotation ->
                    gui = DoubleArray(3) { rotation[it].asDouble }
                }
            }
            current = model.get("parent")?.asString
        }
        return if (found) Resolved(elements, textures, gui) else null
    }

    private fun elementFaces(element: JsonObject, model: Resolved, texture: (String) -> BufferedImage?): List<Face> {
        val from = vec(element.getAsJsonArray("from")) ?: return emptyList()
        val to = vec(element.getAsJsonArray("to")) ?: return emptyList()
        val facesJson = element.getAsJsonObject("faces") ?: return emptyList()
        val faces = boxFaces(from, to) { dir ->
            val face = facesJson.getAsJsonObject(DIRECTIONS[dir]) ?: return@boxFaces null
            val path = model.texture(face.get("texture")?.asString ?: return@boxFaces null) ?: return@boxFaces null
            val image = texture(path) ?: return@boxFaces null
            val scale = image.width / 16.0
            val uv = face.getAsJsonArray("uv")?.let { array -> DoubleArray(4) { array[it].asDouble * scale } }
                ?: defaultUv(dir, from, to).map { it * scale }.toDoubleArray()
            FaceTexture(image, uv, if (face.has("tintindex")) tintFor(path) ?: GRASS else null, face.get("rotation")?.asInt ?: 0)
        }
        // An element's own rotation turns it about its origin before the model is viewed.
        val rotation = element.getAsJsonObject("rotation") ?: return faces
        val origin = vec(rotation.getAsJsonArray("origin")) ?: return faces
        val angle = rotation.get("angle")?.asDouble ?: return faces
        val axis = when (rotation.get("axis")?.asString) {
            "x" -> doubleArrayOf(1.0, 0.0, 0.0)
            "y" -> doubleArrayOf(0.0, 1.0, 0.0)
            else -> doubleArrayOf(0.0, 0.0, 1.0)
        }
        val turn = axisAngle(axis, angle)
        return faces.map { face -> face.transformed(turn, origin) }
    }

    // ------------------------------------------------------------------ geometry

    private class FaceTexture(val image: BufferedImage, val uv: DoubleArray?, val tint: Int?, val uvRotation: Int = 0)

    /** A face as four corners (top-left, top-right, bottom-right, bottom-left of its texture). */
    private class Face(val corners: Array<DoubleArray>, val tex: FaceTexture, val shade: Double) {
        fun transformed(m: Array<DoubleArray>, origin: DoubleArray = DoubleArray(3)): Face =
            Face(Array(4) { i -> add(apply(m, sub(corners[i], origin)), origin) }, tex, shade)
    }

    private const val DOWN = 0
    private const val UP = 1
    private const val NORTH = 2
    private const val SOUTH = 3
    private const val WEST = 4
    private const val EAST = 5
    private val DIRECTIONS = arrayOf("down", "up", "north", "south", "west", "east")

    /** The brightness the game gives each side, so a cube reads as a cube. */
    private val SHADE = doubleArrayOf(0.5, 1.0, 0.8, 0.8, 0.6, 0.6)

    /**
     * The six faces of a box, with corners in the order its texture runs: top-left, top-right,
     * bottom-right, bottom-left, as seen from outside with up being up (north up on the top
     * face). [texture] returns null for a face the box does not have.
     */
    private fun boxFaces(from: DoubleArray, to: DoubleArray, texture: (Int) -> FaceTexture?): List<Face> {
        val (x0, y0, z0) = from
        val (x1, y1, z1) = to
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        val corners = arrayOf(
            arrayOf(p(x0, y0, z1), p(x1, y0, z1), p(x1, y0, z0), p(x0, y0, z0)),
            arrayOf(p(x0, y1, z0), p(x1, y1, z0), p(x1, y1, z1), p(x0, y1, z1)),
            arrayOf(p(x1, y1, z0), p(x0, y1, z0), p(x0, y0, z0), p(x1, y0, z0)),
            arrayOf(p(x0, y1, z1), p(x1, y1, z1), p(x1, y0, z1), p(x0, y0, z1)),
            arrayOf(p(x0, y1, z0), p(x0, y1, z1), p(x0, y0, z1), p(x0, y0, z0)),
            arrayOf(p(x1, y1, z1), p(x1, y1, z0), p(x1, y0, z0), p(x1, y0, z1))
        )
        return (0 until 6).mapNotNull { dir -> texture(dir)?.let { Face(corners[dir], it, SHADE[dir]) } }
    }

    /** The UV a face gets when the model does not say, in 0-16 texture units. */
    private fun defaultUv(dir: Int, f: DoubleArray, t: DoubleArray): DoubleArray = when (dir) {
        DOWN -> doubleArrayOf(f[0], 16 - t[2], t[0], 16 - f[2])
        UP -> doubleArrayOf(f[0], f[2], t[0], t[2])
        NORTH -> doubleArrayOf(16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1])
        SOUTH -> doubleArrayOf(f[0], 16 - t[1], t[0], 16 - f[1])
        WEST -> doubleArrayOf(f[2], 16 - t[1], t[2], 16 - f[1])
        else -> doubleArrayOf(16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1])
    }

    /** Where each side of an entity box sits on its texture (ModelPart's unwrap), in pixels. */
    private fun entityUv(u: Int, v: Int, w: Int, h: Int, d: Int, dir: Int): DoubleArray {
        val a = u.toDouble(); val b = (u + d).toDouble(); val c = (u + d + w).toDouble()
        val e = (u + d + w + w).toDouble(); val f = (u + d + w + d).toDouble(); val g = (u + d + w + d + w).toDouble()
        val top = v.toDouble(); val mid = (v + d).toDouble(); val bottom = (v + d + h).toDouble()
        return when (dir) {
            UP -> doubleArrayOf(b, top, c, mid)
            DOWN -> doubleArrayOf(c, top, e, mid)
            WEST -> doubleArrayOf(a, mid, b, bottom)
            NORTH -> doubleArrayOf(b, mid, c, bottom)
            EAST -> doubleArrayOf(c, mid, f, bottom)
            else -> doubleArrayOf(f, mid, g, bottom)
        }
    }

    // ------------------------------------------------------------------ drawing

    /** Views [faces] through [view], fits them to the card and paints them back to front. */
    private fun draw(faces: List<Face>, view: Array<DoubleArray>): BufferedImage? {
        if (faces.isEmpty()) return null
        val center = DoubleArray(3)
        faces.forEach { face -> face.corners.forEach { c -> for (i in 0..2) center[i] += c[i] } }
        for (i in 0..2) center[i] /= faces.size * 4.0
        val projected = faces.map { face -> face to Array(4) { apply(view, sub(face.corners[it], center)) } }

        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        projected.forEach { (_, points) ->
            points.forEach { p ->
                minX = min(minX, p[0]); maxX = max(maxX, p[0])
                minY = min(minY, -p[1]); maxY = max(maxY, -p[1])
            }
        }
        val span = max(maxX - minX, maxY - minY).coerceAtLeast(1e-6)
        val fit = SIZE * 0.92 / span
        val offsetX = SIZE / 2.0 - (minX + maxX) / 2 * fit
        val offsetY = SIZE / 2.0 - (minY + maxY) / 2 * fit

        val image = BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.composite = AlphaComposite.SrcOver
        val shaded = HashMap<Triple<BufferedImage, Double, Int?>, BufferedImage>()

        projected.sortedBy { (_, points) -> points.sumOf { it[2] } }.forEach { (face, points) ->
            val screen = points.map { doubleArrayOf(it[0] * fit + offsetX, -it[1] * fit + offsetY) }
            val tex = face.tex
            val uv = tex.uv ?: doubleArrayOf(0.0, 0.0, tex.image.width.toDouble(), min(tex.image.width, tex.image.height).toDouble())
            val du = uv[2] - uv[0]
            val dv = uv[3] - uv[1]
            if (abs(du) < 1e-6 || abs(dv) < 1e-6) return@forEach
            // A face turned edge-on has no area to paint.
            val area = (screen[1][0] - screen[0][0]) * (screen[3][1] - screen[0][1]) - (screen[1][1] - screen[0][1]) * (screen[3][0] - screen[0][0])
            if (abs(area) < 0.05) return@forEach
            // A UV rotation moves which corner the texture's top-left lands on.
            val k = ((tex.uvRotation / 90) % 4 + 4) % 4
            val p0 = screen[k % 4]
            val p1 = screen[(1 + k) % 4]
            val p3 = screen[(3 + k) % 4]
            val transform = AffineTransform(
                (p1[0] - p0[0]) / du, (p1[1] - p0[1]) / du,
                (p3[0] - p0[0]) / dv, (p3[1] - p0[1]) / dv,
                0.0, 0.0
            )
            transform.preConcatenate(AffineTransform.getTranslateInstance(p0[0], p0[1]))
            transform.concatenate(AffineTransform.getTranslateInstance(-uv[0], -uv[1]))
            val outline = Path2D.Double().apply {
                moveTo(screen[0][0], screen[0][1])
                for (i in 1..3) lineTo(screen[i][0], screen[i][1])
                closePath()
            }
            g.clip = outline
            val source = shaded.getOrPut(Triple(tex.image, face.shade, tex.tint)) { tinted(tex.image, face.shade, tex.tint) }
            g.drawImage(source, transform, null)
        }
        g.dispose()
        return image
    }

    /** A copy of [image] darkened by [shade] and, for grass and the like, coloured by [tint]. */
    private fun tinted(image: BufferedImage, shade: Double, tint: Int?): BufferedImage {
        val r = shade * ((tint ?: 0xFFFFFF) shr 16 and 0xFF) / 255.0
        val gr = shade * ((tint ?: 0xFFFFFF) shr 8 and 0xFF) / 255.0
        val b = shade * ((tint ?: 0xFFFFFF) and 0xFF) / 255.0
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val argb = image.getRGB(x, y)
            val red = ((argb shr 16 and 0xFF) * r).toInt()
            val green = ((argb shr 8 and 0xFF) * gr).toInt()
            val blue = ((argb and 0xFF) * b).toInt()
            out.setRGB(x, y, (argb and -0x1000000) or (red shl 16) or (green shl 8) or blue)
        }
        return out
    }

    private const val GRASS = 0x7CBD6B
    private const val WATER = 0x3F76E4

    /** The colour the game paints over grey textures (grass, leaves, water) when it draws them in the world. */
    private fun tintFor(path: String): Int? {
        val name = path.substringAfterLast('/')
        return when {
            name.startsWith("water_") -> WATER
            name == "grass_block_top.png" || name == "short_grass.png" || name.startsWith("tall_grass") ||
                name == "fern.png" || name.startsWith("large_fern") || name == "vine.png" || name == "lily_pad.png" -> GRASS
            name.endsWith("_leaves.png") && name !in setOf("cherry_leaves.png", "azalea_leaves.png", "flowering_azalea_leaves.png", "pale_oak_leaves.png") -> GRASS
            else -> null
        }
    }

    // ------------------------------------------------------------------ small helpers

    /** Reads a texture, keeping only the first frame of an animation strip. */
    private fun decode(bytes: ByteArray?): BufferedImage? {
        bytes ?: return null
        val read = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        val frame = if (read.height > read.width && read.height % read.width == 0) read.width else read.height
        val image = BufferedImage(read.width, frame, BufferedImage.TYPE_INT_ARGB)
        image.createGraphics().apply { drawImage(read, 0, 0, null); dispose() }
        return image
    }

    private fun json(bytes: ByteArray?): JsonElement? =
        bytes?.let { runCatching { JsonParser.parseString(it.toString(Charsets.UTF_8)) }.getOrNull() }

    /** "minecraft:item/x" or "item/x" to "item/x". Other namespaces are not River's to read here. */
    private fun strip(ref: String): String = ref.removePrefix("minecraft:")

    private fun vec(array: JsonArray?): DoubleArray? =
        array?.takeIf { it.size() == 3 }?.let { DoubleArray(3) { i -> it[i].asDouble } }

    private fun rotation(x: Double, y: Double, z: Double): Array<DoubleArray> =
        mul(mul(axisAngle(doubleArrayOf(1.0, 0.0, 0.0), x), axisAngle(doubleArrayOf(0.0, 1.0, 0.0), y)), axisAngle(doubleArrayOf(0.0, 0.0, 1.0), z))

    private fun axisAngle(axis: DoubleArray, degrees: Double): Array<DoubleArray> {
        val length = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
        val x = axis[0] / length; val y = axis[1] / length; val z = axis[2] / length
        val a = Math.toRadians(degrees)
        val c = cos(a); val s = sin(a); val t = 1 - c
        return arrayOf(
            doubleArrayOf(t * x * x + c, t * x * y - s * z, t * x * z + s * y),
            doubleArrayOf(t * x * y + s * z, t * y * y + c, t * y * z - s * x),
            doubleArrayOf(t * x * z - s * y, t * y * z + s * x, t * z * z + c)
        )
    }

    private fun scale(s: Double): Array<DoubleArray> =
        arrayOf(doubleArrayOf(s, 0.0, 0.0), doubleArrayOf(0.0, s, 0.0), doubleArrayOf(0.0, 0.0, s))

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { i -> DoubleArray(3) { j -> a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] } }

    private fun apply(m: Array<DoubleArray>, v: DoubleArray): DoubleArray =
        DoubleArray(3) { i -> m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2] }

    private fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
    private fun add(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] + b[it] }
}
//?}
