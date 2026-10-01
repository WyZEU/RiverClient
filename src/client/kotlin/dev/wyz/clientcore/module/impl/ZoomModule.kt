package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.KeybindSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting

class ZoomModule : Module("zoom", "Zoom", "Smooth zoom, scroll to zoom further", ModuleCategory.VISUAL, "item:minecraft:spyglass", 8, 244) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    fun holdKey(): Int = effectiveZoom().holdKey
    fun zoomFov(): Double = effectiveZoom().zoomFov.toDouble()
    fun settingsNow() = effectiveZoom()

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Zoom"))
        list.add(KeybindSetting("Zoom key", { mutableZoom().holdKey }, { mutableZoom().holdKey = it }))
        list.add(BoolSetting("Press to toggle instead of hold", { mutableZoom().toggle }, { mutableZoom().toggle = it }))
        list.add(IntSetting("How far to zoom", 10, 60, { 70 - mutableZoom().zoomFov }, { mutableZoom().zoomFov = 70 - it }))
        list.add(SectionSetting("Feel"))
        list.add(BoolSetting("Smooth zoom", { mutableZoom().smooth }, { mutableZoom().smooth = it }))
        list.add(BoolSetting("Scroll to zoom further", { mutableZoom().scrollToZoom }, { mutableZoom().scrollToZoom = it }))
        list.add(BoolSetting("Slower mouse while zoomed", { mutableZoom().reduceSensitivity }, { mutableZoom().reduceSensitivity = it }))
    }
}
