package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.input.FreelookController
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.KeybindSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Hold a key to orbit the third-person camera without turning your player.
 * Camera-only: your real rotation, movement and packets are untouched.
 * Off by default, blocked in Safe Mode, and can be disabled per server —
 * some servers do not allow camera decoupling, so check the rules first.
 */
class FreelookModule : Module("freelook", "Freelook", "Hold to look around without turning", ModuleCategory.GAMEPLAY, "item:minecraft:ender_eye", 8, 388, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Freelook"))
        list.add(KeybindSetting("Freelook key", { mutableFreelook().holdKey }, { mutableFreelook().holdKey = it }))
        list.add(BoolSetting("Press to toggle instead of hold", { mutableFreelook().toggle }, { mutableFreelook().toggle = it }))
    }

    private var keyWasDown = false
    private var toggledOn = false

    /** Called every tick regardless of enabled state so releasing/disabling always restores the camera. */
    fun sync(client: Minecraft) {
        val key = effectiveFreelook().holdKey
        val held = key > 0 && client.screen == null &&
//? if >=1.21.9 {
            GLFW.glfwGetKey(client.window.handle(), key) == GLFW.GLFW_PRESS
//?} else {
/*            GLFW.glfwGetKey(client.window.getWindow(), key) == GLFW.GLFW_PRESS
*///?}
        val on = if (effectiveFreelook().toggle) {
            if (held && !keyWasDown) toggledOn = !toggledOn
            // A screen, or the module going off, ends a toggled look like letting go would.
            if (client.screen != null || !active) toggledOn = false
            toggledOn
        } else {
            toggledOn = false
            held
        }
        keyWasDown = held
        if (active && on && client.player != null) {
            FreelookController.begin(client)
        } else {
            FreelookController.end(client)
        }
    }
}
