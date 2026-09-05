package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.pvp.TotemPopTracker
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Items

/**
 * One HUD panel that replaces the separate Pearl/Totem/Gapple/Combo/Totem-pop
 * counter modules. Each row is toggled independently in the module settings and
 * the whole thing renders as a single stacked "LABEL value" panel.
 */
class CountersModule : Module("counters", "Counters", "Item and combat counters in one panel", ModuleCategory.HUD, "totem", 8, 244, false) {

    override val multiLine: Boolean = true

    // Combo tracking (moved from the old Combo Counter module).
    private var lastAttackDown = false
    private var combo = 0
    private var lastTargetId = -1
    private var lastHitAt = 0L

    private fun showPearls() = flag("pearls", true)
    private fun showTotems() = flag("totems", true)
    private fun showGapples() = flag("gapples", true)
    private fun showCrapples() = flag("crapples", true)
    private fun showCombo() = flag("combo", false)
    private fun showPops() = flag("pops", false)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Counters"))
        list.add(BoolSetting("Ender pearls", { flag("pearls", true) }, { setFlag("pearls", it) }))
        list.add(BoolSetting("Totems", { flag("totems", true) }, { setFlag("totems", it) }))
        list.add(BoolSetting("Golden apples", { flag("gapples", true) }, { setFlag("gapples", it) }))
        list.add(BoolSetting("Enchanted golden apples", { flag("crapples", true) }, { setFlag("crapples", it) }))
        list.add(BoolSetting("Combo (hits on target)", { flag("combo", false) }, { setFlag("combo", it) }))
        list.add(BoolSetting("Totem pops (players)", { flag("pops", false) }, { setFlag("pops", it) }))
    }

    /** Rebuilt at the configured HUD update rate; rendering every frame reuses the cache. */
    private var cachedEntries: List<Pair<String, String>> = emptyList()
    private var lastEntriesRebuild = 0L

    override fun tick(client: Minecraft) {
        // Track the melee combo regardless of visibility so it's correct the moment it's shown.
        val attackDown = client.options.keyAttack.isDown
        val now = System.currentTimeMillis()
        val target = currentTarget(client)

        if (attackDown && !lastAttackDown) {
            if (target != null) {
                combo = if (target.id == lastTargetId && now - lastHitAt <= 2500L) combo + 1 else 1
                lastTargetId = target.id
                lastHitAt = now
            } else if (now - lastHitAt > 2500L) {
                combo = 0
                lastTargetId = -1
            }
        }
        if (combo > 0 && now - lastHitAt > 2500L) {
            combo = 0
            lastTargetId = -1
        }
        lastAttackDown = attackDown

        // Combo tracking above stays every tick; the panel text is rebuilt at the HUD rate.
        val hz = (ModuleRegistry.get<PerformanceModule>("performance")?.hudRateHz() ?: 20).coerceAtLeast(1)
        if (now - lastEntriesRebuild >= 1000L / hz) {
            lastEntriesRebuild = now
            cachedEntries = buildEntries(client)
        }
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawPanelLabeled(client, graphics, cachedEntries, tickDelta)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val entries = mutableListOf<Pair<String, String>>()
        if (showPearls()) entries += "Pearls" to "16"
        if (showTotems()) entries += "Totems" to "3"
        if (showGapples()) entries += "Gapples" to "18"
        if (showCrapples()) entries += "Crapples" to "2"
        if (showCombo()) entries += "Combo" to "6"
        if (showPops()) entries += "WyZ_EU" to "2"
        if (entries.isEmpty()) entries += "Counters" to "off"
        drawPanelLabeled(client, graphics, entries, tickDelta)
    }

    private fun buildEntries(client: Minecraft): List<Pair<String, String>> {
        val player = client.player ?: return emptyList()
        val entries = mutableListOf<Pair<String, String>>()

        val wantPearls = showPearls()
        val wantTotems = showTotems()
        val wantGapples = showGapples()
        val wantCrapples = showCrapples()

        // Single pass over the inventory counts every wanted item at once.
        var pearls = 0
        var totems = 0
        var gapples = 0
        var crapples = 0
        if (wantPearls || wantTotems || wantGapples || wantCrapples) {
            // containerSize already covers main + armor + offhand slots, so one pass
            // counts everything (the old per-item modules double-counted the offhand).
            val inventory = player.inventory
            for (slot in 0 until inventory.containerSize) {
                val stack = inventory.getItem(slot)
                if (stack.isEmpty) continue
                when {
                    stack.`is`(Items.ENDER_PEARL) -> pearls += stack.count
                    stack.`is`(Items.TOTEM_OF_UNDYING) -> totems += stack.count
                    stack.`is`(Items.GOLDEN_APPLE) -> gapples += stack.count
                    stack.`is`(Items.ENCHANTED_GOLDEN_APPLE) -> crapples += stack.count
                }
            }
        }

        if (wantPearls) entries += "Pearls" to pearls.toString()
        if (wantTotems) entries += "Totems" to totems.toString()
        if (wantGapples) entries += "Gapples" to gapples.toString()
        if (wantCrapples) entries += "Crapples" to crapples.toString()
        if (showCombo()) entries += "Combo" to combo.toString()
        if (showPops()) TotemPopTracker.topEntries(4).forEach { entries += it.first to it.second.toString() }
        return entries
    }

    private fun currentTarget(client: Minecraft): Entity? {
        val target = client.crosshairPickEntity
        return if (target?.isAlive == true) target else null
    }
}
