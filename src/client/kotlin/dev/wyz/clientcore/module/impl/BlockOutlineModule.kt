package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.ClientUi
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * The outline around the block you are looking at, in your colour - or gone.
 *
 * Every other client has this and River did not, which is why vanilla's 40%-black box
 * was the one part of the screen that could not be made to match a theme. Purely a
 * client-side colour: the same block is still targeted, so nothing about reach, mining
 * or interaction changes.
 */
class BlockOutlineModule : Module(
    id = "block_outline",
    displayName = "Block Outline",
    description = "Recolour or hide the block outline",
    category = ModuleCategory.VISUAL,
    icon = "item:minecraft:grass_block",
    defaultX = 0,
    defaultY = 0,
    defaultEnabled = false
) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    companion object {
        /** White at 80%: visibly different from vanilla the moment it is switched on. */
        const val DEFAULT_COLOR = 0xCCFFFFFF.toInt()
    }

    fun hidden(): Boolean = flag("hidden", false)

    /** The stored colour - what the picker edits, and what Rainbow takes its alpha from. */
    fun color(): Int = scalar("color", DEFAULT_COLOR)

    fun rainbow(): Boolean = flag("rainbow", false)

    /** 1 is a slow drift, 10 goes round in well under a second. */
    fun rainbowSpeed(): Int = scalar("rainbow_speed", 5).coerceIn(1, 10)

    /**
     * The colour to draw with this frame. Rainbow walks the hue on wall-clock time, so it
     * runs at the same rate regardless of frame rate and does not stall while the game is
     * paused mid-tick. Alpha is always the stored one: a rainbow outline that silently
     * became opaque would be a second change nobody asked for.
     */
    fun renderColor(): Int {
        val base = color()
        if (!rainbow()) return base
        val cycleMs = (11 - rainbowSpeed()) * 600L
        val hue = (System.currentTimeMillis() % cycleMs).toFloat() / cycleMs.toFloat()
        return (base and 0xFF000000.toInt()) or (ClientUi.hsvToRgb(hue, 0.85f, 1f) and 0xFFFFFF)
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Outline"))
        list.add(ColorSetting("Colour", true, { color() }, { setScalar("color", it) }))
        list.add(BoolSetting("Rainbow", { rainbow() }, { setFlag("rainbow", it) }))
        list.add(IntSetting("Rainbow speed", 1, 10, { rainbowSpeed() }, { setScalar("rainbow_speed", it) }))
        list.add(BoolSetting("Hide it completely", { hidden() }, { setFlag("hidden", it) }))
    }
}
