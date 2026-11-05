package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Faster flight in creative mode. Vanilla creative flight is 0.05 blocks per tick of
 * acceleration, which feels slow on a big build; this multiplies it.
 *
 * Creative only, on purpose: the server already lets a creative player fly, so this is
 * how fast you do it, not permission to do it. Survival, adventure and spectator flight
 * are never touched, and leaving creative or switching the module off puts the vanilla
 * speed straight back.
 */
class FlySpeedModule : Module("fly_speed", "Creative Fly Speed", "Fly faster in creative mode", ModuleCategory.GAMEPLAY, "item:minecraft:firework_rocket", 8, 420, false) {

    companion object {
        /** Vanilla's creative flying speed. */
        private const val VANILLA = 0.05f
    }

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private var applied = false

    private fun multiplier() = scalar("speed", 3).coerceIn(1, 10)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Flight"))
        list.add(IntSetting("Creative flying speed", 1, 10, { multiplier() }, { setScalar("speed", it) }, "x"))
    }

    /** Runs every tick, on or off, so switching off or leaving creative restores vanilla. */
    fun sync(client: Minecraft) {
        val player = client.player
        val abilities = player?.abilities
        if (player == null || abilities == null) {
            applied = false
            return
        }
        if (active && player.isCreative) {
            val wanted = VANILLA * multiplier()
            if (abilities.flyingSpeed != wanted) abilities.flyingSpeed = wanted
            applied = true
        } else if (applied) {
            abilities.flyingSpeed = VANILLA
            applied = false
        }
    }
}
