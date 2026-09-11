package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.mixin.OptionInstanceAccessor
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft

/**
 * Raises the gamma value past the vanilla slider cap — the same effect as editing
 * options.txt by hand, which vanilla accepts. Restores your previous brightness on disable.
 */
class FullbrightModule : Module("fullbright", "Fullbright", "Maximum brightness everywhere", ModuleCategory.VISUAL, "sun", 8, 316, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private var savedGamma: Double? = null
    private var applied = false
    private var intensity = 10

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Brightness"))
        list.add(IntSetting("Intensity", 2, 15, { intensity }, { intensity = it }))
    }

    /** Called every tick regardless of enabled state so disabling restores the old gamma. */
    fun sync(client: Minecraft) {
        val gamma = client.options.gamma()
        if (active) {
            if (!applied) {
                val current = gamma.get()
                // Don't capture our own boosted value as the "original" after a restart.
                savedGamma = if (current <= 1.5) current else 1.0
                applied = true
            }
            @Suppress("UNCHECKED_CAST")
            (gamma as OptionInstanceAccessor).setRawValue(intensity.toDouble())
        } else if (applied) {
            @Suppress("UNCHECKED_CAST")
            (gamma as OptionInstanceAccessor).setRawValue(savedGamma ?: 1.0)
            applied = false
        }
    }
}
