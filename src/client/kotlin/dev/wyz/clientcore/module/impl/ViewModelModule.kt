package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting

/**
 * View Model: repositions and resizes your own first-person held item (and arm).
 * This is a purely client-side cosmetic — it changes only how the item is drawn
 * on your screen, not your reach, hitbox, or anything the server sees. It's the
 * Minecraft equivalent of a Source-engine "viewmodel_offset" tweak.
 */
class ViewModelModule : Module("view_model", "View Model", "Reposition your first-person held item", ModuleCategory.GAMEPLAY, "pointer", 8, 460, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    /** Slider values are stored as whole numbers; offsets are hundredths of a block. */
    fun offsetX(): Float = scalar("x", 0) / 100f
    fun offsetY(): Float = scalar("y", 0) / 100f
    fun offsetZ(): Float = scalar("z", 0) / 100f
    fun scale(): Float = scalar("scale", 100).coerceIn(50, 200) / 100f

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("View model"))
        list.add(IntSetting("Left / right", -50, 50, { scalar("x", 0) }, { setScalar("x", it) }))
        list.add(IntSetting("Up / down", -50, 50, { scalar("y", 0) }, { setScalar("y", it) }))
        list.add(IntSetting("Forward / back", -50, 50, { scalar("z", 0) }, { setScalar("z", it) }))
        list.add(IntSetting("Scale", 50, 200, { scalar("scale", 100) }, { setScalar("scale", it) }, "%"))
    }
}
