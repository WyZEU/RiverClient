package dev.wyz.clientcore.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import dev.wyz.clientcore.compat.riverBlit

object ClientUi {
    private val animationValues = hashMapOf<String, Float>()
    private val cornerInsetCache = hashMapOf<Long, IntArray>()
    private var lastFrameNanos = System.nanoTime()

    // Surfaces are opaque and near-neutral, and they step in brightness rather than in
    // translucency: shell -> panel body -> card. The old palette leaned on alpha over the
    // world, so every surface took a different colour depending on what you were standing
    // in front of, and the whole menu sat at low contrast.
    const val BG = 0xF00B0C10.toInt()
    const val PANEL = 0xFF15171D.toInt()
    const val PANEL_SOFT = 0xFF1B1E26.toInt()
    const val PANEL_ALT = 0xFF23262F.toInt()
    const val BORDER = 0xFF2F333D.toInt()
    // Near-white, not pure white. Pure #FFFFFF on a dark panel glares and is the
    // single fastest way to make a dark UI look like a first draft.
    const val TEXT = 0xFFF0F2F5.toInt()
    const val MUTED = 0xFF9BA0AB.toInt()
    const val DIM = 0xFF6E7481.toInt()
    const val POSITIVE = 0xFF72F1B8.toInt()
    const val WARNING = 0xFFFFD46B.toInt()

    /**
     * Corner radii. Square, like the game and like the launcher.
     *
     * These were 6/4/4, which put rounded, shadowed cards in front of a world made
     * entirely of hard-edged blocks - it read as a web dashboard floating in
     * Minecraft rather than part of the client. The launcher's own design system is
     * "sharp, flat, restrained" with radius 0, so the in-game UI was also the odd
     * one out inside River itself.
     *
     * Kept as named constants rather than removing the parameter: a future surface
     * that genuinely wants a soft corner can opt in without touching every call.
     */
    const val RADIUS_PANEL = 0
    const val RADIUS_CARD = 0
    const val RADIUS_BUTTON = 0

    /** Hover/selection lift, composited over a surface instead of an accent wash. */
    private const val LIFT = 0xFFFFFFFF.toInt()

    // Accents follow the active theme (see RiverTheme).
    val ACCENT_A: Int get() = RiverTheme.current.accentA
    val ACCENT_B: Int get() = RiverTheme.current.accentB
    val ACCENT_SOFT: Int get() = alpha(ACCENT_A, 0.4f)
    val BORDER_STRONG: Int get() = alpha(ACCENT_B, 0.84f)

    fun beginFrame() {
        val now = System.nanoTime()
        val dt = ((now - lastFrameNanos) / 1_000_000_000.0f).coerceIn(0f, 0.1f)
        frameDelta = dt
        lastFrameNanos = now
    }

    private var frameDelta = 1f / 60f

    fun animate(key: String, target: Float, speed: Float = 10f): Float {
        val current = animationValues[key] ?: target
        val alpha = (1f - exp((-speed * frameDelta).toDouble())).toFloat()
        val next = current + (target - current) * alpha
        animationValues[key] = next
        return next
    }

    fun hover(key: String, hovered: Boolean, speed: Float = 12f): Float =
        easeOutCubic(animate(key, if (hovered) 1f else 0f, speed))

