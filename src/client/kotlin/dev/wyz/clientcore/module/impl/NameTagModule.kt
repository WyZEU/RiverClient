package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.Setting

/**
 * The River badge shown next to your name. Managed from the Cosmetics screen; purely
 * visual.
 *
 * Name text effects used to live here too, but a nametag is a plain Font string in 3D
 * space - only per-character colour could ever be expressed, so the effects never looked
 * like the HUD versions (the gradient in particular) and they cost sync weight for every
 * player on screen. Removed rather than half-working.
 */
class NameTagModule : Module(
    id = "nametag",
    displayName = "River Badge",
    description = "River badge next to your name",
    category = ModuleCategory.COSMETICS,
    icon = "tag",
    defaultX = 0,
    defaultY = 0,
    defaultEnabled = false
) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override val showInMenu: Boolean = false // managed from the Cosmetics screen

    fun showRiverBadge(): Boolean = editorNameTag().showRiverBadge

    /** Used by the Cosmetics screen to flip the badge cosmetic directly. */
    fun setShowRiverBadge(value: Boolean) {
        mutableNameTag().showRiverBadge = value
    }

    fun textShadow(): Boolean = editorNameTag().textShadow

    override fun acceptsDraggablePosition(): Boolean = false

    override fun showPositionControlsInEditor(): Boolean = false

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(BoolSetting("River badge", { mutableNameTag().showRiverBadge }, { mutableNameTag().showRiverBadge = it }))
    }
}
