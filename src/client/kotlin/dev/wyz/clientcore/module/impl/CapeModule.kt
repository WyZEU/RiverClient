package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.cosmetic.RiverCape
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.Setting

/**
 * River cape cosmetic: toggle it on and pick a style (River, Sakura Valley, Moonrise).
 * Managed from the Wardrobe screen; other River users on the same server see your exact
 * style via the presence roster. Purely visual. See [dev.wyz.clientcore.cosmetic.RiverCape].
 */
class CapeModule : Module("cape", "River Cape", "Wear a River cape", ModuleCategory.COSMETICS, "shirt", 0, 0, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override val showInMenu: Boolean = false // managed from the Wardrobe screen
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    fun styleIndex(): Int = scalar("style", 0).coerceIn(0, RiverCape.STYLES.size - 1)

    /** The selected style id (e.g. "river"). */
    fun selectedStyle(): String = RiverCape.STYLES[styleIndex()]

    fun setStyleIndex(index: Int) = setScalar("style", index.coerceIn(0, RiverCape.STYLES.size - 1))

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(ChoiceSetting(
            "Cape",
            RiverCape.LABELS,
            { RiverCape.LABELS[styleIndex()] },
            { setStyleIndex(RiverCape.LABELS.indexOf(it).coerceAtLeast(0)) }
        ))
    }
}
