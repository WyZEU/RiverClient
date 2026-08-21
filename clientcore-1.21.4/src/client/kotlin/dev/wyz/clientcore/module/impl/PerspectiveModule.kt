package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft

/**
 * One-tap third-person front view. The keybind jumps straight to the front
 * camera and back to first person, handy for recording and screenshots.
 */
class PerspectiveModule : Module("perspective", "Perspective+", "One-tap front third-person view", ModuleCategory.GAMEPLAY, "video", 8, 478, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
    override val keybindLabel: String = "Front view key"

    override fun onKeybindPressed(client: Minecraft): Boolean {
        if (!active) return true
        val options = client.options
        options.cameraType = if (options.cameraType == CameraType.THIRD_PERSON_FRONT) {
            CameraType.FIRST_PERSON
        } else {
            CameraType.THIRD_PERSON_FRONT
        }
        return true
    }
}
