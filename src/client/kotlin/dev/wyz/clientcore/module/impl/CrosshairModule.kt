package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.config.CrosshairSettings
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.world.entity.LivingEntity
import dev.wyz.clientcore.compat.McId
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType
*///?} else {
/*import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
*///?}
import kotlin.math.roundToInt

class CrosshairModule : Module("crosshair", "Custom Crosshair", "Pick, shape or draw your crosshair", ModuleCategory.VISUAL, "crosshair", 0, 0, false) {

    companion object {
        const val PRESET_CUSTOM = "custom"
        const val PRESET_DRAWN = "drawn"

        /** The pixel editor's canvas: odd, so there is a true centre pixel. */
        const val GRID = 15
        const val GRID_CENTER = GRID / 2
        const val PRESET_CLASSIC = "classic"
        const val PRESET_DOT = "dot"
        const val PRESET_TACTICAL = "tactical"
        const val PRESET_CROSS_DOT = "cross_dot"
        const val PRESET_T = "t"
        const val PRESET_BRACKETS = "brackets"
        const val PRESET_WIDE = "wide"
        const val PRESET_SNIPER = "sniper"
        const val PRESET_MICRO = "micro"

        private val COLOUR_MODES = listOf("Custom", "Vanilla")

        /** A white texture drawn through the crosshair blend, which is how vanilla inverts. */
        private val WHITE: McId = McId.fromNamespaceAndPath("clientcore", "textures/gui/white.png")

        private val PRESETS = listOf(
            PRESET_CUSTOM, PRESET_DRAWN, PRESET_CLASSIC, PRESET_CROSS_DOT, PRESET_DOT, PRESET_T,
            PRESET_TACTICAL, PRESET_BRACKETS, PRESET_WIDE, PRESET_SNIPER, PRESET_MICRO
        )

        /** What the dropdown shows; the stored values stay the lowercase keys. */
        private val PRESET_LABELS = listOf(
            "Your shape", "Drawn", "Classic", "Cross + dot", "Dot", "T",
            "Tactical", "Brackets", "Wide", "Sniper", "Micro"
        )

        private fun labelOf(key: String): String =
            PRESET_LABELS.getOrNull(PRESETS.indexOf(key.lowercase())) ?: PRESET_LABELS[0]

        private fun keyOf(label: String): String =
            PRESETS.getOrNull(PRESET_LABELS.indexOf(label)) ?: PRESET_CUSTOM
    }

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL

    override fun acceptsDraggablePosition(): Boolean = false

    /**
     * Unlike every HUD element this draws at the centre of the screen, so the preview box
     * is a square around that centre rather than a rectangle at (x, y).
     */
    override fun previewBounds(client: Minecraft): IntArray? {
        val s = effectiveCrosshair()
        val shapeReach = (s.gap + s.length + s.outlineThickness + 2) * 2
        val drawnReach = if (s.normalPreset.equals(PRESET_DRAWN, true) || s.targetPreset.equals(PRESET_DRAWN, true)) GRID + (s.outlineThickness + 1) * 2 else 0
        val size = (maxOf(shapeReach, drawnReach) * scaleFactor().roundToInt().coerceAtLeast(1)).coerceAtLeast(10)
        return intArrayOf(
            client.window.guiScaledWidth / 2 - size / 2,
            client.window.guiScaledHeight / 2 - size / 2,
            size,
            size
        )
    }

    override fun showPositionControlsInEditor(): Boolean = false

