package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.world.level.LightLayer

/**
 * Position, and the things you read alongside it.
 *
 * Facing and speed used to be their own modules, which meant three pills stacked
 * up the side of the screen all answering "where am I and which way am I going".
 * The facing badge was already drawn here, so Direction was near-duplicate work
 * before the merge.
 */
class CoordinatesModule : Module("coordinates", "Coordinates", "Position, facing, biome and speed", ModuleCategory.HUD, "location", 8, 26) {

    private companion object {
        // Per-axis label tint, like the reference HUD: X/Y/Z read at a glance.
        val AXIS = intArrayOf(0xFFFF6B6B.toInt(), 0xFF72F1B8.toInt(), 0xFF6BA9FF.toInt())
        val LABEL = 0xFF97A0B5.toInt()
    }

    /** A row: single-letter label, its colour, the value, and the value colour (0 = default). */
    private data class Row(val label: String, val labelColor: Int, val value: String, val valueColor: Int)

    /** Multi-line whenever any extra info line is on, so "Line spacing" appears then. */
    override val multiLine: Boolean
        get() = showBiome() || showEntities() || showDimension() || showLight() || showSpeed()

    private fun showBiome() = flag("biome", true)
    private fun showEntities() = flag("entities", false)
    private fun showDimension() = flag("dimension", false)
    private fun showLight() = flag("light", false)
    private fun showFacing() = flag("facing", true)
    private fun showSpeed() = flag("speed", false)

    // Speed is sampled per tick from the change in horizontal position, the same
    // way the old Speedometer module did it.
    private var lastX = 0.0
    private var lastZ = 0.0
    private var speed = 0.0

    override fun tick(client: Minecraft) {
        if (!showSpeed()) return
        val player = client.player ?: return
        val dx = player.x - lastX
        val dz = player.z - lastZ
        speed = kotlin.math.sqrt(dx * dx + dz * dz) * 20.0
        lastX = player.x
        lastZ = player.z
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Info"))
        list.add(BoolSetting("Show biome", { flag("biome", true) }, { setFlag("biome", it) }))
        list.add(BoolSetting("Show entity count", { flag("entities", false) }, { setFlag("entities", it) }))
        list.add(BoolSetting("Show dimension", { flag("dimension", false) }, { setFlag("dimension", it) }))
        list.add(BoolSetting("Show light level", { flag("light", false) }, { setFlag("light", it) }))
        list.add(BoolSetting("Show facing", { flag("facing", true) }, { setFlag("facing", it) }))
        list.add(BoolSetting("Show speed", { flag("speed", false) }, { setFlag("speed", it) }))
    }

