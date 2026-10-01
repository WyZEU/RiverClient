package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * Visual-only combat comfort: lowers the first-person fire overlay and shield model.
 * Changes what YOU see, never how the game plays.
 */
class CombatVisualsModule : Module("combat_visuals", "Combat Visuals", "Lower fire overlay and shield view", ModuleCategory.VISUAL, "item:minecraft:shield", 8, 298, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL

    override fun acceptsDraggablePosition(): Boolean = false

    override fun showPositionControlsInEditor(): Boolean = false

    fun lowerFire(): Boolean = active && effectiveCombatVisuals().lowFire

    fun lowerShield(): Boolean = active && effectiveCombatVisuals().lowShield

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(BoolSetting("Lower fire overlay", { mutableCombatVisuals().lowFire }, { mutableCombatVisuals().lowFire = it }))
        list.add(BoolSetting("Lower shield", { mutableCombatVisuals().lowShield }, { mutableCombatVisuals().lowShield = it }))
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) = Unit

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) = Unit
}
