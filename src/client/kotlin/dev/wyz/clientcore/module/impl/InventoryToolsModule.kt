package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.screen.RiverListScreen
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * Items glide between slots when they move, and buttons on inventories that run a chat command
 * you set. A button only sends its command when you press it; nothing is automated.
 */
class InventoryToolsModule : Module("inventory_tools", "Inventory Tools", "Items glide between slots, plus command buttons", ModuleCategory.UTILITY, "item:minecraft:hopper", 8, 492, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private companion object {
        const val MAX_BUTTONS = 20
        const val MAX_LENGTH = 60
        const val MAX_LABEL = 14
        const val BUTTON_H = 16
        const val BUTTON_STEP = 18
        const val SIDE_GAP = 100
        var installed = false
    }

    val glide: Boolean get() = flag("inv_glide", true)
    private fun rightSide() = flag("inv_right", false)
    private fun offsetX() = scalar("inv_x", 0)
    private fun offsetY() = scalar("inv_y", 0)
    private fun entries(): MutableList<String> = ClientCore.config.inventoryButtonList()

    init {
        if (!installed) {
            installed = true
            ScreenEvents.AFTER_INIT.register { _, screen, width, height ->
                if (active && screen is AbstractContainerScreen<*>) addButtons(screen, width, height)
            }
        }
    }

    private fun addButtons(screen: AbstractContainerScreen<*>, width: Int, height: Int) {
        val font = Minecraft.getInstance().font
//? if >=26.1 {
/*        val widgets = Screens.getWidgets(screen)
*///?} else {
        val widgets = Screens.getButtons(screen)
//?}
        entries().filter { it.isNotBlank() }.take(MAX_BUTTONS).forEachIndexed { index, raw ->
            val command = raw.substringAfter('=').trim()
            val label = (if ('=' in raw) raw.substringBefore('=').trim() else command.removePrefix("/")).take(MAX_LABEL)
            val w = font.width(label) + 10
            val x = (if (rightSide()) width / 2 + SIDE_GAP else width / 2 - SIDE_GAP - w) + offsetX()
            val y = height / 2 - 80 + index * BUTTON_STEP + offsetY()
            widgets.add(Button.builder(Component.literal(label)) { send(command) }.bounds(x, y, w, BUTTON_H).build())
        }
    }

    private fun send(command: String) {
        val connection = Minecraft.getInstance().player?.connection ?: return
        if (command.startsWith("/")) connection.sendCommand(command.removePrefix("/")) else connection.sendChat(command)
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Items"))
        list.add(BoolSetting("Smooth item moves", { glide }, { setFlag("inv_glide", it) }))
        list.add(SectionSetting("Buttons"))
        list.add(ActionSetting("Buttons", "Edit") {
            val client = Minecraft.getInstance()
            client.setScreen(
                RiverListScreen(
                    client.screen, "Inventory buttons", "box", "Pets=/pets", MAX_LENGTH, MAX_BUTTONS,
                    items = { entries() }, isChecked = null, onToggle = {},
                    onAdd = { entries().add(it) }, onRemove = { entries().removeAt(it) }
                )
            )
        })
        list.add(ChoiceSetting("Side", listOf("Left", "Right"), { if (rightSide()) "Right" else "Left" }, { setFlag("inv_right", it == "Right") }))
        list.add(IntSetting("Move across", -200, 200, { offsetX() }, { setScalar("inv_x", it) }))
        list.add(IntSetting("Move down", -200, 200, { offsetY() }, { setScalar("inv_y", it) }))
    }
}

/**
 * Spots a stack leaving one slot and arriving in another in the same frame, then draws it flying
 * between them while the slot it landed in stays empty. Items that only arrive or only leave
 * (picked up, thrown, sent by the server) are left alone.
 */
object InventoryGlide {
    private class Flight(val stack: ItemStack, val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val toIndex: Int, val startedAt: Long)

    private const val DURATION_MS = 130f
    /** More than this at once is a page of a server menu changing, not items being moved. */
    private const val MAX_MOVES = 12

    private var module: InventoryToolsModule? = null
    private var menu: AbstractContainerMenu? = null
    private var seen: Array<ItemStack> = emptyArray()
    private val flights = ArrayList<Flight>()
    private class Left(val index: Int, val stack: ItemStack, val x: Int, val y: Int, val at: Long)
    private val left = ArrayList<Left>()
    private const val LEFT_MEMORY_MS = 200L

    private fun on(): Boolean {
        val m = module ?: ModuleRegistry.get<InventoryToolsModule>("inventory_tools")?.also { module = it } ?: return false
        return m.active && m.glide
    }

    @JvmStatic
    fun beforeDraw(screen: AbstractContainerScreen<*>) {
        if (!on()) return
        val current = screen.menu
        val slots = current.slots
        if (current !== menu || slots.size != seen.size) {
            menu = current
            seen = Array(slots.size) { slots[it].item.copy() }
            flights.clear()
            return
        }
        var changed: ArrayList<Int>? = null
        for (i in slots.indices) {
            if (!ItemStack.matches(seen[i], slots[i].item)) (changed ?: ArrayList<Int>().also { changed = it }).add(i)
        }
        val moved = changed ?: return
        val time = System.currentTimeMillis()

        // The server can send the two halves of a move a frame or two apart, so what left a slot
        // is remembered for a moment instead of having to arrive in the same frame.
        for (i in moved) {
            val before = seen[i]
            val now = slots[i].item
            if (!before.isEmpty && (now.isEmpty || !ItemStack.isSameItemSameComponents(before, now) || now.count < before.count)) {
                left.add(Left(i, before, slots[i].x, slots[i].y, time))
            }
        }
        left.removeAll { time - it.at > LEFT_MEMORY_MS }
        val gained = moved.filter { i ->
            val before = seen[i]
            val now = slots[i].item
            !now.isEmpty && (before.isEmpty || !ItemStack.isSameItemSameComponents(before, now) || now.count > before.count)
        }
        if (gained.size <= MAX_MOVES) {
            for (to in gained) {
                val now = slots[to].item
                val from = left.firstOrNull { it.index != to && ItemStack.isSameItemSameComponents(it.stack, now) } ?: continue
                left.remove(from)
                flights.add(Flight(now.copy(), from.x, from.y, slots[to].x, slots[to].y, to, time))
            }
        }
        for (i in moved) seen[i] = slots[i].item.copy()
    }

    /** True while the item in this slot is still on its way there. */
    @JvmStatic
    fun hides(slot: Slot): Boolean = flights.isNotEmpty() && flights.any { it.toIndex == slot.index }

    @JvmStatic
    fun afterDraw(g: GuiGraphics, left: Int, top: Int) {
        if (flights.isEmpty()) return
        val time = System.currentTimeMillis()
        flights.removeAll { time - it.startedAt >= DURATION_MS }
        val font = Minecraft.getInstance().font
        for (flight in flights) {
            val t = (time - flight.startedAt) / DURATION_MS
            val eased = 1f - (1f - t) * (1f - t)
            val x = left + flight.fromX + ((flight.toX - flight.fromX) * eased).toInt()
            val y = top + flight.fromY + ((flight.toY - flight.fromY) * eased).toInt()
            g.renderItem(flight.stack, x, y)
            g.renderItemDecorations(font, flight.stack, x, y)
        }
    }
}