    /** F3-style "rendered/total" entity count, or just the total if the renderer stat is unavailable. */
    private fun entityCount(client: Minecraft): String {
        val fromRenderer = runCatching {
//? if >=26.2 {
/*            Regex("E:\\s*([0-9]+/[0-9]+)").find(client.levelExtractor.entityStatistics().orEmpty())?.groupValues?.get(1)
*///?} else {
            Regex("E:\\s*([0-9]+/[0-9]+)").find(client.levelRenderer.entityStatistics.orEmpty())?.groupValues?.get(1)
//?}
        }.getOrNull()
        if (!fromRenderer.isNullOrBlank()) return fromRenderer
        return runCatching { client.level?.entityCount?.toString() }.getOrNull() ?: "0"
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val player = client.player ?: return
        val level = client.level ?: return
        val pos = player.blockPosition()

        val rows = ArrayList<Row>(6)
        rows.add(Row("X", AXIS[0], pos.x.toString(), 0))
        rows.add(Row("Y", AXIS[1], pos.y.toString(), 0))
        rows.add(Row("Z", AXIS[2], pos.z.toString(), 0))
        if (showEntities()) rows.add(Row("C", LABEL, entityCount(client), 0))
        if (showBiome()) {
//? if >=1.21.11 {
            val path = level.getBiome(pos).unwrapKey().orElse(null)?.identifier()?.path ?: "unknown"
//?} else {
/*            val path = level.getBiome(pos).unwrapKey().orElse(null)?.location()?.path ?: "unknown"
*///?}
            rows.add(Row("Biome", LABEL, prettify(path), biomeColor(path)))
        }
//? if >=1.21.11 {
        if (showDimension()) rows.add(Row("Dim", LABEL, prettify(level.dimension().identifier().path), 0))
//?} else {
/*        if (showDimension()) rows.add(Row("Dim", LABEL, prettify(level.dimension().location().path), 0))
*///?}
        if (showLight()) {
            val block = level.getBrightness(LightLayer.BLOCK, pos)
            val sky = level.getBrightness(LightLayer.SKY, pos)
            rows.add(Row("Light", LABEL, "B$block S$sky", 0))
        }
        if (showSpeed()) rows.add(Row("Speed", LABEL, "%.2f b/s".format(speed), 0))
        drawCoords(client, graphics, rows, if (showFacing()) facing(player.yRot) else "")
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val rows = ArrayList<Row>(6)
        rows.add(Row("X", AXIS[0], "-448", 0))
        rows.add(Row("Y", AXIS[1], "128", 0))
        rows.add(Row("Z", AXIS[2], "-70", 0))
        if (showEntities()) rows.add(Row("C", LABEL, "132/240", 0))
        if (showBiome()) rows.add(Row("Biome", LABEL, "Plains", biomeColor("plains")))
        if (showDimension()) rows.add(Row("Dim", LABEL, "Overworld", 0))
        if (showLight()) rows.add(Row("Light", LABEL, "B0 S15", 0))
        if (showSpeed()) rows.add(Row("Speed", LABEL, "5.61 b/s", 0))
        drawCoords(client, graphics, rows, if (showFacing()) "NE" else "")
    }

    /** Cardinal/inter-cardinal facing from the player yaw (MC yaw 0 = south). */
    private fun facing(yaw: Float): String {
        val a = ((yaw % 360f) + 360f) % 360f
        return arrayOf("S", "SW", "W", "NW", "N", "NE", "E", "SE")[((a + 22.5f) / 45f).toInt() % 8]
    }

    /**
     * Reference-style layout: a colour-labelled row per axis, values in a shared
     * column, and the facing direction pinned to the top-right in the accent colour.
     */
    private fun drawCoords(client: Minecraft, graphics: GuiGraphics, rows: List<Row>, facing: String) {
        if (rows.isEmpty()) return
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val gap = 6
        val lineH = font.lineHeight + 2 + st.spacing
        val labelW = rows.maxOf { font.width(it.label) }
        val valueW = rows.maxOf { font.width(it.value) }
        val facingW = if (facing.isNotEmpty()) font.width(facing) + 8 else 0
        val w = pad + 4 + labelW + gap + valueW + facingW + pad
        val h = pad * 2 + rows.size * lineH - 2

        drawPillBackground(graphics, w, h)
        val defaultValue = panelTextColor()
        val lx = x + pad + 4
        rows.forEachIndexed { i, row ->
            val ly = y + pad + i * lineH
            graphics.drawString(font, row.label, lx, ly, row.labelColor, st.textShadow)
            graphics.drawString(font, row.value, lx + labelW + gap, ly, if (row.valueColor != 0) row.valueColor else defaultValue, st.textShadow)
        }
        // Facing badge, top-right, so a glance gives direction without a compass.
        if (facing.isNotEmpty()) {
            graphics.drawString(font, facing, x + w - pad - font.width(facing), y + pad, panelAccentColor(), st.textShadow)
        }
    }

    private fun prettify(path: String): String =
        path.split('_').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }

    /** Stable, always-vivid color per biome (fixed saturation + full value) so it never
     *  comes out dark or white and never blends into the pill or the world. */
    private fun biomeColor(path: String): Int {
        var hash = 0
        for (c in path) hash = hash * 31 + c.code
        val hue = ((hash and 0x7FFFFFFF) % 360) / 360f
        return (0xFF shl 24) or (hsvToRgb(hue, 0.6f, 1.0f) and 0xFFFFFF)
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Int {
        val i = (h * 6f).toInt()
        val f = h * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }
}