    /*
      Laid out in the order people decide things: what it looks like, what colour, and
      what changes when you aim at someone. The list is rebuilt every frame, so rows that
      do nothing for the current choice - arm sliders on a drawn crosshair, a colour
      picker in vanilla colour mode - are simply not there.
    */
    override fun addModuleSettings(list: MutableList<Setting>) {
        val c = { mutableCrosshair() }
        val s = c()
        // Any shape edit makes it your own shape, so the dropdown stops naming a preset it
        // no longer matches.
        val edit = { change: (CrosshairSettings) -> Unit -> change(c()); c().normalPreset = PRESET_CUSTOM }
        val drawn = s.normalPreset.equals(PRESET_DRAWN, true)

        list.add(SectionSetting("Crosshair"))
        list.add(ChoiceSetting("Style", PRESET_LABELS, { labelOf(c().normalPreset) }, { v -> applyPreset(c(), keyOf(v)) }))
        list.add(ActionSetting("Draw it pixel by pixel", if (drawn) "Edit drawing" else "Open editor") { openEditor(false) })
        list.add(BoolSetting("Hide the vanilla crosshair", { c().hideVanillaCrosshair }, { c().hideVanillaCrosshair = it }))

        if (!drawn) {
            list.add(SectionSetting("Shape"))
            list.add(IntSetting("Gap", 0, 12, { c().gap }, { v -> edit { it.gap = v } }))
            list.add(IntSetting("Arm length", 1, 14, { c().length }, { v -> edit { it.length = v } }))
            list.add(IntSetting("Thickness", 1, 5, { c().thickness }, { v -> edit { it.thickness = v } }))
            list.add(BoolSetting("Top arm", { c().showTop && !c().tShape }, { v -> edit { it.showTop = v; it.tShape = false } }))
            list.add(BoolSetting("Bottom arm", { c().showBottom }, { v -> edit { it.showBottom = v } }))
            list.add(BoolSetting("Left arm", { c().showLeft }, { v -> edit { it.showLeft = v } }))
            list.add(BoolSetting("Right arm", { c().showRight }, { v -> edit { it.showRight = v } }))
            list.add(BoolSetting("Centre dot", { c().showCenterDot }, { v -> edit { it.showCenterDot = v } }))
            if (s.showCenterDot) list.add(IntSetting("Dot size", 1, 6, { c().dotSize }, { v -> edit { it.dotSize = v } }))
            list.add(BoolSetting("Spread while attack recharges", { c().dynamicAttackGap }, { v -> edit { it.dynamicAttackGap = v } }))
        }

        list.add(SectionSetting("Colour"))
        // Vanilla inverts whatever is behind it, so it shows on any background.
        list.add(ChoiceSetting("Colour style", COLOUR_MODES,
            { if (c().invertColors) COLOUR_MODES[1] else COLOUR_MODES[0] },
            { v ->
                val invert = v == COLOUR_MODES[1]
                c().invertColors = invert
                // Vanilla's crosshair has no outline; an outline would fight the inversion.
                if (invert) c().useOutline = false
            }))
        if (!s.invertColors) {
            list.add(ColorSetting("Crosshair colour", true,
                { argb(c().alpha, c().red, c().green, c().blue) },
                { v -> c().alpha = (v ushr 24) and 0xFF; c().red = (v ushr 16) and 0xFF; c().green = (v ushr 8) and 0xFF; c().blue = v and 0xFF }
            ))
        }
        list.add(BoolSetting("Outline", { c().useOutline }, { c().useOutline = it }))
        if (s.useOutline) {
            list.add(IntSetting("Outline width", 1, 3, { c().outlineThickness.coerceAtLeast(1) }, { c().outlineThickness = it }))
            list.add(ColorSetting("Outline colour", true,
                { argb(c().outlineAlpha, c().outlineRed, c().outlineGreen, c().outlineBlue) },
                { v -> c().outlineAlpha = (v ushr 24) and 0xFF; c().outlineRed = (v ushr 16) and 0xFF; c().outlineGreen = (v ushr 8) and 0xFF; c().outlineBlue = v and 0xFF }
            ))
        }

        list.add(SectionSetting("Aiming at a player or mob"))
        list.add(BoolSetting("Change colour", { c().targetColorEnabled }, { c().targetColorEnabled = it }))
        if (s.targetColorEnabled) {
            list.add(ColorSetting("Colour on target", true,
                { argb(c().targetAlpha, c().targetRed, c().targetGreen, c().targetBlue) },
                { v -> c().targetAlpha = (v ushr 24) and 0xFF; c().targetRed = (v ushr 16) and 0xFF; c().targetGreen = (v ushr 8) and 0xFF; c().targetBlue = v and 0xFF }
            ))
        }
        list.add(BoolSetting("Change shape", { c().swapOnTarget }, { c().swapOnTarget = it }))
        if (s.swapOnTarget) {
            list.add(ChoiceSetting("Shape on target", PRESET_LABELS, { labelOf(c().targetPreset) }, { v -> setTargetPreset(c(), keyOf(v)) }))
            if (s.targetPreset.equals(PRESET_DRAWN, true)) {
                list.add(ActionSetting("Drawing on target", "Edit drawing") { openEditor(true) })
            }
        }
    }

    private fun openEditor(target: Boolean) {
        val mc = Minecraft.getInstance()
        mc.setScreen(dev.wyz.clientcore.ui.screen.RiverCrosshairEditorScreen(mc.screen, target))
    }

