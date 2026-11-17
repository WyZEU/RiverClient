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
import dev.wyz.clientcore.config.SharedHudStyle
import dev.wyz.clientcore.config.ToggleSprintSettings
import dev.wyz.clientcore.config.WaypointModuleSettings
import dev.wyz.clientcore.config.ZoomSettings
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.hint
import dev.wyz.clientcore.module.settings.KeybindSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.safety.ServerSafety
import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

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
    /*
      Looked up again only when the whole config is replaced (a profile switch or a reload
      from disk), so those still take effect at once. It used to be looked up on every
      property read - enabled, x, y, each flag - which is several Gson tree-map lookups
      per module per frame and showed up in the profiler as River's busiest call.
    */
    private var configSource: Any? = null
    private var configCache: ModuleConfig? = null
    private val config: ModuleConfig
        get() {
            val modules = ClientCore.config.modules
            val cached = configCache
            if (cached != null && configSource === modules) return cached
            val resolved = modules.getOrPut(id) { ModuleConfig(defaultEnabled, defaultX, defaultY) }
            configSource = modules
            configCache = resolved
            return resolved
        }

    var enabled: Boolean
        get() = config.enabled
        set(value) { config.enabled = value }

    /**
     * Enabled, released, and not paused by cinematic mode. Rendering and ticking key off this.
     * Also off when the standalone mod is too many updates behind (ModUpdateCheck).
     */
    val active: Boolean
        get() = enabled && !comingSoon && ServerSafety.allows(this) && !dev.wyz.clientcore.net.ModUpdateCheck.turnedOff

    /** Listed in the menu but not usable yet. */
    open val comingSoon: Boolean = false

    /** Hidden from the module grid/search (managed elsewhere, e.g. the Wardrobe). */
    open val showInMenu: Boolean = true

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
            withTextStyle(body)
            return
        }
        val pose = graphics.pose()
//? if >=1.21.6 {
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(s, s)
        pose.translate(-x.toFloat(), -y.toFloat())
//?} else {
/*        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.scale(s, s, 1f)
        pose.translate(-x.toFloat(), -y.toFloat(), 0f)
*///?}
        withTextStyle(body)
//? if >=1.21.6 {
        pose.popMatrix()
