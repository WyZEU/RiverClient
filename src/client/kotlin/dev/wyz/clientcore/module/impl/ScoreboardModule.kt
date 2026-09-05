package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting

/**
 * Sidebar scoreboard restyling: hide the red score numbers, scale the whole
 * sidebar down, or hide it completely. Display only, the scoreboard data is
 * untouched.
 */
class ScoreboardModule : Module("scoreboard", "Scoreboard", "Restyle or hide the sidebar", ModuleCategory.VISUAL, "scoreboard", 8, 370, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    fun hideAll(): Boolean = active && effectiveScoreboardCfg().hideAll

    fun hideNumbers(): Boolean = active && effectiveScoreboardCfg().hideNumbers

    /** 100 = vanilla size. */
    fun scalePct(): Int = if (active) effectiveScoreboardCfg().scalePercent.coerceIn(50, 100) else 100

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Sidebar"))
        list.add(BoolSetting("Hide numbers", { mutableScoreboard().hideNumbers }, { mutableScoreboard().hideNumbers = it }))
        list.add(IntSetting("Scale", 50, 100, { mutableScoreboard().scalePercent }, { mutableScoreboard().scalePercent = it }, "%"))
        list.add(BoolSetting("Hide completely", { mutableScoreboard().hideAll }, { mutableScoreboard().hideAll = it }))
    }
}
