package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.dev.DevClock
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * The game's own HUD parts: size of the boss bar, action bar, tab list and title, a background
 * for the ones that have room for it, and a smooth way in. GuiMixin and BossHealthOverlayMixin
 * wrap the vanilla drawing with these; the sidebar's settings live in ScoreboardModule.
 */
class GameHudModule : Module("game_hud", "Game HUD", "Size, background and animation of vanilla HUD parts", ModuleCategory.VISUAL, "item:minecraft:name_tag", 8, 396, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private companion object {
        const val SHOW_MS = 220f
    }

    private var barText: Component? = null
    private var barWasShown = false
    private var barStart = 0L
    private var titleText: Component? = null
    private var titleWasShown = false
    private var titleStart = 0L
    private var tabWasShown = false
    private var tabStart = 0L
    private var demoTicks = 0

    private fun size(key: String) = scalar(key, 100).coerceIn(50, 200) / 100f

    fun bossSize() = size("gh_boss_size")
    fun barSize() = size("gh_bar_size")
    fun tabSize() = size("gh_tab_size")
    fun titleSize() = size("gh_title_size")

    /** Percent of black behind the text, off by default like vanilla. */
    fun barBackground() = scalar("gh_bar_bg", 0).coerceIn(0, 100)
    fun titleBackground() = scalar("gh_title_bg", 0).coerceIn(0, 100)

    /** Percent of vanilla's own background. */
    fun tabBackground() = scalar("gh_tab_bg", 100).coerceIn(0, 100)

    /** How far the action bar's message is through showing up, from 0 to 1. A server resending the same text does not restart it. */
    fun barShow(text: Component?, time: Int): Float {
        val shown = text != null && time > 0
        if (shown && (!barWasShown || text != barText)) barStart = DevClock.millis()
        barWasShown = shown
        if (shown) barText = text
        return if (flag("gh_bar_anim", true)) eased(barStart) else 1f
    }

    fun titleShow(text: Component?, time: Int): Float {
        val shown = text != null && time > 0
        if (shown && (!titleWasShown || text != titleText)) titleStart = DevClock.millis()
        titleWasShown = shown
        if (shown) titleText = text
        return if (flag("gh_title_anim", true)) eased(titleStart) else 1f
    }

    fun tabShow(held: Boolean): Float {
        if (held && !tabWasShown) tabStart = DevClock.millis()
        tabWasShown = held
        return if (flag("gh_tab_anim", true)) eased(tabStart) else 1f
    }

    private fun eased(start: Long): Float {
        val t = ((DevClock.millis() - start) / SHOW_MS).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    /** The dev screenshot's settings: everything changed at once so it shows in one picture. */
    fun startDemo() {
        enabled = true
        demoTicks = 1
        setScalar("gh_boss_size", 150)
        setScalar("gh_bar_size", 150)
        setScalar("gh_bar_bg", 60)
        setScalar("gh_title_size", 70)
        setScalar("gh_title_bg", 50)
    }

    /** The action bar only lasts three seconds, so the screenshot keeps sending it. */
    override fun tick(client: Minecraft) {
        if (demoTicks > 0 && demoTicks++ % 40 == 0) client.player?.connection?.sendCommand("title @s actionbar \"River action bar\"")
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Boss bar"))
        list.add(IntSetting("Size", 50, 200, { scalar("gh_boss_size", 100) }, { setScalar("gh_boss_size", it) }, "%"))

        list.add(SectionSetting("Action bar"))
        list.add(IntSetting("Size", 50, 200, { scalar("gh_bar_size", 100) }, { setScalar("gh_bar_size", it) }, "%"))
        list.add(IntSetting("Background", 0, 100, { barBackground() }, { setScalar("gh_bar_bg", it) }, "%"))
        list.add(BoolSetting("Animation", { flag("gh_bar_anim", true) }, { setFlag("gh_bar_anim", it) }))

        list.add(SectionSetting("Tab list"))
        list.add(IntSetting("Size", 50, 200, { scalar("gh_tab_size", 100) }, { setScalar("gh_tab_size", it) }, "%"))
        list.add(IntSetting("Background", 0, 100, { tabBackground() }, { setScalar("gh_tab_bg", it) }, "%"))
        list.add(BoolSetting("Animation", { flag("gh_tab_anim", true) }, { setFlag("gh_tab_anim", it) }))

        list.add(SectionSetting("Title and subtitle"))
        list.add(IntSetting("Size", 50, 200, { scalar("gh_title_size", 100) }, { setScalar("gh_title_size", it) }, "%"))
        list.add(IntSetting("Background", 0, 100, { titleBackground() }, { setScalar("gh_title_bg", it) }, "%"))
        list.add(BoolSetting("Animation", { flag("gh_title_anim", true) }, { setFlag("gh_title_anim", it) }))
    }
}
