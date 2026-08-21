package dev.wyz.clientcore.module

import dev.wyz.clientcore.ClientCore
import dev.wyz.clientcore.config.ArmorHudSettings
import dev.wyz.clientcore.config.CombatVisualsSettings
import dev.wyz.clientcore.config.CrosshairSettings
import dev.wyz.clientcore.config.FreelookSettings
import dev.wyz.clientcore.config.InventoryHudSettings
import dev.wyz.clientcore.config.ModuleConfig
import dev.wyz.clientcore.config.ChatTweaksSettings
import dev.wyz.clientcore.config.NameTagSettings
import dev.wyz.clientcore.config.PingSettings
import dev.wyz.clientcore.config.PotionHudSettings
import dev.wyz.clientcore.config.ScoreboardSettings
import dev.wyz.clientcore.config.ScreenshotSettings
import dev.wyz.clientcore.config.SharedHudStyle
import dev.wyz.clientcore.config.ToggleSprintSettings
import dev.wyz.clientcore.config.WaypointModuleSettings
import dev.wyz.clientcore.config.ZoomSettings
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.KeybindSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.safety.ServerSafety
import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics

abstract class Module(
    val id: String,
    val displayName: String,
    val description: String,
    val category: ModuleCategory,
    val icon: String,
    val defaultX: Int,
    val defaultY: Int,
    private val defaultEnabled: Boolean = true
) {
    /** Resolved on every access so profile switches take effect immediately. */
    private val config: ModuleConfig
        get() = ClientCore.config.modules.getOrPut(id) { ModuleConfig(defaultEnabled, defaultX, defaultY) }

    var enabled: Boolean
        get() = config.enabled
        set(value) { config.enabled = value }

    /** Enabled AND allowed by the per-server rules. Rendering and ticking key off this. */
    val active: Boolean
        get() = enabled && !comingSoon && ServerSafety.allows(this)

    /** Listed in the menu but not usable yet. */
    open val comingSoon: Boolean = false

    /** Hidden from the module grid/search (managed elsewhere, e.g. the Wardrobe). */
    open val showInMenu: Boolean = true

    /** Modules that some servers dislike (e.g. Freelook) surface an extra warning in the UI. */
    open val serverSensitive: Boolean = false

    var favorite: Boolean
        get() = ClientCore.config.favoritesList().contains(id)
        set(value) {
            val list = ClientCore.config.favoritesList()
            if (value) { if (!list.contains(id)) list.add(id) } else list.remove(id)
        }

    var x: Int
        get() = config.x
        set(value) { config.x = value }

    var y: Int
        get() = config.y
        set(value) { config.y = value }

    /** False = auto-stacked by [dev.wyz.clientcore.hud.HudLayout]; true = user dragged it somewhere. */
    var placed: Boolean
        get() = config.placed
        set(value) { config.placed = value }

    /** Which auto-stack this element flows into while un-placed. */
    open val hudStack: HudStack = HudStack.TOP_LEFT

    var keybind: Int
        get() = config.keybind
        set(value) { config.keybind = value }

    open val editorProfile: ModuleEditorProfile = ModuleEditorProfile.TEXT_PANEL

    open fun acceptsDraggablePosition(): Boolean = true

    open fun showPositionControlsInEditor(): Boolean = true

    /**
     * Called when the module keybind is pressed. Return true to consume the press
     * (e.g. open a screen) instead of the default enable/disable toggle.
     */
    open fun onKeybindPressed(client: Minecraft): Boolean = false

    /** Label for the keybind row in settings ("Toggle key" unless the bind does something else). */
    open val keybindLabel: String = "Toggle key"

    open fun tick(client: Minecraft) = Unit
    open fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) = Unit
    open fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) = render(client, graphics, tickDelta)

    /**
     * Runs [body] scaled by this module's scale factor about its (x, y) anchor, so the
     * whole readout (text and pill) grows/shrinks together. Callers use this instead of
     * calling [render]/[renderEditorPreview] directly so the Scale setting actually
     * affects the drawn content and not just the editor's bounding box.
     */
    fun renderScaled(graphics: GuiGraphics, body: () -> Unit) {
        val s = scaleFactor()
        if (s == 1f) {
            body()
            return
        }
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(s, s)
        pose.translate(-x.toFloat(), -y.toFloat())
        body()
        pose.popMatrix()
    }

    fun scaleFactor(): Float = config.scalePercent.coerceIn(50, 200) / 100f

    fun moveBy(dx: Int, dy: Int) {
        x = (x + dx).coerceAtLeast(0)
        y = (y + dy).coerceAtLeast(0)
    }

    fun resetPosition() {
        x = defaultX
        y = defaultY
        placed = false
        config.anchorH = -1
        config.anchorV = -1
    }

    // ---------------------------------------------------------------- anchored positioning

    /**
     * Records which screen third this element's center sits in and the offset from that
     * anchor, based on the current x/y and on-screen size. Anchors are what make a dragged
     * layout survive resolution and GUI-scale changes: "8px from the right edge" stays 8px
     * from the right edge on any screen, where a raw x drifted into the middle or off-screen.
     */
    fun captureAnchor(client: Minecraft, sw: Int, sh: Int) {
        val (w, h) = editorApproximateSize(client)
        val cx = x + w / 2
        val cy = y + h / 2
        config.anchorH = if (cx < sw / 3) 0 else if (cx > sw * 2 / 3) 2 else 1
        config.anchorV = if (cy < sh / 3) 0 else if (cy > sh * 2 / 3) 2 else 1
        config.offsetX = when (config.anchorH) {
            0 -> x
            2 -> (x + w) - sw
            else -> cx - sw / 2
        }
        config.offsetY = when (config.anchorV) {
            0 -> y
            2 -> (y + h) - sh
            else -> cy - sh / 2
        }
    }

    /**
     * Writes x/y from the stored anchor for the current screen size. Legacy configs
     * (placed before anchors existed, anchorH == -1) are migrated on first resolve: their
     * absolute x/y is still correct for whatever screen they were dragged on, so capturing
     * now preserves the layout exactly and upgrades it in place.
     */
    fun resolveAnchor(client: Minecraft, sw: Int, sh: Int) {
        if (config.anchorH < 0 || config.anchorV < 0) {
            captureAnchor(client, sw, sh)
            return
        }
        val (w, h) = editorApproximateSize(client)
        x = when (config.anchorH) {
            0 -> config.offsetX
            2 -> sw + config.offsetX - w
            else -> sw / 2 + config.offsetX - w / 2
        }.coerceIn(0, (sw - w).coerceAtLeast(0))
        y = when (config.anchorV) {
            0 -> config.offsetY
            2 -> sh + config.offsetY - h
            else -> sh / 2 + config.offsetY - h / 2
        }.coerceIn(0, (sh - h).coerceAtLeast(0))
    }

    open fun resetModuleSettings() {
        resetPosition()
        config.scalePercent = 100
        config.style = SharedHudStyle()
        config.armorHud = null
        config.nameTag = null
        config.crosshair = null
        config.combatVisuals = null
        config.inventoryHud = null
        config.zoom = null
        config.freelook = null
        config.toggleSprint = null
        config.waypointsCfg = null
        config.potionHud = null
        config.scoreboard = null
        config.chatTweaks = null
        config.screenshot = null
        config.ping = null
        config.keybind = -1
    }

    // ---------------------------------------------------------------- settings schema

    /** Full settings list for the slide-out panel: module-specific first, then shared controls. */
    fun settings(): List<Setting> {
        val list = mutableListOf<Setting>()
        addModuleSettings(list)
        if (stylable) addPanelStyleSettings(list)
        list.add(SectionSetting("Keybind"))
        list.add(KeybindSetting(keybindLabel, { keybind }, { keybind = it }))
        if (ServerSafety.currentServerKey() != null) {
            list.add(SectionSetting("Server safety"))
            list.add(BoolSetting(
                "Disable on this server",
                { ServerSafety.isBlockedHere(this) },
                { ServerSafety.setBlockedHere(this, it) }
            ))
        }
        return list
    }

    /** Module-specific settings, shown at the top of the panel. */
    protected open fun addModuleSettings(list: MutableList<Setting>) = Unit

    /** Whether the shared HUD panel styling section applies. */
    protected open val stylable: Boolean
        get() = editorProfile == ModuleEditorProfile.TEXT_PANEL || editorProfile == ModuleEditorProfile.KEYSTROKES

    /** True only for stacked multi-line panels; gates the "Line spacing" style control
     *  so single-line readouts don't show a setting that does nothing. */
    open val multiLine: Boolean = false

    private fun addPanelStyleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Panel"))
        list.add(IntSetting("Scale", 50, 200, { editorScalePercent() }, { editorSetScalePercent(it) }, "%"))
        list.add(BoolSetting("Background", { mutableStyle().showBackground }, { mutableStyle().showBackground = it }))
        list.add(IntSetting("Background opacity", 0, 255, { mutableStyle().backgroundOpacity }, { mutableStyle().backgroundOpacity = it }))
        list.add(BoolSetting("Border", { mutableStyle().showBorder }, { mutableStyle().showBorder = it }))
        list.add(IntSetting("Border opacity", 0, 255, { mutableStyle().borderOpacity }, { mutableStyle().borderOpacity = it }))
        list.add(IntSetting("Padding", 2, 20, { mutableStyle().padding }, { mutableStyle().padding = it }))
        if (multiLine) {
            list.add(IntSetting("Line spacing", 0, 16, { mutableStyle().spacing }, { mutableStyle().spacing = it }))
        }
        list.add(BoolSetting("Text shadow", { mutableStyle().textShadow }, { mutableStyle().textShadow = it }))
    }

    // ---------------------------------------------------------------- config accessors

    protected fun mutableStyle(): SharedHudStyle {
        if (config.style == null) config.style = SharedHudStyle()
        return config.style!!
    }

    protected fun effectiveStyle(): SharedHudStyle = config.effectiveStyle()

    protected fun mutableArmorHud(): ArmorHudSettings {
        if (config.armorHud == null) config.armorHud = ArmorHudSettings()
        return config.armorHud!!
    }

    protected fun effectiveArmorHud(): ArmorHudSettings = config.effectiveArmorHud()

    protected fun mutableNameTag(): NameTagSettings {
        if (config.nameTag == null) config.nameTag = NameTagSettings()
        return config.nameTag!!
    }

    protected fun effectiveNameTag(): NameTagSettings = config.effectiveNameTag()

    protected fun mutableCrosshair(): CrosshairSettings {
        if (config.crosshair == null) config.crosshair = CrosshairSettings()
        return config.crosshair!!
    }

    protected fun effectiveCrosshair(): CrosshairSettings = config.effectiveCrosshair()

    protected fun mutableCombatVisuals(): CombatVisualsSettings {
        if (config.combatVisuals == null) config.combatVisuals = CombatVisualsSettings()
        return config.combatVisuals!!
    }

    protected fun effectiveCombatVisuals(): CombatVisualsSettings = config.effectiveCombatVisuals()

    protected fun mutableInventoryHud(): InventoryHudSettings {
        if (config.inventoryHud == null) config.inventoryHud = InventoryHudSettings()
        return config.inventoryHud!!
    }

    protected fun effectiveInventoryHud(): InventoryHudSettings = config.effectiveInventoryHud()

    protected fun mutableZoom(): ZoomSettings {
        if (config.zoom == null) config.zoom = ZoomSettings()
        return config.zoom!!
    }

    protected fun effectiveZoom(): ZoomSettings = config.effectiveZoom()

    protected fun mutableFreelook(): FreelookSettings {
        if (config.freelook == null) config.freelook = FreelookSettings()
        return config.freelook!!
    }

    protected fun effectiveFreelook(): FreelookSettings = config.effectiveFreelook()

    protected fun mutableToggleSprint(): ToggleSprintSettings {
        if (config.toggleSprint == null) config.toggleSprint = ToggleSprintSettings()
        return config.toggleSprint!!
    }

    protected fun effectiveToggleSprint(): ToggleSprintSettings = config.effectiveToggleSprint()

    protected fun mutableWaypoints(): WaypointModuleSettings {
        if (config.waypointsCfg == null) config.waypointsCfg = WaypointModuleSettings()
        return config.waypointsCfg!!
    }

    protected fun mutablePotionHud(): PotionHudSettings {
        if (config.potionHud == null) config.potionHud = PotionHudSettings()
        return config.potionHud!!
    }

    protected fun effectivePotionHud(): PotionHudSettings = config.effectivePotionHud()

    protected fun mutableScoreboard(): ScoreboardSettings {
        if (config.scoreboard == null) config.scoreboard = ScoreboardSettings()
        return config.scoreboard!!
    }

    protected fun effectiveScoreboardCfg(): ScoreboardSettings = config.effectiveScoreboard()

    protected fun mutableChatTweaks(): ChatTweaksSettings {
        if (config.chatTweaks == null) config.chatTweaks = ChatTweaksSettings()
        return config.chatTweaks!!
    }

    protected fun effectiveChatTweaks(): ChatTweaksSettings = config.effectiveChatTweaks()

    protected fun mutableScreenshot(): ScreenshotSettings {
        if (config.screenshot == null) config.screenshot = ScreenshotSettings()
        return config.screenshot!!
    }

    protected fun effectiveScreenshotCfg(): ScreenshotSettings = config.effectiveScreenshot()

    protected fun mutablePing(): PingSettings {
        if (config.ping == null) config.ping = PingSettings()
        return config.ping!!
    }

    protected fun effectivePingCfg(): PingSettings = config.effectivePing()

    /** Generic scalar slot for simple modules. */
    protected fun scalar(key: String, default: Int): Int = config.scalarMap()[key] ?: default

    protected fun setScalar(key: String, value: Int) {
        config.scalarMap()[key] = value
    }

    protected fun flag(key: String, default: Boolean): Boolean = (config.scalarMap()[key] ?: if (default) 1 else 0) != 0

    protected fun setFlag(key: String, value: Boolean) {
        config.scalarMap()[key] = if (value) 1 else 0
    }

    protected fun effectiveWaypointsCfg(): WaypointModuleSettings = config.effectiveWaypoints()

    fun editorStyle(): SharedHudStyle = mutableStyle()

    fun editorArmorHud(): ArmorHudSettings = mutableArmorHud()

    fun editorNameTag(): NameTagSettings = mutableNameTag()

    fun editorCrosshair(): CrosshairSettings = mutableCrosshair()

    fun editorCombatVisuals(): CombatVisualsSettings = mutableCombatVisuals()

    fun editorInventoryHud(): InventoryHudSettings = mutableInventoryHud()

    fun editorZoom(): ZoomSettings = mutableZoom()

    fun editorFreelook(): FreelookSettings = mutableFreelook()

    fun editorWaypointSettings(): WaypointModuleSettings = mutableWaypoints()

    fun editorKeybind(): Int = config.keybind

    fun editorSetKeybind(code: Int) {
        config.keybind = code
    }

    fun editorScalePercent(): Int = config.scalePercent.coerceIn(50, 200)

    fun editorSetScalePercent(v: Int) {
        config.scalePercent = v.coerceIn(50, 200)
    }

    /** Precomputed: editorApproximateSize runs per module per frame via HudLayout. */
    private val approxSizeLabel = "$displayName 000"

    // The exact unscaled pill size from the last time this module drew, so the HUD editor
    // border wraps the real content (multi-line panels, wide values) instead of a guess.
    private var measuredW = 0
    private var measuredH = 0

    open fun editorApproximateSize(client: Minecraft): Pair<Int, Int> {
        val s = scaleFactor()
        if (measuredW > 0 && measuredH > 0) {
            return Pair((measuredW * s).toInt().coerceAtLeast(1), (measuredH * s).toInt().coerceAtLeast(1))
        }
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val w = (pad * 2 + 4 + font.width(approxSizeLabel) + 4).coerceAtLeast(64)
        val h = pad * 2 + font.lineHeight
        return Pair((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
    }

    // ---------------------------------------------------------------- shared drawing (HUD v2)

    protected fun panelLineHeight(client: Minecraft) = client.font.lineHeight + 2 + effectiveStyle().spacing

    protected fun panelTextColor(): Int {
        val custom = effectiveStyle().textColor
        return if (custom >= 0) (0xFF shl 24) or (custom and 0xFFFFFF) else 0xFFF2F4FA.toInt()
    }

    protected fun panelAccentColor(): Int {
        val custom = effectiveStyle().accentColor
        return if (custom >= 0) (0xFF shl 24) or (custom and 0xFFFFFF) else dev.wyz.clientcore.ui.RiverTheme.current.hudAccent
    }

    private val labelColor = 0xFF97A0B5.toInt()

    protected fun drawPillBackground(graphics: GuiGraphics, w: Int, h: Int) {
        // Every pill/panel draw routes through here, so record the real size for the editor.
        measuredW = w
        measuredH = h
        val st = effectiveStyle()
        if (st.showBackground) {
            val a = st.backgroundOpacity.coerceIn(0, 255)
            // Desaturated neutral base (the in-game ClientUi PANEL_SOFT surface), tight
            // RADIUS_CARD corners - the HUD now reads as one product with the Right Shift
            // menu instead of the old blue-tinted, rounder pill.
            ClientUi.fillRounded(graphics, x, y, w, h, ClientUi.RADIUS_CARD, (a shl 24) or (ClientUi.PANEL_SOFT and 0xFFFFFF))
        }
        if (st.showBorder) {
            val bo = st.borderOpacity.coerceIn(0, 255)
            // Neutral 1px border; the accent lives only in the left tick (Essential-style
            // restraint). A user who set a custom accent colour still gets it on the border.
            val borderRgb = if (st.accentColor >= 0) panelAccentColor() and 0xFFFFFF else ClientUi.BORDER and 0xFFFFFF
            ClientUi.drawRoundedBorder(graphics, x, y, w, h, ClientUi.RADIUS_CARD, (bo shl 24) or borderRgb)
        }
    }

    /** One-line "LABEL value" pill — the standard HUD readout. */
    protected fun drawStat(client: Minecraft, graphics: GuiGraphics, label: String, value: String) {
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val gap = if (label.isEmpty()) 0 else 4
        val labelW = if (label.isEmpty()) 0 else font.width(label)
        val w = pad + 4 + labelW + gap + font.width(value) + pad
        val h = pad * 2 + font.lineHeight

        drawPillBackground(graphics, w, h)
        val tx = x + pad + 4
        val ty = y + pad
        if (label.isNotEmpty()) {
            graphics.drawString(font, label, tx, ty, labelColor, st.textShadow)
        }
        graphics.drawString(font, value, tx + labelW + gap, ty, panelTextColor(), st.textShadow)
    }

    protected fun drawPanel(client: Minecraft, graphics: GuiGraphics, lines: List<String>, tickDelta: Float) {
        if (lines.isEmpty()) return
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val lineH = font.lineHeight + 2 + st.spacing
        val textW = lines.maxOf { font.width(it) }
        val w = pad + 4 + textW + pad
        val h = pad * 2 + lines.size * lineH - 2

        drawPillBackground(graphics, w, h)
        val textColor = panelTextColor()
        val tx = x + pad + 4
        var ty = y + pad
        lines.forEach { line ->
            graphics.drawString(font, line, tx, ty, textColor, st.textShadow)
            ty += lineH
        }
    }

    protected fun drawPanelLabeled(client: Minecraft, graphics: GuiGraphics, entries: List<Pair<String, String>>, tickDelta: Float) {
        if (entries.isEmpty()) return
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val lineH = font.lineHeight + 2 + st.spacing
        val gap = 6
        val labelW = entries.maxOf { font.width(it.first) }
        val valueW = entries.maxOf { font.width(it.second) }
        val w = pad + 4 + labelW + gap + valueW + pad
        val h = pad * 2 + entries.size * lineH - 2

        drawPillBackground(graphics, w, h)
        val bright = panelTextColor()
        entries.forEachIndexed { i, (label, value) ->
            val ly = y + pad + i * lineH
            val lx = x + pad + 4
            graphics.drawString(font, label, lx, ly, labelColor, st.textShadow)
            graphics.drawString(font, value, lx + labelW + gap, ly, bright, st.textShadow)
        }
    }

    /** Like [drawPanelLabeled] but each value carries its own color (0 = default text color). */
    protected fun drawPanelColored(client: Minecraft, graphics: GuiGraphics, entries: List<Triple<String, String, Int>>, tickDelta: Float) {
        if (entries.isEmpty()) return
        val font = client.font
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val lineH = font.lineHeight + 2 + st.spacing
        val gap = 6
        val labelW = entries.maxOf { font.width(it.first) }
        val valueW = entries.maxOf { font.width(it.second) }
        val w = pad + 4 + labelW + gap + valueW + pad
        val h = pad * 2 + entries.size * lineH - 2

        drawPillBackground(graphics, w, h)
        val defaultColor = panelTextColor()
        entries.forEachIndexed { i, (label, value, color) ->
            val ly = y + pad + i * lineH
            val lx = x + pad + 4
            graphics.drawString(font, label, lx, ly, labelColor, st.textShadow)
            graphics.drawString(font, value, lx + labelW + gap, ly, if (color != 0) color else defaultColor, st.textShadow)
        }
    }

    protected fun drawKey(
        graphics: GuiGraphics,
        font: Font,
        bx: Int, by: Int,
        bw: Int, bh: Int,
        label: String,
        pressed: Boolean
    ) {
        val accent = panelAccentColor()
        val bg = if (pressed) ClientUi.alpha(accent, 0.82f) else (0x9E shl 24) or (ClientUi.PANEL_SOFT and 0xFFFFFF)
        val fg = if (pressed) 0xFFFFFFFF.toInt() else 0xFFB9C0D2.toInt()

        ClientUi.fillRounded(graphics, bx, by, bw, bh, ClientUi.RADIUS_CARD, bg)
        if (!pressed) {
            ClientUi.drawRoundedBorder(graphics, bx, by, bw, bh, ClientUi.RADIUS_CARD, (0x66 shl 24) or (ClientUi.BORDER and 0xFFFFFF))
        }

        val tw = font.width(label)
        val th = font.lineHeight
        graphics.drawString(font, label, bx + (bw - tw) / 2, by + (bh - th) / 2, fg, false)
    }
}

/** HUD auto-stack anchors. Un-placed elements flow in these columns and never overlap. */
enum class HudStack {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT
}