    fun alpha(color: Int, multiplier: Float): Int {
        val a = (((color ushr 24) and 0xFF) * multiplier).roundToInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    /**
     * Composites [src] over opaque [dst] and returns an opaque colour. Used to step a
     * surface up in brightness (hover, selection, enabled) without stacking translucent
     * quads over the world, which is what made the old panels take on the colour of
     * whatever was behind them.
     */
    fun over(src: Int, dst: Int): Int {
        val sa = ((src ushr 24) and 0xFF) / 255f
        val r = lerp((dst ushr 16) and 0xFF, (src ushr 16) and 0xFF, sa)
        val g = lerp((dst ushr 8) and 0xFF, (src ushr 8) and 0xFF, sa)
        val b = lerp(dst and 0xFF, src and 0xFF, sa)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun mix(from: Int, to: Int, tRaw: Float): Int {
        val t = tRaw.coerceIn(0f, 1f)
        val a = lerp((from ushr 24) and 0xFF, (to ushr 24) and 0xFF, t)
        val r = lerp((from ushr 16) and 0xFF, (to ushr 16) and 0xFF, t)
        val g = lerp((from ushr 8) and 0xFF, (to ushr 8) and 0xFF, t)
        val b = lerp(from and 0xFF, to and 0xFF, t)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun drawBackdrop(g: GuiGraphics, width: Int, height: Int) {
        // Flat scrim, like vanilla's screen dim. The panel supplies the colour; the
        // backdrop only has to push the world back.
        g.fill(0, 0, width, height, 0xC8090A0D.toInt())
    }

    /**
     * The shell every River screen sits in: solid body, one crisp border, no wash.
     *
     * No drop shadow. Elevation comes from the border and the step in surface brightness,
     * the same way it does in the launcher. A soft shadow under a hard-edged panel was the
     * one thing still making these screens look like a web dashboard placed over the world.
     */
    fun drawPanel(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int = RADIUS_PANEL) {
        fillRounded(g, x, y, w, h, radius, PANEL_SOFT)
        drawRoundedBorder(g, x, y, w, h, radius, BORDER)
        // Hairline on the top edge only - a lit bevel the way a Minecraft button has one,
        // without tinting the whole surface.
        fillRounded(g, x + 1, y + 1, w - 2, 1, 0, alpha(LIFT, 0.05f))
    }

    fun drawSectionCard(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, hovered: Float = 0f, selected: Boolean = false) {
        val base = if (selected) over(alpha(ACCENT_A, 0.20f), PANEL_ALT) else PANEL_ALT
        val fill = mix(base, over(alpha(LIFT, 0.06f), base), hovered)
        val border = if (selected) alpha(ACCENT_B, 0.90f) else mix(BORDER, over(alpha(LIFT, 0.18f), BORDER), hovered)
        fillRounded(g, x, y, w, h, RADIUS_CARD, fill)
        drawRoundedBorder(g, x, y, w, h, RADIUS_CARD, border)
    }

    fun drawListRow(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, hovered: Float = 0f, selected: Boolean = false) {
        val base = if (selected) over(alpha(ACCENT_A, 0.20f), PANEL_ALT) else PANEL
        val fill = mix(base, over(alpha(LIFT, 0.06f), base), hovered)
        val border = if (selected) alpha(ACCENT_B, 0.85f) else mix(BORDER, over(alpha(LIFT, 0.14f), BORDER), hovered)
        fillRounded(g, x, y, w, h, RADIUS_CARD, fill)
        drawRoundedBorder(g, x, y, w, h, RADIUS_CARD, border)
    }

    fun drawModuleCard(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, hovered: Float = 0f, selected: Boolean = false, enabled: Boolean = false) {
        // Enabled reads as a brighter card rather than a glow: Essential's tiles differ by
        // surface value, which survives being seen against a bright world.
        val base = if (enabled) over(alpha(ACCENT_A, 0.14f), PANEL_ALT) else PANEL_ALT
        val fill = mix(base, over(alpha(LIFT, 0.07f), base), hovered)
        val border = when {
            selected -> alpha(ACCENT_B, 0.90f)
            enabled -> alpha(ACCENT_A, 0.55f)
            else -> mix(BORDER, over(alpha(LIFT, 0.16f), BORDER), hovered)
        }
        fillRounded(g, x, y, w, h, RADIUS_CARD, fill)
        drawRoundedBorder(g, x, y, w, h, RADIUS_CARD, border)
    }

    fun drawButton(g: GuiGraphics, font: Font, x: Int, y: Int, w: Int, h: Int, label: String, hovered: Boolean, primary: Boolean = false) {
        val hoverAnim = hover("btn:$x:$y:$label", hovered)
        val base = if (primary) ACCENT_A else PANEL_ALT
        val fill = mix(base, over(alpha(LIFT, if (primary) 0.14f else 0.08f), base), hoverAnim)
        val border = if (primary) ACCENT_B else mix(BORDER, over(alpha(LIFT, 0.18f), BORDER), hoverAnim)
        fillRounded(g, x, y, w, h, RADIUS_BUTTON, fill)
        drawRoundedBorder(g, x, y, w, h, RADIUS_BUTTON, border)
        fillRounded(g, x + 1, y + 1, w - 2, 1, 0, alpha(LIFT, 0.07f))
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2, TEXT, true)
    }

    fun drawFlatButton(g: GuiGraphics, font: Font, x: Int, y: Int, w: Int, h: Int, label: String, hovered: Boolean, primary: Boolean = false) {
        val hoverAnim = hover("flatbtn:$x:$y:$label", hovered)
        val base = if (primary) ACCENT_A else PANEL_ALT
        val fill = mix(base, over(alpha(LIFT, if (primary) 0.14f else 0.08f), base), hoverAnim)
        fillRounded(g, x, y, w, h, RADIUS_BUTTON, fill)
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + (h - font.lineHeight) / 2, TEXT, true)
    }

    // [id] must be a value that is STABLE for a given toggle across frames (e.g. a
    // module id), NOT its screen position. Keying the knob animation on x/y makes it
    // reset and re-slide every time the toggle scrolls to a new y, which reads as the
    // toggle "moving" while you scroll. Callers in scrolling lists must pass an id.
    fun drawToggle(g: GuiGraphics, x: Int, y: Int, on: Boolean, hovered: Boolean, id: String = "$x:$y") {
        drawTexturedToggle(g, "toggle:$id", x, y + 1, 36, 16, 13, on, hovered)
    }

    fun drawMinimalToggle(g: GuiGraphics, x: Int, y: Int, on: Boolean, hovered: Boolean, id: String = "$x:$y") {
        drawTexturedToggle(g, "mintoggle:$id", x, y, 34, 14, 11, on, hovered)
    }

    private fun drawTexturedToggle(g: GuiGraphics, key: String, x: Int, y: Int, trackW: Int, trackH: Int, knobSize: Int, on: Boolean, hovered: Boolean) {
        val anim = animate(key, if (on) 1f else 0f, 14f)
        val hoverAnim = hover("$key:hover", hovered)
        val track = RiverTextures.get("widgets/toggle_track")
        val knob = RiverTextures.get("widgets/toggle_knob")

        val idle = mix(0xFF2B2F39.toInt(), 0xFF3A3F4B.toInt(), hoverAnim)
        val active = mix(ACCENT_A, over(alpha(LIFT, 0.12f), ACCENT_A), hoverAnim)
        val trackTint = mix(idle, active, anim)
        val pad = 2
        val knobX = x + pad + ((trackW - knobSize - pad * 2) * anim).roundToInt()
        val knobY = y + (trackH - knobSize) / 2

        if (track != null && knob != null) {
//? if >=1.21.6 {
            g.riverBlit(track, x, y, 0f, 0f, trackW, trackH, 136, 56, 136, 56, trackTint)
            g.riverBlit(knob, knobX, knobY, 0f, 0f, knobSize, knobSize, 48, 48, 48, 48, 0xFFFFFFFF.toInt())
//?} else {
/*            g.riverBlit(track, x, y, 0f, 0f, trackW, trackH, 136, 56, 136, 56, trackTint)
            g.riverBlit(knob, knobX, knobY, 0f, 0f, knobSize, knobSize, 48, 48, 48, 48, 0xFFFFFFFF.toInt())
*///?}
        } else {
            fillRounded(g, x, y, trackW, trackH, trackH / 2, trackTint)
            fillRounded(g, knobX, knobY, knobSize, knobSize, knobSize / 2, 0xFFF5F7FB.toInt())
        }
    }

    fun drawSlider(g: GuiGraphics, x0: Int, x1: Int, y: Int, valueT: Float, hovered: Boolean) {
        val hoverAnim = hover("slider:$x0:$y:$x1", hovered)
        val trackH = 8
        fillRounded(g, x0, y, x1 - x0, trackH, 3, 0xFF101218.toInt())
        val fillW = ((x1 - x0) * valueT.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(6)
        fillRounded(g, x0, y, fillW, trackH, 3, ACCENT_A)
        drawRoundedBorder(g, x0, y, x1 - x0, trackH, 3, mix(BORDER, over(alpha(LIFT, 0.18f), BORDER), hoverAnim))
        // Square-ish knob: vanilla's slider handle is a rectangle, not a pill.
        val knob = x0 + fillW - 4
        fillRounded(g, knob, y - 3, 8, 14, 2, mix(0xFFD5D9E2.toInt(), 0xFFFFFFFF.toInt(), hoverAnim))
        drawRoundedBorder(g, knob, y - 3, 8, 14, 2, 0xFF0C0D11.toInt())
    }

    fun drawGradientTitle(g: GuiGraphics, font: Font, text: String, x: Int, y: Int) {
        g.drawString(font, text, x + 2, y + 2, 0xA0000000.toInt(), false)
        var cursor = x
        val lastIndex = (text.length - 1).coerceAtLeast(1)
        text.forEachIndexed { index, char ->
            val color = mix(ACCENT_A, ACCENT_B, index.toFloat() / lastIndex.toFloat())
            val s = char.toString()
            g.drawString(font, s, cursor, y, color, true)
            cursor += font.width(s)
        }
    }

    fun drawTag(g: GuiGraphics, font: Font, x: Int, y: Int, text: String, color: Int) {
        val w = font.width(text) + 12
        fillRounded(g, x, y, w, 16, 3, over(alpha(color, 0.18f), PANEL))
        drawRoundedBorder(g, x, y, w, 16, 3, alpha(color, 0.55f))
        g.drawString(font, text, x + 6, y + 4, color or (0xFF shl 24), false)
    }

    fun <T> withScissor(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, block: () -> T): T {
        g.enableScissor(x0, y0, x1, y1)
        return try {
            block()
        } finally {
            g.disableScissor()
        }
    }

    fun fillRounded(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int) {
        if (w <= 0 || h <= 0) return
        val r = min(radius, min(w, h) / 2).coerceAtLeast(0)
        if (r <= 0) {
            g.fill(x, y, x + w, y + h, color)
            return
        }
        val insets = cornerInsets(h, r)
        for (row in 0 until h) {
            val inset = insets[row]
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, color)
        }
    }

    fun fillRoundedGradient(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, topColor: Int, bottomColor: Int) {
        if (w <= 0 || h <= 0) return
        val r = min(radius, min(w, h) / 2).coerceAtLeast(0)
        val insets = cornerInsets(h, r)
        for (row in 0 until h) {
            val inset = insets[row]
            val color = mix(topColor, bottomColor, row.toFloat() / max(1, h - 1).toFloat())
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, color)
        }
    }