    private fun setTargetPreset(s: CrosshairSettings, key: String) {
        s.targetPreset = key
        // A target drawing starts as a copy of whatever is on screen normally.
        if (key == PRESET_DRAWN && s.targetPixels.length != GRID * GRID) {
            s.targetPixels = encode(if (s.normalPreset.equals(PRESET_DRAWN, true)) pixelsOf(s, false) else rasterise(s))
        }
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
//? if >=26.2 {
/*        if (client.gui.hud.isHidden()) return
*///?} else {
        if (client.options.hideGui) return
//?}
        val settings = effectiveCrosshair()
        // Not gated on swapOnTarget: that switch is about the shape, while the colour
        // has its own. Either half can be used without the other.
        val onTarget = client.crosshairPickEntity is LivingEntity
        val active = resolveProfile(settings, onTarget)
        val attackGap = if (active.dynamicAttackGap) {
            val strength = client.player?.getAttackStrengthScale(0f) ?: 1f
            ((1f - strength.coerceIn(0f, 1f)) * 6f).roundToInt()
        } else 0
        val swapped = onTarget && settings.swapOnTarget
        val preset = if (swapped) settings.targetPreset else settings.normalPreset
        val parts = if (preset.equals(PRESET_DRAWN, true)) drawnRects(settings, swapped) else shapeRects(active, attackGap)

        // One unit is one GUI pixel at 100%, and always a whole number of them, so a
        // one-pixel line never lands half on a pixel.
        val unit = scaleFactor().roundToInt().coerceAtLeast(1)
        // The centre pixel sits where vanilla's does: (w - 15) / 2 + 7 for its 15px texture.
        val originX = (client.window.guiScaledWidth - unit) / 2
        val originY = (client.window.guiScaledHeight - unit) / 2
        // A target colour, when set, is a real colour even in vanilla mode: that is its point.
        val invert = settings.invertColors && !(onTarget && settings.targetColorEnabled)
        drawRects(graphics, parts, originX, originY, unit, active, invert)
    }

    /** Draws unit-space [parts] with the crosshair's colours, every outline before any fill. */
    fun drawRects(graphics: GuiGraphics, parts: List<IntArray>, originX: Int, originY: Int, unit: Int, style: CrosshairSettings, invert: Boolean = false) {
        val color = argb(style.alpha, style.red, style.green, style.blue)
        // Drawn per part, the outline of each arm was laid over the arms and dot already
        // drawn, which is what chewed pieces out of them whenever the gap was small.
        val o = style.outlineThickness.coerceAtLeast(0) * unit
        if (style.useOutline && o > 0) {
            val outlineColor = argb(style.outlineAlpha, style.outlineRed, style.outlineGreen, style.outlineBlue)
            for (p in parts) {
                val x = originX + p[0] * unit
                val y = originY + p[1] * unit
                graphics.fill(x - o, y - o, x + p[2] * unit + o, y + p[3] * unit + o, outlineColor)
            }
        }
        for (p in parts) {
            val x = originX + p[0] * unit
            val y = originY + p[1] * unit
            if (invert) fillInverted(graphics, x, y, p[2] * unit, p[3] * unit)
            else graphics.fill(x, y, x + p[2] * unit, y + p[3] * unit, color)
        }
    }

    /** A rectangle that inverts whatever is behind it, exactly as vanilla's crosshair does. */
    private fun fillInverted(graphics: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
//? if >=1.21.6 {
        graphics.blit(RenderPipelines.CROSSHAIR, WHITE, x, y, 0f, 0f, w, h, 1, 1, 1, 1, -1)
//?} elif >=1.21.2 {
/*        graphics.blit(RenderType::crosshair, WHITE, x, y, 0f, 0f, w, h, 1, 1, 1, 1)
*///?} else {
/*        RenderSystem.enableBlend()
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
            GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO
        )
        graphics.blit(WHITE, x, y, w, h, 0f, 0f, 1, 1, 1, 1)
        RenderSystem.defaultBlendFunc()
        RenderSystem.disableBlend()
*///?}
    }

