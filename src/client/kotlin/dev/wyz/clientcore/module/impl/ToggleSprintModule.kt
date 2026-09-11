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
 * Holds the vanilla sprint key down for you. Identical to sitting on Ctrl —
 * the widely-accepted ToggleSprint behavior.
 */
class ToggleSprintModule : Module("toggle_sprint", "Toggle Sprint", "Sprint without holding the key", ModuleCategory.GAMEPLAY, "run", 8, 370, false) {
    private var wasHolding = false

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Sprint"))
        list.add(BoolSetting("Show HUD indicator", { mutableToggleSprint().showIndicator }, { mutableToggleSprint().showIndicator = it }))
    }

    /** Called every tick regardless of enabled state so the key is released on disable. */
    fun sync(client: Minecraft) {
        val holding = active && client.player != null && client.screen == null
        if (holding) {
            client.options.keySprint.isDown = true
        } else if (wasHolding) {
            client.options.keySprint.isDown = false
        }
        wasHolding = holding
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (!effectiveToggleSprint().showIndicator) return
        val player = client.player ?: return
        drawStat(client, graphics, "Sprint", if (player.isSprinting) "toggled" else "ready")
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "Sprint", "toggled")
    }
}