    fun drawRoundedBorder(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int) {
        if (w <= 1 || h <= 1) return
        val right = x + w
        val bottom = y + h
        fillRounded(g, x, y, w, 1, radius, color)
        fillRounded(g, x, bottom - 1, w, 1, radius, color)
        g.fill(x, y + 1, x + 1, bottom - 1, color)
        g.fill(right - 1, y + 1, right, bottom - 1, color)
    }

    /**
     * Separation from the world behind, not a raised card.
     *
     * This used to run at 0.38 alpha over a 3px spread, which is a CSS drop shadow -
     * on rounded corners it made every panel look like it was hovering above the
     * game. Halved and tightened so a panel reads as sitting flat against the world
     * with a defined edge, the way the game's own inventory does.
     */
    fun drawShadow(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, radius: Int, color: Int, spread: Int) {
        val limitedSpread = spread.coerceIn(1, 2)
        for (i in limitedSpread downTo 1) {
            val alpha = i.toFloat() / limitedSpread.toFloat() * 0.18f
            fillRounded(g, x - i, y - i, w + i * 2, h + i * 2, radius, this.alpha(color, alpha))
        }
    }

    fun drawScrollbar(g: GuiGraphics, x: Int, y: Int, h: Int, contentH: Int, viewH: Int, scroll: Int) {
        if (contentH <= viewH) return
        fillRounded(g, x, y, 5, h, 2, 0xFF101218.toInt())
        val thumbH = (viewH.toFloat() / contentH * h).roundToInt().coerceAtLeast(24)
        val maxScroll = max(1, contentH - viewH)
        val thumbY = y + ((scroll.toFloat() / maxScroll.toFloat()) * (h - thumbH)).roundToInt()
        fillRounded(g, x, thumbY, 5, thumbH, 2, ACCENT_A)
    }

