package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.mixin.OverlayTextureAccessor
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft

/**
 * Recolours the flash an entity gets when it is hit, or switches it off. Vanilla draws
 * that flash from the top eight rows of a small overlay texture; this repaints those
 * rows and uploads them once per change, so it costs nothing per frame.
 *
 * The shader mixes toward the overlay colour by (1 - alpha): alpha is how much of the
 * entity survives, which is why the setting here is a strength and not an alpha.
 */
class HitColorModule : Module("hit_color", "Hit Color", "Recolour or turn off the red hit flash", ModuleCategory.VISUAL, "item:minecraft:red_dye", 0, 0, false) {

    companion object {
        /** Vanilla's flash: red at 30% strength. */
        private const val VANILLA_RGB = 0xFF0000
        private const val VANILLA_STRENGTH = 30
    }

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private fun rgb() = scalar("rgb", VANILLA_RGB) and 0xFFFFFF
    private fun strength() = scalar("strength", VANILLA_STRENGTH).coerceIn(0, 100)

    /** The ARGB painted last, or null before the first paint. */
    private var applied: Int? = null

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Hit flash"))
        list.add(ColorSetting("Colour", false, { 0xFF000000.toInt() or rgb() }, { setScalar("rgb", it and 0xFFFFFF) }))
        list.add(IntSetting("Strength", 0, 100, { strength() }, { setScalar("strength", it) }, "%"))
    }

    /** Runs every client tick, on or off, so turning it off puts vanilla's flash back. */
    fun sync(client: Minecraft) {
        val color = if (active) toArgb(rgb(), strength()) else toArgb(VANILLA_RGB, VANILLA_STRENGTH)
        if (color == applied) return
        val texture = (client.gameRenderer.overlayTexture() as OverlayTextureAccessor).`clientcore$texture`()
        val pixels = texture.pixels ?: return
        for (y in 0 until 8) {
            for (x in 0 until 16) {
//? if >=1.21.2 {
                pixels.setPixel(x, y, color)
//?} else {
/*                // This version's setter takes ABGR.
                pixels.setPixelRGBA(x, y, (color and 0xFF00FF00.toInt()) or ((color shr 16) and 0xFF) or ((color and 0xFF) shl 16))
*///?}
            }
        }
        texture.upload()
        applied = color
    }

    private fun toArgb(rgb: Int, strength: Int): Int {
        val alpha = 255 - Math.round(strength * 2.55f)
        return (alpha shl 24) or (rgb and 0xFFFFFF)
    }
}
