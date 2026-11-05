package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * ToggleSprint as Lunar and Feather have it: sprint stays on, the sprint key switches it
 * off and on again, and optionally the sneak key toggles sneaking the same way. Both only
 * hold the vanilla key down for you - identical to sitting on Ctrl or Shift, which is why
 * every server allows it.
 */
class ToggleSprintModule : Module("toggle_sprint", "Toggle Sprint", "Sprint and sneak without holding keys", ModuleCategory.GAMEPLAY, "run", 8, 370, false) {
    private var sprintOn = true
    private var sneakOn = false
    private var heldSprint = false
    private var heldSneak = false

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Sprint"))
        list.add(BoolSetting("Toggle sneak too", { mutableToggleSprint().toggleSneak }, { mutableToggleSprint().toggleSneak = it }))
        list.add(BoolSetting("Show status on screen", { mutableToggleSprint().showIndicator }, { mutableToggleSprint().showIndicator = it }))
    }

    /** Called every tick regardless of enabled state so the keys are released on disable. */
    fun sync(client: Minecraft) {
        val playing = active && client.player != null && client.screen == null
        val sprintKey = client.options.keySprint
        val sneakKey = client.options.keyShift
        // A press of the real key flips the toggle. consumeClick only counts presses of
        // the physical key; holding it down for the player below does not add any.
        if (playing) {
            while (sprintKey.consumeClick()) sprintOn = !sprintOn
            if (effectiveToggleSprint().toggleSneak) {
                while (sneakKey.consumeClick()) sneakOn = !sneakOn
            } else {
                sneakOn = false
            }
        }
        if (!active) {
            sprintOn = true
            sneakOn = false
        }

        val holdSprint = playing && sprintOn
        if (holdSprint) sprintKey.isDown = true else if (heldSprint) sprintKey.isDown = false
        heldSprint = holdSprint

        val holdSneak = playing && sneakOn
        if (holdSneak) sneakKey.isDown = true else if (heldSneak) sneakKey.isDown = false
        heldSneak = holdSneak
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (!effectiveToggleSprint().showIndicator) return
        val player = client.player ?: return
        when {
            sneakOn -> drawStat(client, graphics, "", "Sneaking (toggled)")
            sprintOn && player.isSprinting -> drawStat(client, graphics, "", "Sprinting (toggled)")
            sprintOn -> drawStat(client, graphics, "", "Sprint (toggled)")
        }
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "", "Sprinting (toggled)")
    }
}