    fun iconForCategory(id: String): String = when (id.lowercase()) {
        "hud" -> "HUD"
        "pvp" -> "PVP"
        "utility" -> "UTIL"
        else -> "MOD"
    }

    /** HSV (all 0..1) to 0xRRGGBB. */
    fun hsbToRgb(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 1f) + 1f) % 1f
        val i = (hh * 6f).toInt()
        val f = hh * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i % 6) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return ((r * 255).roundToInt() shl 16) or ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
    }

    /** 0xRRGGBB to HSV floats [h, s, v], each 0..1. */
    fun rgbToHsb(rgb: Int): FloatArray {
        val r = ((rgb ushr 16) and 0xFF) / 255f
        val g = ((rgb ushr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        var h = 0f
        if (d > 0.00001f) {
            h = when (max) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } / 6f
            if (h < 0f) h += 1f
        }
        return floatArrayOf(h, if (max <= 0f) 0f else d / max, max)
    }

    private fun easeOutCubic(value: Float): Float = 1f - (1f - value).pow(3)

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + ((b - a) * t)).roundToInt()

    private fun cornerInset(row: Int, h: Int, radius: Int): Int {
        if (radius <= 0) return 0
        val top = row
        val bottom = h - 1 - row
        val edgeDist = min(top, bottom)
        if (edgeDist >= radius) return 0
        val dy = radius - edgeDist - 0.5f
        val dx = radius - kotlin.math.sqrt((radius * radius - dy * dy).coerceAtLeast(0f))
        return dx.roundToInt().coerceIn(0, radius)
    }

    private fun cornerInsets(h: Int, radius: Int): IntArray {
        if (h <= 0 || radius <= 0) return IntArray(max(0, h))
        val key = (h.toLong() shl 32) or (radius.toLong() and 0xFFFF_FFFFL)
        return cornerInsetCache.getOrPut(key) {
            IntArray(h) { row -> cornerInset(row, h, radius) }
        }
    }
}
