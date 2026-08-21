package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile

/**
 * Keeps the field of view fixed, cancelling the FOV stretch from sprinting or
 * Speed. Pure comfort, doesn't change how far you can see or reach.
 */
class StaticFovModule : Module("static_fov", "Static FOV", "No FOV stretch when sprinting", ModuleCategory.GAMEPLAY, "crosshair", 8, 460, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
}
