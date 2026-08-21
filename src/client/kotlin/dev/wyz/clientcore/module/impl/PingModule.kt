package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

class PingModule : Module("ping", "Ping", "Current server latency", ModuleCategory.HUD, "wifi", 8, 246) {

    /** Consulted by the tab overlay mixin. */
    fun showInTab(): Boolean = active && effectivePingCfg().showInTab

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Tab list"))
        list.add(BoolSetting("Show ping numbers in tab", { mutablePing().showInTab }, { mutablePing().showInTab = it }))
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val player = client.player ?: return
        val ping = client.connection?.getPlayerInfo(player.uuid)?.latency ?: 0
        drawStat(client, graphics, "Ping", "${ping}ms")
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawStat(client, graphics, "Ping", "24ms")
    }
}
