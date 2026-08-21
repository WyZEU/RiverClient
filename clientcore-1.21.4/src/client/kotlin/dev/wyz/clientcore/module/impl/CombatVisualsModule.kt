package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Items

/**
 * Visual-only combat comfort: lowers the first-person fire overlay and shield model,
 * and warns when you run low on totems. Changes what YOU see, never how the game plays.
 */
class CombatVisualsModule : Module("combat_visuals", "Combat Visuals", "Lower fire overlay and shield view", ModuleCategory.VISUAL, "flame", 8, 298, false) {
    private var lastLowTotemWarningTick = -10_000L

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL

    override fun acceptsDraggablePosition(): Boolean = false

    override fun showPositionControlsInEditor(): Boolean = false

    fun lowerFire(): Boolean = active && effectiveCombatVisuals().lowFire

    fun lowerShield(): Boolean = active && effectiveCombatVisuals().lowShield

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("View"))
        list.add(BoolSetting("Lower fire overlay", { mutableCombatVisuals().lowFire }, { mutableCombatVisuals().lowFire = it }))
        list.add(BoolSetting("Lower shield", { mutableCombatVisuals().lowShield }, { mutableCombatVisuals().lowShield = it }))
        list.add(SectionSetting("Totem warning"))
        list.add(BoolSetting("Low totem warning", { mutableCombatVisuals().lowTotemWarning }, { mutableCombatVisuals().lowTotemWarning = it }))
        list.add(BoolSetting("Warning sound", { mutableCombatVisuals().warningSound }, { mutableCombatVisuals().warningSound = it }))
        list.add(IntSetting("Warn at or below", 0, 5, { mutableCombatVisuals().lowTotemThreshold }, { mutableCombatVisuals().lowTotemThreshold = it }))
    }

    override fun tick(client: Minecraft) {
        if (!active) return
        val player = client.player ?: return
        val settings = effectiveCombatVisuals()
        if (!settings.lowTotemWarning || !settings.warningSound) return

        val totalTotems = (0 until player.inventory.containerSize).sumOf { slot ->
            val stack = player.inventory.getItem(slot)
            if (stack.`is`(Items.TOTEM_OF_UNDYING)) stack.count else 0
        } + if (player.offhandItem.`is`(Items.TOTEM_OF_UNDYING)) player.offhandItem.count else 0

        if (totalTotems > settings.lowTotemThreshold) return
        val gameTick = client.level?.gameTime ?: return
        if (gameTick - lastLowTotemWarningTick < settings.warningIntervalTicks) return
        lastLowTotemWarningTick = gameTick
        player.playSound(SoundEvents.NOTE_BLOCK_BIT.value(), 0.55f, 1.45f)
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) = Unit

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) = Unit
}