    /*
      Shapes are laid out around a centre pixel at (0, 0), in units of one pixel, as
      x, y, w, h. The gap is measured from that pixel's edge on all four sides; measured
      from the line between two pixels, as it used to be, the top and left arms sat one
      pixel closer than the other two.
    */
    fun shapeRects(s: CrosshairSettings, attackGap: Int = 0): List<IntArray> {
        val gap = s.gap.coerceAtLeast(0) + attackGap
        val length = s.length.coerceAtLeast(1)
        val thickness = s.thickness.coerceAtLeast(1)
        val dot = s.dotSize.coerceAtLeast(1)
        val across = -(thickness - 1) / 2
        val parts = ArrayList<IntArray>(5)
        if (s.showCenterDot) {
            val start = -(dot - 1) / 2
            parts.add(intArrayOf(start, start, dot, dot))
        }
        if (s.showTop && !s.tShape) parts.add(intArrayOf(across, -gap - length, thickness, length))
        if (s.showBottom) parts.add(intArrayOf(across, 1 + gap, thickness, length))
        if (s.showLeft) parts.add(intArrayOf(-gap - length, across, length, thickness))
        if (s.showRight) parts.add(intArrayOf(1 + gap, across, length, thickness))
        return parts
    }

    /** The drawn crosshair's pixels, one unit-space rect each. */
    private fun drawnRects(s: CrosshairSettings, target: Boolean): List<IntArray> {
        val grid = pixelsOf(s, target)
        val parts = ArrayList<IntArray>()
        for (i in grid.indices) {
            if (grid[i]) parts.add(intArrayOf(i % GRID - GRID_CENTER, i / GRID - GRID_CENTER, 1, 1))
        }
        return parts
    }

    /** A drawn canvas - the normal one or the one for aiming at a target; empty until drawn. */
    fun pixelsOf(s: CrosshairSettings, target: Boolean): BooleanArray {
        val grid = BooleanArray(GRID * GRID)
        val stored = if (target) s.targetPixels else s.pixels
        if (stored.length == GRID * GRID) for (i in grid.indices) grid[i] = stored[i] == '1'
        return grid
    }

    /** The current shape, rasterised onto the canvas - where a new drawing starts from. */
    fun rasterise(s: CrosshairSettings): BooleanArray {
        val grid = BooleanArray(GRID * GRID)
        for (p in shapeRects(s)) {
            for (y in p[1] until p[1] + p[3]) for (x in p[0] until p[0] + p[2]) {
                val gx = x + GRID_CENTER
                val gy = y + GRID_CENTER
                if (gx in 0 until GRID && gy in 0 until GRID) grid[gy * GRID + gx] = true
            }
        }
        return grid
    }

    fun editorSettings(): CrosshairSettings = mutableCrosshair()

    private fun encode(grid: BooleanArray): String = String(CharArray(grid.size) { if (grid[it]) '1' else '0' })

    /** Saves a drawing and switches that crosshair (normal or on-target) to it. */
    fun savePixels(grid: BooleanArray, target: Boolean) {
        val s = mutableCrosshair()
        if (target) {
            s.targetPixels = encode(grid)
            s.targetPreset = PRESET_DRAWN
            s.swapOnTarget = true
        } else {
            s.pixels = encode(grid)
            s.normalPreset = PRESET_DRAWN
        }
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        render(client, graphics, tickDelta)
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return ((alpha.coerceIn(0, 255) and 0xFF) shl 24) or
            ((red.coerceIn(0, 255) and 0xFF) shl 16) or
            ((green.coerceIn(0, 255) and 0xFF) shl 8) or
            (blue.coerceIn(0, 255) and 0xFF)
    }

    private fun resolveProfile(settings: CrosshairSettings, onTarget: Boolean): CrosshairSettings {
        val shaped = shapeFor(settings, onTarget)
        // The colour swap is separate from the shape swap on purpose: turning red on a
        // target is the useful half, and most people want it without the crosshair also
        // changing shape underneath them.
        return if (onTarget && settings.targetColorEnabled) {
            shaped.copy(
                red = settings.targetRed,
                green = settings.targetGreen,
                blue = settings.targetBlue,
                alpha = settings.targetAlpha
            )
        } else {
            shaped
        }
    }

