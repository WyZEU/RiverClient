package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile

/** Removes the red camera tilt/shake when you take damage. Visual comfort only. */
class NoHurtCamModule : Module("no_hurt_cam", "No Hurt Camera", "Stop the damage camera tilt", ModuleCategory.GAMEPLAY, "heartcrack", 8, 424, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
}
