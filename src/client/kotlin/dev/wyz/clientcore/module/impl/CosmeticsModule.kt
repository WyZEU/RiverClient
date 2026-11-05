package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.module.settings.hint
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Cosmetics: whether you see other River players' capes and badges, and a way into the
 * wardrobe from the modules list. Your own cape and badge are set in the wardrobe; this is
 * only about what you see on everybody else. On by default, so nothing changes until you
 * turn something off.
 */
class CosmeticsModule : Module("cosmetics", "Cosmetics", "Other players' capes and badges", ModuleCategory.COSMETICS, "shirt", 8, 544, true) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    fun othersCapes(): Boolean = flag("others_capes", true)
    fun othersBadges(): Boolean = flag("others_badges", true)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Other players"))
        list.add(BoolSetting("Show their capes", { othersCapes() }, { setFlag("others_capes", it) }))
        list.add(BoolSetting("Show their badges", { othersBadges() }, { setFlag("others_badges", it) }))
        list.add(SectionSetting("Yours"))
        list.add(ActionSetting("Cape and badge", "Open wardrobe") {
            val client = Minecraft.getInstance()
            client.setScreen(dev.wyz.clientcore.ui.screen.RiverCosmeticsScreen(client.screen))
        }.hint("Pick your cape and turn your badge on or off."))
    }

    companion object {
        private fun module(): CosmeticsModule? = ModuleRegistry.get<CosmeticsModule>("cosmetics")

        /** False when you have chosen not to see other players' capes. */
        @JvmStatic
        fun showOthersCapes(): Boolean {
            val module = module() ?: return true
            return !module.active || module.othersCapes()
        }

        /** False when you have chosen not to see other players' badges. */
        @JvmStatic
        fun showOthersBadges(): Boolean {
            val module = module() ?: return true
            return !module.active || module.othersBadges()
        }
    }
}
