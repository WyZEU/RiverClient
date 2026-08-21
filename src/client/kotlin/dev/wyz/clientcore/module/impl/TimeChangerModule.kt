package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting

/**
 * Client-side visual time of day. Overrides only what YOUR client renders (sky,
 * lighting) so builds and recordings sit in a fixed light. Nothing is sent to the
 * server, and it never touches gameplay.
 */
class TimeChangerModule : Module("time_changer", "Time Changer", "Set a client-side time of day", ModuleCategory.UTILITY, "sun", 8, 478, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    /** Ticks into the day, 0..24000; null when the module is off. */
    fun overrideDayTime(): Long? = if (active) scalar("time", 6000).toLong() else null

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Time of day"))
        list.add(IntSetting("Time", 0, 23000, { scalar("time", 6000) }, { setScalar("time", it) }))
        list.add(SectionSetting("Presets"))
        list.add(ActionSetting("Dawn", "Set") { setScalar("time", 23000) })
        list.add(ActionSetting("Noon", "Set") { setScalar("time", 6000) })
        list.add(ActionSetting("Dusk", "Set") { setScalar("time", 12500) })
        list.add(ActionSetting("Midnight", "Set") { setScalar("time", 18000) })
    }
}
