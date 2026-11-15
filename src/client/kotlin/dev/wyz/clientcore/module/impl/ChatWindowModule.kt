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
        const val MARK = 0x258C // the bar at the start of a marked line
    }

    private val kept = ArrayDeque<String>()

    private fun words(): MutableList<String> = ClientCore.config.chatWordList()
    private fun includeName() = flag("cw_name", true)
    private fun showWindow() = flag("cw_window", true)
    private fun highlight() = flag("cw_highlight", true)
    private fun background() = flag("cw_background", true)
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
            // Badges are private characters drawn by River's own font; as plain text they show as boxes.
            kept.addLast(text.filter { Character.getType(it) != Character.PRIVATE_USE.toInt() }.trim())
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
        list.add(BoolSetting("Colored background", { background() }, { setFlag("cw_background", it) }))
        list.add(ColorSetting("Mark color", false, { color() or 0xFF000000.toInt() }, { setScalar("cw_color", it and 0xFFFFFF) }))
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (highlight() && background()) drawChatBackgrounds(client, graphics)
        if (showWindow() && kept.isNotEmpty()) draw(client, graphics, kept.toList())
    }

    /**
     * Tints the marked lines in the normal chat. Only the wrapped line that starts an entry carries
     * the mark, so a line is looked up by walking to the top of its entry. The place of each line
     * is the same sum vanilla uses: lines go up from 40 above the bottom of the screen.
     */
    private fun drawChatBackgrounds(client: Minecraft, g: GuiGraphics) {
        val chat = client.gui.chat
        val access = chat as dev.wyz.clientcore.mixin.ChatComponentAccess
        val lines = access.clientcore_lines()
        if (lines.isEmpty()) return
        val focused = chat.isChatFocused
        val scale = access.clientcore_scale().toFloat()
        val lineHeight = access.clientcore_lineHeight()
        val width = (access.clientcore_width() / scale).toInt()
        val bottom = ((g.guiHeight() - 40) / scale).toInt()
        val scroll = access.clientcore_scroll()
//? if >=26.2 {
/*        val tick = client.gui.hud.guiTicks
*///?} else {
        val tick = client.gui.guiTicks
//?}
        for (row in 0 until chat.linesPerPage) {
            val index = row + scroll
            if (index >= lines.size) break
            val age = tick - lines[index].addedTime()
            if (!focused && age >= 200) continue
            var top = index
            while (top + 1 < lines.size && !lines[top + 1].endOfEntry()) top++
            if (!startsWithMark(lines[top].content())) continue
            val fade = if (focused) 1f else (((1f - age / 200f) * 10f).coerceIn(0f, 1f)).let { it * it }
            val alpha = (fade * 70f).toInt()
            if (alpha < 4) continue
            val y = bottom - row * lineHeight
            g.fill(0, ((y - lineHeight) * scale).toInt(), ((width + 8) * scale).toInt(), (y * scale).toInt(), (alpha shl 24) or color())
        }
    }

    private fun startsWithMark(content: net.minecraft.util.FormattedCharSequence): Boolean {
        var first = 0
        content.accept { _, _, codePoint -> first = codePoint; false }
        return first == MARK
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
                var part = font.plainSubstrByWidth(rest, BOX_WIDTH).ifEmpty { rest.take(1) }
                // Break at a space when there is one, not in the middle of a word.
                if (part.length < rest.length) part.lastIndexOf(' ').takeIf { it > 0 }?.let { part = part.substring(0, it) }
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
