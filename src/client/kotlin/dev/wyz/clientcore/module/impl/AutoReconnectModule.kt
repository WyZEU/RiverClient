package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting

/**
 * After a disconnect from a multiplayer server, counts down and rejoins the same
 * server automatically. The disconnect screen shows the countdown with a cancel.
 */
class AutoReconnectModule : Module("auto_reconnect", "Auto Reconnect", "Rejoin a server after a disconnect", ModuleCategory.UTILITY, "plug", 8, 496, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    fun delaySeconds(): Int = scalar("delay", 5).coerceIn(1, 60)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Reconnect"))
        list.add(IntSetting("Delay", 1, 30, { scalar("delay", 5) }, { setScalar("delay", it) }, "s"))
    }
}
