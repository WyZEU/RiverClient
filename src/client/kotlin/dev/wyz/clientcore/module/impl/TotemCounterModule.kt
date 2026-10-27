package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.pvp.TotemPopTracker
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.util.UUID

/**
 * Modelled on TotemCounter (uku3lig, MIT): how many totems you are carrying, as a totem
 * and a number on the HUD, and how many each player has popped, after their name. Pops
 * come from [TotemPopTracker], which counts entity event 35 whether or not this module
 * is on, so turning it on mid-fight shows the right numbers straight away.
 */
class TotemCounterModule : Module("totem_counter", "Totem Counter", "Your totems, and pops above names", ModuleCategory.HUD, "item:minecraft:totem_of_undying", 8, 276, false) {

    // Lazy: modules are built while the mod loads, and 26.2 refuses to make an item stack
    // before its item components are bound, which crashed the game on startup.
    private val totem by lazy { ItemStack(Items.TOTEM_OF_UNDYING) }

    fun showOnNametags() = active && flag("nametags", true)
    fun showInTab() = active && flag("tab", false)
    private fun separator() = flag("separator", true)
    private fun popColors() = flag("pop_colors", true)
    private fun countColors() = flag("count_colors", true)
    private fun hideWhenNone() = flag("hide_empty", true)
    private fun chatOnPop() = flag("chat", false)
    fun resetOnDeath() = flag("reset_death", true)
    fun resetOnRoundEnd() = flag("reset_round", true)

    override val keybindLabel: String = "Reset key"

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Your totems"))
        list.add(BoolSetting("Colour by amount", { flag("count_colors", true) }, { setFlag("count_colors", it) }))
        list.add(BoolSetting("Hide when you have none", { flag("hide_empty", true) }, { setFlag("hide_empty", it) }))
        list.add(SectionSetting("Pop counter"))
        list.add(BoolSetting("Show on nametags", { flag("nametags", true) }, { setFlag("nametags", it) }))
        list.add(BoolSetting("Show in tab list", { flag("tab", false) }, { setFlag("tab", it) }))
        list.add(BoolSetting("Separator", { flag("separator", true) }, { setFlag("separator", it) }))
        list.add(BoolSetting("Colour by pops", { flag("pop_colors", true) }, { setFlag("pop_colors", it) }))
        list.add(BoolSetting("Chat message on pop", { flag("chat", false) }, { setFlag("chat", it) }))
        list.add(SectionSetting("Reset"))
        list.add(BoolSetting("Reset when a player dies", { flag("reset_death", true) }, { setFlag("reset_death", it) }))
        list.add(BoolSetting("Reset when a round ends", { flag("reset_round", true) }, { setFlag("reset_round", it) }))
        list.add(ActionSetting("Pop counts", "Reset now") { reset(Minecraft.getInstance()) })
    }

    override fun onKeybindPressed(client: Minecraft): Boolean {
        reset(client)
        return true
    }

    private fun reset(client: Minecraft) {
        TotemPopTracker.clear()
        client.gui.chat.addMessage(
            Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal("Totem pop counter reset.").withStyle(ChatFormatting.GRAY))
        )
    }

    fun onPop(player: Player, pops: Int) {
        if (!active || !chatOnPop()) return
        val client = Minecraft.getInstance()
        client.gui.chat.addMessage(
            Component.literal("[River] ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(player.name.string).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" popped ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(if (pops == 1) "a totem" else "$pops totems").withStyle(Style.EMPTY.withColor(popColor(pops) and 0xFFFFFF)))
        )
    }

    /** " | -3" after a name, or null while that player has not popped. */
    fun popSuffix(uuid: UUID): Component? {
        val pops = TotemPopTracker.pops(uuid)
        if (pops <= 0) return null
        val suffix: MutableComponent = Component.literal(" ")
        if (separator()) suffix.append(Component.literal("| ").withStyle(ChatFormatting.GRAY))
        val count = Component.literal("-$pops")
        suffix.append(if (popColors()) count.withStyle(Style.EMPTY.withColor(popColor(pops) and 0xFFFFFF)) else count)
        return suffix
    }

    // TotemCounter's scales: pops go green to red as they pile up, a stock of totems goes
    // the other way as it runs out.
    private fun popColor(pops: Int): Int = when (pops) {
        1, 2 -> 0xFF55FF55.toInt()
        3, 4 -> 0xFF00AA00.toInt()
        5, 6 -> 0xFFFFFF55.toInt()
        7, 8 -> 0xFFFFAA00.toInt()
        else -> 0xFFFF5555.toInt()
    }

    private fun totemColor(count: Int): Int = when (count) {
        1, 2 -> 0xFFFF5555.toInt()
        3, 4 -> 0xFFFFAA00.toInt()
        5, 6 -> 0xFFFFFF55.toInt()
        7, 8 -> 0xFF00AA00.toInt()
        else -> 0xFF55FF55.toInt()
    }

    private fun carried(player: Player): Int {
        var total = 0
        val inventory = player.inventory
        // containerSize covers the main inventory, armour and offhand in one pass.
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.`is`(Items.TOTEM_OF_UNDYING)) total += stack.count
        }
        return total
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val player = client.player ?: return
        val count = carried(player)
        if (count == 0 && hideWhenNone()) return
        draw(client, graphics, count)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        draw(client, graphics, client.player?.let { carried(it) }?.takeIf { it > 0 } ?: 4)
    }

    private fun draw(client: Minecraft, graphics: GuiGraphics, count: Int) {
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val text = count.toString()
        val w = pad + 16 + 3 + font.width(text) + pad
        val h = pad * 2 + 16
        drawPillBackground(graphics, w, h)
        graphics.renderItem(totem, x + pad, y + pad)
        val color = if (countColors()) totemColor(count) else panelTextColor()
        graphics.drawString(font, text, x + pad + 19, y + pad + 4, color, st.textShadow)
    }
}
