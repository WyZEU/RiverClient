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
class ScoreboardModule : Module("scoreboard", "Scoreboard", "Restyle or hide the sidebar", ModuleCategory.VISUAL, "item:minecraft:oak_sign", 8, 370, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    fun hideAll(): Boolean = active && effectiveScoreboardCfg().hideAll

    fun hideNumbers(): Boolean = active && effectiveScoreboardCfg().hideNumbers

    /** 100 = vanilla size. */
    fun scalePct(): Int = if (active) effectiveScoreboardCfg().scalePercent.coerceIn(50, 150) else 100

    /** Percent of vanilla's background. */
    fun backgroundPct(): Int = if (active) scalar("sb_bg", 100).coerceIn(0, 100) else 100

    /** The dev screenshot's settings: a sidebar with no background. */
    fun startDemo() {
        enabled = true
        setScalar("sb_bg", 0)
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Sidebar"))
        list.add(BoolSetting("Hide numbers", { mutableScoreboard().hideNumbers }, { mutableScoreboard().hideNumbers = it }))
        list.add(IntSetting("Scale", 50, 150, { mutableScoreboard().scalePercent }, { mutableScoreboard().scalePercent = it }, "%"))
        list.add(IntSetting("Background", 0, 100, { scalar("sb_bg", 100).coerceIn(0, 100) }, { setScalar("sb_bg", it) }, "%"))
        list.add(BoolSetting("Hide completely", { mutableScoreboard().hideAll }, { mutableScoreboard().hideAll = it }))
    }
}