    /*
      Picking a style copies its values into the settings, and from there every slider and
      toggle works on it. Presets used to be applied on every frame instead, so while one
      was selected nothing below the dropdown did anything at all.
    */
    private fun applyPreset(target: CrosshairSettings, key: String) {
        val p = presetShape(target, key)
        target.normalPreset = key
        // Nothing drawn yet: start the canvas from the shape on screen rather than blank.
        if (key == PRESET_DRAWN && target.pixels.length != GRID * GRID) target.pixels = encode(rasterise(target))
        if (key == PRESET_CUSTOM || key == PRESET_DRAWN) return
        target.showTop = p.showTop && !p.tShape
        target.showBottom = p.showBottom
        target.showLeft = p.showLeft
        target.showRight = p.showRight
        target.showCenterDot = p.showCenterDot
        target.useOutline = p.useOutline
        target.dynamicAttackGap = p.dynamicAttackGap
        target.tShape = false
        target.gap = p.gap
        target.length = p.length
        target.thickness = p.thickness
        target.dotSize = p.dotSize
        target.outlineThickness = p.outlineThickness
    }

    private fun shapeFor(settings: CrosshairSettings, onTarget: Boolean): CrosshairSettings =
        if (onTarget && settings.swapOnTarget) presetShape(settings, settings.targetPreset) else settings

    // Sized against vanilla's 15px crosshair, one pixel thick: the first set was about half
    // as big again and read as clumsy next to it.
    private fun presetShape(settings: CrosshairSettings, preset: String): CrosshairSettings {
        return when (preset.lowercase()) {
            PRESET_CLASSIC -> settings.copy(
                showTop = true,
                showBottom = true,
                showLeft = true,
                showRight = true,
                showCenterDot = false,
                useOutline = true,
                dynamicAttackGap = false,
                tShape = false,
                gap = 2,
                length = 4,
                thickness = 1,
                dotSize = 1,
                outlineThickness = 1
            )
            PRESET_DOT -> settings.copy(
                showTop = false,
                showBottom = false,
                showLeft = false,
                showRight = false,
                showCenterDot = true,
                useOutline = true,
                dynamicAttackGap = false,
                tShape = false,
                gap = 0,
                length = 1,
                thickness = 1,
                dotSize = 2,
                outlineThickness = 1
            )
            PRESET_TACTICAL -> settings.copy(
                showTop = false,
                showBottom = true,
                showLeft = true,
                showRight = true,
                showCenterDot = true,
                useOutline = true,
                dynamicAttackGap = true,
                tShape = true,
                gap = 3,
                length = 4,
                thickness = 1,
                dotSize = 1,
                outlineThickness = 1
            )
            // Classic with the centre filled in - the most asked-for shape on every client.
            PRESET_CROSS_DOT -> settings.copy(
                showTop = true, showBottom = true, showLeft = true, showRight = true,
                showCenterDot = true, useOutline = true, dynamicAttackGap = false,
                tShape = false, gap = 2, length = 4, thickness = 1, dotSize = 1,
                outlineThickness = 1
            )
            // No top arm, so nothing sits over what you are aiming at.
            PRESET_T -> settings.copy(
                showTop = false, showBottom = true, showLeft = true, showRight = true,
                showCenterDot = false, useOutline = true, dynamicAttackGap = false,
                tShape = true, gap = 2, length = 4, thickness = 1, dotSize = 1,
                outlineThickness = 1
            )
            // Side arms only: the centre of the screen stays completely clear.
            PRESET_BRACKETS -> settings.copy(
                showTop = false, showBottom = false, showLeft = true, showRight = true,
                showCenterDot = false, useOutline = true, dynamicAttackGap = false,
                tShape = false, gap = 3, length = 3, thickness = 2, dotSize = 1,
                outlineThickness = 1
            )
            PRESET_WIDE -> settings.copy(
                showTop = true, showBottom = true, showLeft = true, showRight = true,
                showCenterDot = false, useOutline = true, dynamicAttackGap = false,
                tShape = false, gap = 4, length = 6, thickness = 1, dotSize = 1,
                outlineThickness = 1
            )
            // Long, thin and far apart: precise without covering anything.
            PRESET_SNIPER -> settings.copy(
                showTop = true, showBottom = true, showLeft = true, showRight = true,
                showCenterDot = true, useOutline = true, dynamicAttackGap = true,
                tShape = false, gap = 5, length = 8, thickness = 1, dotSize = 1,
                outlineThickness = 1
            )
            // A single pixel, outlined so it survives a bright background.
            PRESET_MICRO -> settings.copy(
                showTop = false, showBottom = false, showLeft = false, showRight = false,
                showCenterDot = true, useOutline = true, dynamicAttackGap = false,
                tShape = false, gap = 0, length = 1, thickness = 1, dotSize = 1,
                outlineThickness = 1
            )
            else -> settings
        }
    }
}
