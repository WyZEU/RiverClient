package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.config.TodoItem
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.screen.RiverListScreen
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/** A task list on the screen. Lines are ticked off in its manager, which its key opens. */
class TodoModule : Module("todo", "To-Do", "Your own task list on screen", ModuleCategory.HUD, "item:minecraft:writable_book", 8, 420, false) {
    override val multiLine: Boolean = true
    override val keybindLabel: String = "Open list"

    private companion object {
        const val MAX_ITEMS = 30
        const val MAX_LENGTH = 80
        const val BOX = 7
    }

    private fun items(): MutableList<TodoItem> = ClientCore.config.todoList()
    private fun hideDone() = flag("todo_hide_done", false)

    private fun openManager(client: Minecraft) {
        client.setScreen(
            RiverListScreen(
                client.screen, "To-Do", "list", "Add a task", MAX_LENGTH, MAX_ITEMS,
                items = { items().map { it.text } },
                isChecked = { index -> items()[index].done },
                onToggle = { index -> items()[index].let { it.done = !it.done } },
                onAdd = { text -> items().add(TodoItem(text)) },
                onRemove = { index -> items().removeAt(index) }
            )
        )
    }

    override fun onKeybindPressed(client: Minecraft): Boolean {
        openManager(client)
        return true
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("To-Do"))
        list.add(ActionSetting("Tasks", "Edit") { openManager(Minecraft.getInstance()) })
        list.add(BoolSetting("Hide finished tasks", { hideDone() }, { setFlag("todo_hide_done", it) }))
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica")) {
            list.add(ActionSetting("Litematica materials", "Add missing") { addLitematicaMaterials() })
        }
    }

    /**
     * What the loaded schematic still needs, as tasks, replacing any added before. Litematica is
     * optional, so it is reached by name; with no material list open this adds nothing.
     */
    private fun addLitematicaMaterials() {
        runCatching {
            val list = Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getMaterialList").invoke(null) ?: return
            val entries = list.javaClass.getMethod("getMaterialsAll").invoke(list) as Iterable<*>
            val needed = entries.mapNotNull { entry ->
                val type = entry!!.javaClass
                val missing = type.getMethod("getCountMissing").invoke(entry) as Int
                if (missing <= 0) return@mapNotNull null
                val stack = type.getMethod("getStack").invoke(entry) as net.minecraft.world.item.ItemStack
                TodoItem("$missing x ${stack.hoverName.string}".take(MAX_LENGTH), fromLitematica = true)
            }
            val tasks = items()
            tasks.removeAll { it.fromLitematica }
            tasks.addAll(needed.take((MAX_ITEMS - tasks.size).coerceAtLeast(0)))
            dev.wyz.clientcore.RiverRuntime.saveConfig()
        }.onFailure { println("[River] could not read Litematica's materials: $it") }
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val shown = items().filter { !hideDone() || !it.done }
        if (shown.isNotEmpty()) draw(client, graphics, shown)
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        draw(client, graphics, items().ifEmpty { listOf(TodoItem("Build the base", true), TodoItem("Find diamonds")) })
    }

    private fun draw(client: Minecraft, graphics: GuiGraphics, rows: List<TodoItem>) {
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val lineH = font.lineHeight + 2 + st.spacing
        val w = pad * 2 + BOX + 5 + rows.maxOf { font.width(it.text) }
        drawPillBackground(graphics, w, pad * 2 + rows.size * lineH - 2)
        val text = panelTextColor()
        rows.forEachIndexed { i, row ->
            val ly = y + pad + i * lineH
            val boxX = x + pad
            graphics.fill(boxX, ly, boxX + BOX, ly + BOX, labelColor)
            graphics.fill(boxX + 1, ly + 1, boxX + BOX - 1, ly + BOX - 1, if (row.done) text else 0xFF15171D.toInt())
            graphics.drawString(font, row.text, boxX + BOX + 5, ly, if (row.done) labelColor else text, st.textShadow)
        }
    }
}