//?} else {
/*        pose.popPose()
*///?}
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

    /** The shared style controls this module's drawing really reads. Background, glass,
     *  colour, opacity, corners, bold and small caps always apply; the rest only where listed,
     *  so no module shows a setting that does nothing. */
    enum class StyleOpt { PADDING, BORDER, TEXT, LABEL, ACCENT, SHADOW }

    protected open val styleOpts: Set<StyleOpt>
        get() = setOf(StyleOpt.PADDING, StyleOpt.BORDER, StyleOpt.TEXT, StyleOpt.LABEL, StyleOpt.SHADOW)

    /** What the label colour is for in this module, and what the spacing slider spaces. */
    protected open val labelColorName: String = "Label color"
    protected open val labelColorHint: String? = "The name next to a value, like FPS in FPS 240."
    protected open val spacingName: String = "Line spacing"

    private fun addPanelStyleSettings(list: MutableList<Setting>) {
        val st = { mutableStyle() }

        list.add(SectionSetting("Style"))
        list.add(ChoiceSetting("Preset", STYLE_PRESETS + "Custom", { presetName(st()) }, { applyPreset(st(), it) })
            .hint("A ready-made look. Changing anything below makes it Custom."))
        list.add(IntSetting("Size", 50, 200, { editorScalePercent() }, { editorSetScalePercent(it) }, "%"))

        list.add(SectionSetting("Background"))
        list.add(BoolSetting("Background", { st().showBackground }, { st().showBackground = it }))
        list.add(BoolSetting("Glass", { st().glass }, { st().glass = it })
            .hint("Blurs the game behind it."))
        list.add(ColorSetting("Color", false, { colorOr(st().backgroundColor, ClientUi.PANEL_SOFT) }, { st().backgroundColor = it and 0xFFFFFF }))
        list.add(IntSetting("Opacity", 0, 255, { st().backgroundOpacity }, { st().backgroundOpacity = it }))
        list.add(IntSetting("Rounded corners", 0, 8, { st().cornerRadius }, { st().cornerRadius = it }, "px"))
        val opts = styleOpts
        if (StyleOpt.PADDING in opts) {
            list.add(IntSetting("Padding", 2, 20, { st().padding }, { st().padding = it }, "px"))
        }

        if (StyleOpt.BORDER in opts) {
            list.add(SectionSetting("Border"))
            list.add(BoolSetting("Border", { st().showBorder }, { st().showBorder = it }))
            list.add(IntSetting("Border opacity", 0, 255, { st().borderOpacity }, { st().borderOpacity = it }))
            // Without an accent of its own, the accent colour is only the border's colour.
            if (StyleOpt.ACCENT !in opts) {
                list.add(ColorSetting("Border color", false, { colorOr(st().accentColor, ClientUi.BORDER) }, { st().accentColor = it and 0xFFFFFF }))
            }
        }

        list.add(SectionSetting("Text"))
        if (StyleOpt.TEXT in opts) {
            list.add(ColorSetting("Text color", false, { colorOr(st().textColor, 0xF2F4FA) }, { st().textColor = it and 0xFFFFFF }))
        }
        if (StyleOpt.LABEL in opts) {
            val label = ColorSetting(labelColorName, false, { colorOr(st().labelColor, 0x97A0B5) }, { st().labelColor = it and 0xFFFFFF })
            labelColorHint?.let { label.hint(it) }
            list.add(label)
        }
        if (StyleOpt.ACCENT in opts) {
            list.add(ColorSetting("Accent color", false, { colorOr(st().accentColor, dev.wyz.clientcore.ui.RiverTheme.current.hudAccent) }, { st().accentColor = it and 0xFFFFFF }))
        }
//? if >=1.21.6 {
        list.add(BoolSetting("Bold", { st().boldText }, { st().boldText = it }))
        list.add(BoolSetting("Small caps", { st().smallCaps }, { st().smallCaps = it }))
//?}
        if (StyleOpt.SHADOW in opts) {
            list.add(BoolSetting("Text shadow", { st().textShadow }, { st().textShadow = it }))
        }
        if (multiLine) {
            list.add(IntSetting(spacingName, 0, 16, { st().spacing }, { st().spacing = it }, "px"))
        }
        if (rowsCanBeHorizontal) {
            list.add(BoolSetting("Horizontal layout", { st().layoutHorizontal }, { st().layoutHorizontal = it }))
        }

        list.add(ActionSetting("Use this style on all modules", "Apply") { applyStyleToAll() })
        list.add(ActionSetting("Reset style", "Reset", destructive = true) {
            config.style = SharedHudStyle()
            config.scalePercent = 100
        })
    }

    /** Dev screenshots only (see dev.DevShot): this module's style, to try a look. */
    internal fun styleForDevShot(): SharedHudStyle = mutableStyle()

    private fun colorOr(custom: Int, fallback: Int): Int = if (custom >= 0) custom and 0xFFFFFF else fallback and 0xFFFFFF

    /** Copies this module's look (not its position or content settings) to every styled module. */
    private fun applyStyleToAll() {
        val source = effectiveStyle()
        ModuleRegistry.all.forEach { other ->
            if (other !== this && other.stylable) {
                other.config.style = source.copy()
                other.config.scalePercent = config.scalePercent
            }
        }
        dev.wyz.clientcore.RiverRuntime.saveConfig()
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
    /** When this module last drew a panel with a background (System.nanoTime), 0 if it has none. */
    private var panelAt = 0L

    /**
     * The screen rectangle this module draws into, for the preview box in its settings
     * panel: [x, y, width, height]. Null means there is nothing to preview, which is the
     * right answer for the modules that only act on a keybind.
     *
     * Draggable is the test because a module that owns a spot on the HUD is exactly one
     * that draws something there. Anything that draws elsewhere - the crosshair, at the
     * centre of the screen - says so by overriding this.
     */
    open fun previewBounds(client: Minecraft): IntArray? {
        if (!acceptsDraggablePosition()) return null
        val (w, h) = editorApproximateSize(client)
        return intArrayOf(x, y, w, h)
    }

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
        return if (custom >= 0) dev.wyz.clientcore.ui.RiverColor.resolve((0xFF shl 24) or (custom and 0xFFFFFF)) else 0xFFF2F4FA.toInt()
    }

    protected fun panelAccentColor(): Int {
        val custom = effectiveStyle().accentColor
        return if (custom >= 0) dev.wyz.clientcore.ui.RiverColor.resolve((0xFF shl 24) or (custom and 0xFFFFFF)) else dev.wyz.clientcore.ui.RiverTheme.current.hudAccent
    }

    /** Row-label tint, the module's label color if it has one. Merged HUD modules lay out their own rows. */
    protected val labelColor: Int
        get() = effectiveStyle().labelColor.let { if (it >= 0) dev.wyz.clientcore.ui.RiverColor.resolve((0xFF shl 24) or (it and 0xFFFFFF)) else 0xFF97A0B5.toInt() }

    /** Label and value readouts can run along one line instead of down a column. */
    protected open val rowsCanBeHorizontal: Boolean = false

    /** Draws label and value pairs side by side: FPS 241  CPS 7  Ping 24ms. */
    protected fun drawRowsInLine(font: net.minecraft.client.gui.Font, graphics: GuiGraphics, rows: List<Pair<String, String>>) {
        val st = effectiveStyle()
        val pad = st.padding.coerceAtLeast(3)
        val inner = 4
        val between = 10
        val w = rows.sumOf { (label, value) -> (if (label.isEmpty()) 0 else font.width(label) + inner) + font.width(value) } + between * (rows.size - 1)
        drawPillBackground(graphics, pad * 2 + 4 + w, pad * 2 + font.lineHeight)
        val value = panelTextColor()
        var cx = x + pad + 2
        val ly = y + pad
        rows.forEach { (label, text) ->
            if (label.isNotEmpty()) {
                graphics.drawString(font, label, cx, ly, labelColor, st.textShadow)
                cx += font.width(label) + inner
            }
            graphics.drawString(font, text, cx, ly, value, st.textShadow)
            cx += font.width(text) + between
        }
    }

    protected fun drawPillBackground(graphics: GuiGraphics, w: Int, h: Int) {
        // Every pill/panel draw routes through here, so record the real size for the editor.
        measuredW = w
        measuredH = h
        val st = effectiveStyle()
        val solidPanel = st.showBackground && st.backgroundOpacity > 0
        panelAt = if (solidPanel) System.nanoTime() else 0L
        val joined = if (solidPanel && st.cornerRadius > 0 && scaleFactor() == 1f) joinedCorners(w, h) else 0
        if (joined and 15 == 0) {
            drawPanelBackground(graphics, x, y, w, h, sheen = joined and 16 == 0)
            return
        }
        // A corner that meets another module's panel is drawn square, so two modules pushed
        // together read as one piece. Each quarter is drawn once, clipped, round or square.
        val mx = x + w / 2
        val my = y + h / 2
        val sheen = joined and 16 == 0
        panelQuarter(graphics, x, y, mx, my, w, h, joined and 1 != 0, sheen)
        panelQuarter(graphics, mx, y, x + w, my, w, h, joined and 2 != 0, sheen)
        panelQuarter(graphics, x, my, mx, y + h, w, h, joined and 4 != 0, sheen)
        panelQuarter(graphics, mx, my, x + w, y + h, w, h, joined and 8 != 0, sheen)
    }

    private fun panelQuarter(graphics: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, w: Int, h: Int, square: Boolean, sheen: Boolean) {
        ClientUi.withScissor(graphics, x0, y0, x1, y1) { drawPanelBackground(graphics, x, y, w, h, square = square, sheen = sheen) }
    }

    /**
     * Which corners of this module's panel touch another module's panel:
     * 1 top-left, 2 top-right, 4 bottom-left, 8 bottom-right, and 16 when one sits on its top edge.
     */
    private fun joinedCorners(w: Int, h: Int): Int {
        val now = System.nanoTime()
        val ax0 = x
        val ay0 = y
        val ax1 = ax0 + w
        val ay1 = ay0 + h
        var bits = 0
        val all = ModuleRegistry.all
        for (i in all.indices) {
            val o = all[i]
            // Only a module that drew its own panel in the last moment, at normal size.
            if (o === this || o.panelAt == 0L || now - o.panelAt > 250_000_000L || o.scaleFactor() != 1f) continue
            val bx0 = o.x
            val by0 = o.y
            val bx1 = bx0 + o.measuredW
            val by1 = by0 + o.measuredH
            val acrossX = minOf(ax1, bx1) - maxOf(ax0, bx0) > 0
            val acrossY = minOf(ay1, by1) - maxOf(ay0, by0) > 0
            if (acrossX && kotlin.math.abs(by1 - ay0) <= 1) {
                bits = bits or 16
                if (bx0 <= ax0 + 1) bits = bits or 1
                if (bx1 >= ax1 - 1) bits = bits or 2
            }
            if (acrossX && kotlin.math.abs(by0 - ay1) <= 1) {
                if (bx0 <= ax0 + 1) bits = bits or 4
                if (bx1 >= ax1 - 1) bits = bits or 8
            }
            if (acrossY && kotlin.math.abs(bx1 - ax0) <= 1) {
                if (by0 <= ay0 + 1) bits = bits or 1
                if (by1 >= ay1 - 1) bits = bits or 4
            }
            if (acrossY && kotlin.math.abs(bx0 - ax1) <= 1) {
                if (by0 <= ay0 + 1) bits = bits or 2
                if (by1 >= ay1 - 1) bits = bits or 8
            }
        }
        return bits
    }

    /**
     * The module's background and border, from its style: glass or solid, its color and
     * opacity, rounded corners. Every HUD module draws its panel through this, so a style
     * setting changes them all the same way. [border] is off for modules that draw their own.
     */
    protected fun drawPanelBackground(graphics: GuiGraphics, bx: Int, by: Int, w: Int, h: Int, border: Boolean = true, square: Boolean = false, sheen: Boolean = true) {
        val st = effectiveStyle()
        val radius = if (square) 0 else st.cornerRadius.coerceIn(0, 8)
        if (st.showBackground) {
            val a = st.backgroundOpacity.coerceIn(0, 255)
            if (a > 0) {
                val base = if (st.backgroundColor >= 0) dev.wyz.clientcore.ui.RiverColor.resolve(st.backgroundColor) and 0xFFFFFF else ClientUi.PANEL_SOFT and 0xFFFFFF
                val solid = (a shl 24) or base
                if (st.glass) {
                    // The opacity is then how strong the tint over the blurred world is.
                    dev.wyz.clientcore.ui.Glass.surface(graphics, bx, by, w, h, radius, solid, tint = base, tintAlpha = (a / 255f) * 0.7f, sheen = sheen)
                } else {
                    ClientUi.fillRounded(graphics, bx, by, w, h, radius, solid)
                }
            }
        }
        if (border && st.showBorder) {
            val bo = st.borderOpacity.coerceIn(0, 255)
            // Neutral 1px border; a custom accent colour tints it.
            ClientUi.drawRoundedBorder(graphics, bx, by, w, h, radius, (bo shl 24) or panelBorderRgb())
        }
    }

    /** The border's colour: neutral, or the module's accent / border colour when one is set. */
    protected fun panelBorderRgb(): Int =
        if (effectiveStyle().accentColor >= 0) panelAccentColor() and 0xFFFFFF else ClientUi.BORDER and 0xFFFFFF

    /** Draws [body] with this module's bold / small-caps text style on. */
    fun <T> withTextStyle(body: () -> T): T {
        val st = effectiveStyle()
        return dev.wyz.clientcore.ui.RiverText.withHudStyle(st.boldText, st.smallCaps, body)
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
        pressed: Boolean,
        subLabel: String? = null
    ) {
        val accent = panelAccentColor()
        val st = effectiveStyle()
        val radius = st.cornerRadius.coerceIn(0, 8)
        val fg = if (pressed) 0xFFFFFFFF.toInt() else panelTextColor()

        if (pressed) {
            ClientUi.fillRounded(graphics, bx, by, bw, bh, radius, ClientUi.alpha(accent, 0.82f))
        } else {
            // Each key is a small panel in the module's own style, glass included.
            drawPanelBackground(graphics, bx, by, bw, bh, border = false)
            if (st.showBorder) {
                ClientUi.drawRoundedBorder(graphics, bx, by, bw, bh, radius, (st.borderOpacity.coerceIn(0, 255) shl 24) or (ClientUi.BORDER and 0xFFFFFF))
            }
        }

        drawKeyLabel(graphics, font, bx, by, bw, bh, label, subLabel, fg)
    }

    /** One key for [drawKeys]: where it is, what it says, and whether it is held. */
    protected class KeySpec(val x: Int, val y: Int, val w: Int, val h: Int, val label: String, val pressed: Boolean, val subLabel: String? = null)

    /**
     * Several keys drawn layer by layer - every key's blurred backdrop, then every fill, then
     * the sheens, borders and labels - instead of key by key. The GUI renderer batches what is
     * drawn back to back with the same pipeline, so a block of keys costs a handful of draw
     * calls rather than five or six per key. Looks the same as [drawKey] on each.
     */
    protected fun drawKeys(graphics: GuiGraphics, font: Font, keys: List<KeySpec>) {
        val accent = panelAccentColor()
        val st = effectiveStyle()
        val radius = st.cornerRadius.coerceIn(0, 8)
        val opacity = st.backgroundOpacity.coerceIn(0, 255)
        val background = st.showBackground && opacity > 0
        val base = if (st.backgroundColor >= 0) dev.wyz.clientcore.ui.RiverColor.resolve(st.backgroundColor) and 0xFFFFFF else ClientUi.PANEL_SOFT and 0xFFFFFF
        val glassy = BooleanArray(keys.size)
        if (background && st.glass) {
            for (i in keys.indices) {
                val k = keys[i]
                if (!k.pressed) glassy[i] = dev.wyz.clientcore.ui.Glass.backdrop(graphics, k.x, k.y, k.w, k.h, radius)
            }
        }
        for (i in keys.indices) {
            val k = keys[i]
            when {
                k.pressed -> ClientUi.fillRounded(graphics, k.x, k.y, k.w, k.h, radius, ClientUi.alpha(accent, 0.82f))
                glassy[i] -> ClientUi.fillRounded(graphics, k.x, k.y, k.w, k.h, radius, ClientUi.alpha(base or 0xFF000000.toInt(), (opacity / 255f) * 0.7f))
                background -> ClientUi.fillRounded(graphics, k.x, k.y, k.w, k.h, radius, (opacity shl 24) or base)
            }
        }
        val side = maxOf(1, radius)
        for (i in keys.indices) {
            val k = keys[i]
            if (glassy[i] && k.w > side * 2) {
                val depth = (k.h / 3).coerceIn(2, 24)
                graphics.fillGradient(k.x + side, k.y + 1, k.x + k.w - side, k.y + 1 + depth, 0x14FFFFFF, 0x00FFFFFF)
            }
        }
        if (st.showBorder) {
            val border = (st.borderOpacity.coerceIn(0, 255) shl 24) or (ClientUi.BORDER and 0xFFFFFF)
            for (k in keys) if (!k.pressed) ClientUi.drawRoundedBorder(graphics, k.x, k.y, k.w, k.h, radius, border)
        }
        val text = panelTextColor()
        for (k in keys) drawKeyLabel(graphics, font, k.x, k.y, k.w, k.h, k.label, k.subLabel, if (k.pressed) 0xFFFFFFFF.toInt() else text)
    }

    private fun drawKeyLabel(graphics: GuiGraphics, font: Font, bx: Int, by: Int, bw: Int, bh: Int, label: String, subLabel: String?, fg: Int) {
        val tw = font.width(label)
        val th = font.lineHeight
        if (subLabel == null) {
            graphics.drawString(font, label, bx + (bw - tw) / 2, by + (bh - th) / 2, fg, false)
            return
        }
        // Two lines: the key, then a smaller reading under it (CPS on the mouse buttons).
        graphics.drawString(font, label, bx + (bw - tw) / 2, by + 2, fg, false)
        val pose = graphics.pose()
        val sub = 0.75f
        val sx = bx + (bw - font.width(subLabel) * sub) / 2f
        val sy = by + 2 + th + 1f
//? if >=1.21.6 {
        pose.pushMatrix()
        pose.translate(sx, sy)
        pose.scale(sub, sub)
//?} else {
/*        pose.pushPose()
        pose.translate(sx, sy, 0f)
        pose.scale(sub, sub, 1f)
*///?}
        graphics.drawString(font, subLabel, 0, 0, fg, false)
//? if >=1.21.6 {
        pose.popMatrix()
//?} else {
/*        pose.popPose()
*///?}
    }
}

