package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.screen.RiverListScreen
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.network.chat.TextColor
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * A second chat box that can sit anywhere and only keeps the lines with your name or your words,
 * and the same lines marked in the normal chat. Reads what the game already received.
 */
class ChatWindowModule : Module("chat_window", "Chat Window", "A movable chat box for chosen words", ModuleCategory.HUD, "item:minecraft:name_tag", 8, 440, false) {
    override val multiLine: Boolean = true

    private companion object {
        const val MAX_WORDS = 30
        const val MAX_WORD_LENGTH = 40
        const val MAX_KEPT = 60
        const val BOX_WIDTH = 220
    }

    private val kept = ArrayDeque<String>()

    private fun words(): MutableList<String> = ClientCore.config.chatWordList()
    private fun includeName() = flag("cw_name", true)
    private fun showWindow() = flag("cw_window", true)
    private fun highlight() = flag("cw_highlight", true)
    private fun lines() = scalar("cw_lines", 6).coerceIn(2, 15)
    private fun color() = scalar("cw_color", 0xFFD46B) and 0xFFFFFF

    /** True when this line has your name or one of your words, and is not something you wrote. */
    private fun matches(text: String): Boolean {
        val name = Minecraft.getInstance().user?.name.orEmpty()
        if (name.isNotEmpty()) {
            val head = text.substringBefore(':', text.substringBefore('>', ""))
            if (head.contains(name, ignoreCase = true)) return false
            if (includeName() && text.contains(name, ignoreCase = true)) return true
        }
        return words().any { it.isNotBlank() && text.contains(it, ignoreCase = true) }
    }

    private fun record(message: Component, shown: Component): Component {
        if (!active) return shown
        val text = message.string
        if (!matches(text)) return shown
        if (showWindow()) {
            kept.addLast(text)
            while (kept.size > MAX_KEPT) kept.removeFirst()
        }
        if (!highlight()) return shown
        val mark = Style.EMPTY.withColor(TextColor.fromRgb(color()))
        return Component.empty().append(Component.literal("▌ ").withStyle(mark)).append(shown.copy().withStyle { it.applyTo(mark) })
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Chat Window"))
        list.add(ActionSetting("Words", "Edit") {
            val client = Minecraft.getInstance()
            client.setScreen(
                RiverListScreen(
                    client.screen, "Chat words", "search", "Add a word", MAX_WORD_LENGTH, MAX_WORDS,
                    items = { words() }, isChecked = null, onToggle = {},
                    onAdd = { words().add(it) }, onRemove = { words().removeAt(it) }
                )
            )
        })
        list.add(BoolSetting("Your name counts", { includeName() }, { setFlag("cw_name", it) }))
        list.add(BoolSetting("Show the window", { showWindow() }, { setFlag("cw_window", it) }))
        list.add(IntSetting("Lines", 2, 15, { lines() }, { setScalar("cw_lines", it) }))
        list.add(BoolSetting("Mark them in chat", { highlight() }, { setFlag("cw_highlight", it) }))
        list.add(ColorSetting("Mark color", false, { color() or 0xFF000000.toInt() }, { setScalar("cw_color", it and 0xFFFFFF) }))
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (showWindow() && kept.isNotEmpty()) draw(client, graphics, kept.toList())
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        draw(client, graphics, kept.toList().ifEmpty { listOf("Lines with your words show up here") })
    }

    private fun draw(client: Minecraft, graphics: GuiGraphics, messages: List<String>) {
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val lineH = font.lineHeight + 1 + st.spacing
        val wrapped = ArrayList<String>()
        for (message in messages) {
            var rest = message
            while (rest.isNotEmpty()) {
                val part = font.plainSubstrByWidth(rest, BOX_WIDTH).ifEmpty { rest.take(1) }
                wrapped.add(part)
                rest = rest.substring(part.length).trimStart()
            }
        }
        val shown = wrapped.takeLast(lines())
        drawPillBackground(graphics, BOX_WIDTH + pad * 2, pad * 2 + shown.size * lineH - 1)
        val text = panelTextColor()
        shown.forEachIndexed { i, line -> graphics.drawString(font, line, x + pad, y + pad + i * lineH, text, st.textShadow) }
    }

    /** Called with each chat line as it arrives; returns the line to show in the normal chat. */
    fun onChat(message: Component, shown: Component): Component = record(message, shown)
}

/** Java-side entry, so the chat mixin does not have to look the module up itself. */
object ChatWindowHook {
    @JvmStatic
    fun apply(message: Component, shown: Component): Component =
        ModuleRegistry.get<ChatWindowModule>("chat_window")?.onChat(message, shown) ?: shown
}
