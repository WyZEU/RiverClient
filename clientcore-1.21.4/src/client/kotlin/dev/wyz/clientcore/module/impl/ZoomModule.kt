package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.KeybindSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting

class ZoomModule : Module("zoom", "Zoom", "Hold a key to zoom your view", ModuleCategory.VISUAL, "zoom", 8, 244) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    fun holdKey(): Int = effectiveZoom().holdKey
    fun zoomFov(): Double = effectiveZoom().zoomFov.toDouble()

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Zoom"))
        list.add(KeybindSetting("Hold key", { mutableZoom().holdKey }, { mutableZoom().holdKey = it }))
        list.add(IntSetting("Zoom FOV", 10, 60, { mutableZoom().zoomFov }, { mutableZoom().zoomFov = it }))
    }
}