/**
 * Ready-made looks for a HUD panel. Each sets the whole look - background, glass, corners,
 * border and padding - so picking one gives exactly what its name says. Any change after
 * that makes the style Custom.
 */
private class StylePreset(
    val name: String, val background: Boolean, val glass: Boolean, val opacity: Int,
    val corners: Int, val border: Boolean, val borderOpacity: Int, val padding: Int
)

private val PRESETS = listOf(
    StylePreset("Frosted glass", true, true, 150, 6, false, 90, 5),
    StylePreset("Frosted with border", true, true, 140, 6, true, 110, 5),
    StylePreset("Dark", true, false, 210, 0, false, 90, 5),
    StylePreset("Dark rounded", true, false, 200, 8, true, 90, 6),
    StylePreset("Faint", true, true, 70, 4, false, 90, 4),
    StylePreset("Outline only", false, false, 0, 6, true, 160, 5),
    StylePreset("Text only", false, false, 0, 0, false, 0, 4)
)

private val STYLE_PRESETS = PRESETS.map { it.name }

private fun presetName(st: SharedHudStyle): String = PRESETS.firstOrNull { p ->
    when {
        !p.background && !p.border -> !st.showBackground && !st.showBorder
        !p.background -> !st.showBackground && st.showBorder && st.cornerRadius == p.corners && st.borderOpacity == p.borderOpacity
        else -> st.showBackground && st.glass == p.glass && st.backgroundOpacity == p.opacity &&
            st.cornerRadius == p.corners && st.showBorder == p.border && st.padding == p.padding &&
            (!p.border || st.borderOpacity == p.borderOpacity)
    }
}?.name ?: "Custom"

private fun applyPreset(st: SharedHudStyle, name: String) {
    val p = PRESETS.firstOrNull { it.name == name } ?: return
    st.showBackground = p.background
    st.glass = p.glass
    if (p.background) st.backgroundOpacity = p.opacity
    st.cornerRadius = p.corners
    st.showBorder = p.border
    if (p.border) st.borderOpacity = p.borderOpacity
    st.padding = p.padding
    // Without a background, the text needs its shadow to stay readable.
    if (!p.background) st.textShadow = true
}

/** HUD auto-stack anchors. Un-placed elements flow in these columns and never overlap. */
enum class HudStack {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT
}
