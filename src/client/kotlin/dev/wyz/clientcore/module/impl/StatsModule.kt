package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import java.util.ArrayDeque

/**
 * FPS, CPS, ping and memory in one readout.
 *
 * These were four separate modules, each drawing a single line in its own pill.
 * Four things to enable, four things to drag into a column, and four pill
 * backgrounds stacked up the side of the screen. They are all "how is the game
 * running right now", so they are one module with a toggle per row.
 */
class StatsModule : Module("stats", "Stats", "FPS, CPS, ping and memory", ModuleCategory.HUD, "gauge", 8, 8) {

    private val clicks = ArrayDeque<Long>()
    private var wasAttackDown = false

    private fun showFps() = flag("fps", true)
    private fun showCps() = flag("cps", true)
    private fun showPing() = flag("ping", true)
    private fun showMemory() = flag("memory", false)

    /** Consulted by the tab overlay mixin. Lived on the old Ping module. */
    fun showInTab(): Boolean = active && effectivePingCfg().showInTab

    /** More than one row means the "Line spacing" style option is worth showing. */
    override val multiLine: Boolean
        get() = listOf(showFps(), showCps(), showPing(), showMemory()).count { it } > 1

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Readouts"))
        list.add(BoolSetting("FPS", { flag("fps", true) }, { setFlag("fps", it) }))
        list.add(BoolSetting("CPS", { flag("cps", true) }, { setFlag("cps", it) }))
        list.add(BoolSetting("Ping", { flag("ping", true) }, { setFlag("ping", it) }))
        list.add(BoolSetting("Memory", { flag("memory", false) }, { setFlag("memory", it) }))
        list.add(SectionSetting("Tab list"))
        list.add(BoolSetting("Show ping numbers in tab", { mutablePing().showInTab }, { mutablePing().showInTab = it }))
    }

    override fun tick(client: Minecraft) {
        if (!showCps()) return
        val attackDown = client.options.keyAttack.isDown
        val now = System.currentTimeMillis()
        if (attackDown && !wasAttackDown) clicks.addLast(now)
        while (clicks.isNotEmpty() && now - clicks.first > 1000L) clicks.removeFirst()
        wasAttackDown = attackDown
    }

    private fun memoryLine(): String {
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val max = runtime.maxMemory() / 1024 / 1024
        return "$used / $max MB"
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val rows = ArrayList<Pair<String, String>>(4)
        if (showFps()) rows.add("FPS" to "${client.fps}")
        if (showCps()) rows.add("CPS" to "${clicks.size}")
        if (showPing()) {
            val player = client.player
            val ping = if (player == null) 0 else client.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
            rows.add("Ping" to "${ping}ms")
        }
        if (showMemory()) rows.add("Mem" to memoryLine())
        drawRows(client, graphics, rows)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val rows = ArrayList<Pair<String, String>>(4)
        if (showFps()) rows.add("FPS" to "241")
        if (showCps()) rows.add("CPS" to "7")
        if (showPing()) rows.add("Ping" to "24ms")
        if (showMemory()) rows.add("Mem" to "1204 / 4096 MB")
        drawRows(client, graphics, rows)
    }

    /**
     * Label column and value column, so the numbers line up down the readout
     * instead of jittering left and right as the values change width.
     */
    private fun drawRows(client: Minecraft, graphics: GuiGraphics, rows: List<Pair<String, String>>) {
        if (rows.isEmpty()) return
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val gap = 6
        val lineH = font.lineHeight + 2 + st.spacing
        val labelW = rows.maxOf { font.width(it.first) }
        val valueW = rows.maxOf { font.width(it.second) }
        val w = pad + 4 + labelW + gap + valueW + pad
        val h = pad * 2 + rows.size * lineH - 2

        drawPillBackground(graphics, w, h)
        val value = panelTextColor()
        val lx = x + pad + 4
        rows.forEachIndexed { i, row ->
            val ly = y + pad + i * lineH
            graphics.drawString(font, row.first, lx, ly, labelColor, st.textShadow)
            graphics.drawString(font, row.second, lx + labelW + gap, ly, value, st.textShadow)
        }
    }
}
