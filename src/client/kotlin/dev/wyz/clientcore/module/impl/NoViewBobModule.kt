package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile

/** Turns off the walking view/hand bob without touching your vanilla options. */
class NoViewBobModule : Module("no_view_bob", "No View Bob", "Steady the camera while walking", ModuleCategory.GAMEPLAY, "aperture", 8, 442, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false
}
