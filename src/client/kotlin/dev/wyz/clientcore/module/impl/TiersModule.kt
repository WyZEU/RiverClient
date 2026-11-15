package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.nametag.TierIcons
import dev.wyz.clientcore.net.TierService
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import java.util.UUID
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * PvP tier tags: everyone's MCTiers rank next to their name, above their head and in the
 * tab list, the way Lunar's TierTagger does it. The one thing the 2026 PvP crowd asks a
 * client for that River did not have.
 *
 * Off by default, because it is the only module that asks a third party (mctiers.com)
 * about the people around you - that should be a choice someone makes, not a default
 * they discover.
 */
class TiersModule : Module(
    id = "tiers",
    displayName = "PvP Tiers",
    description = "MCTiers ranks on names and in tab",
    category = ModuleCategory.UTILITY,
    icon = "item:minecraft:mace",
    defaultX = 8,
    defaultY = 478,
    defaultEnabled = false
) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    companion object {
        private const val HIGHEST = "Highest"
    }

    /** Index into [TierService.MODES], or that list's size for "highest of all modes". */
    private fun modeIndex(): Int = scalar("mode", TierService.MODES.indexOf("sword"))
        .coerceIn(0, TierService.MODES.size)

    private fun modeKey(): String? = TierService.MODES.getOrNull(modeIndex())

    fun showAboveHeads(): Boolean = flag("above_heads", true)

    fun showInTab(): Boolean = flag("in_tab", true)

    private fun fallbackToHighest(): Boolean = flag("fallback", true)

    private fun dimRetired(): Boolean = flag("dim_retired", true)

    private fun showIcons(): Boolean = flag("icons", true)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Tiers"))
        list.add(ChoiceSetting(
            "Game mode",
            TierService.MODE_LABELS + HIGHEST,
            { TierService.MODE_LABELS.getOrNull(modeIndex()) ?: HIGHEST },
            { choice ->
                val index = TierService.MODE_LABELS.indexOf(choice)
                setScalar("mode", if (index < 0) TierService.MODES.size else index)
            }
        ))
        list.add(BoolSetting("Use highest when unranked in it", { fallbackToHighest() }, { setFlag("fallback", it) }))
        list.add(SectionSetting("Where"))
        list.add(BoolSetting("Above heads", { showAboveHeads() }, { setFlag("above_heads", it) }))
        list.add(BoolSetting("In tab list", { showInTab() }, { setFlag("in_tab", it) }))
        list.add(BoolSetting("Dim retired tiers", { dimRetired() }, { setFlag("dim_retired", it) }))
        list.add(BoolSetting("Mode icons", { showIcons() }, { setFlag("icons", it) }))
    }

    /**
     * The tag for a player, or null when they are unranked, not looked up yet, or the
     * module is off. Called from the name mixins, so it never blocks: a miss schedules
     * the lookup and the tag simply appears a moment later.
     */
    fun tagFor(uuid: UUID?): Component? {
        if (!active || uuid == null) return null
        val mode = modeKey()
        // Carry the mode along with the ranking: when the tag falls back to someone's
        // best tier, the icon has to be that tier's mode, not the one picked in settings.
        val direct = if (mode == null) null else TierService.ranking(uuid, mode)?.let { mode to it }
        val fallback = if (direct == null && (mode == null || fallbackToHighest())) TierService.best(uuid) else null
        val (shownMode, ranking) = direct ?: fallback ?: return null

        val color = if (ranking.retired && dimRetired()) ChatFormatting.DARK_GRAY else tierColor(ranking.tier)
        val label = Component.literal(ranking.label).withStyle(color)
        val icon = if (showIcons()) TierIcons.icon(shownMode) else null
        if (icon != null) {
            // The icon already marks where the tag starts, so it needs no brackets.
            return Component.empty().append(icon).append(Component.literal(" ")).append(label)
        }
        return Component.literal("[").withStyle(ChatFormatting.DARK_GRAY)
            .append(label)
            .append(Component.literal("]").withStyle(ChatFormatting.DARK_GRAY))
    }

    /** Tier 1 is the top of the list, so it gets the loudest colour and 5 the quietest. */
    private fun tierColor(tier: Int): ChatFormatting = when (tier) {
        1 -> ChatFormatting.GOLD
        2 -> ChatFormatting.RED
        3 -> ChatFormatting.YELLOW
        4 -> ChatFormatting.GREEN
        5 -> ChatFormatting.AQUA
        else -> ChatFormatting.GRAY
    }
}
