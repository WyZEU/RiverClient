package dev.wyz.clientcore.ui.menu

import dev.wyz.clientcore.compat.McId
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import dev.wyz.clientcore.compat.riverBlit
import dev.wyz.clientcore.ui.Seasons
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a season adds to the title screen, drawn behind the buttons: a moon with a soft
 * glow, flapping bats, mist rolling along the water and jack o'lanterns flickering in the
 * corners. The bats, moon and mist are pixel-art sprites in
 * assets/clientcore/textures/season.
 */
object SeasonalFx {


    // Sprites from assets/clientcore/textures/season, loaded the first time they are drawn.
    private var loaded = false
    private var batTex: McId? = null
    private var glowTex: McId? = null
    private var mistTex: McId? = null
    private var webLeftTex: McId? = null
    private var webRightTex: McId? = null
    private var spiderTex: McId? = null
    private var pumpkinTex: McId? = null
    private var pumpkinLightTex: McId? = null

    private const val BAT_W = 96
    private const val BAT_H = 60
    private const val BAT_FRAMES = 8
    private const val MIST_W = 512
    private const val MIST_H = 96

    private fun ensureTextures() {
        if (loaded) return
        loaded = true
        val dir = "/assets/clientcore/textures/season"
        batTex = RiverMainMenu.loadTexture("$dir/bat.png", "season_bat")?.first
        glowTex = RiverMainMenu.loadTexture("$dir/moon_glow.png", "season_moon_glow")?.first
        mistTex = RiverMainMenu.loadTexture("$dir/mist.png", "season_mist")?.first
        webLeftTex = RiverMainMenu.loadTexture("$dir/web_left.png", "season_web_left")?.first
        webRightTex = RiverMainMenu.loadTexture("$dir/web_right.png", "season_web_right")?.first
        spiderTex = RiverMainMenu.loadTexture("$dir/spider.png", "season_spider")?.first
        pumpkinTex = RiverMainMenu.loadTexture("$dir/pumpkin.png", "season_pumpkin")?.first
        pumpkinLightTex = RiverMainMenu.loadTexture("$dir/pumpkin_light.png", "season_pumpkin_light")?.first
    }

    private var halloweenLogoTex: McId? = null
    private var halloweenLogoLoaded = false

    /**
     * The orange River logo while the Halloween theme is on, else null so callers keep the
     * blue one. Used by the title screen's River button and the watermark.
     */
    fun halloweenLogo(): McId? {
        if (!halloween()) return null
        if (!halloweenLogoLoaded) {
            halloweenLogoLoaded = true
            halloweenLogoTex = RiverMainMenu.loadTexture(
                "/assets/clientcore/textures/watermark_logo_halloween.png", "river_logo_halloween"
            )?.first
        }
        return halloweenLogoTex
    }

    /**
     * Whether the window should open with the Halloween icon. The icon is set before the
     * config is necessarily loaded, so this reads it if needed and predicts the one-time
     * October switch that [Seasons.offerOnce] makes for players still on the default theme.
     */
    @JvmStatic
    fun halloweenAtBoot(): Boolean = runCatching {
        val season = Seasons.active()
        val cfg = dev.wyz.clientcore.RiverRuntime.configOrNull() ?: dev.wyz.clientcore.config.ConfigService.load()
        val theme = cfg.theme ?: "river"
        // Outside October only somebody who earned Halloween for good still has it.
        if (season == null || season.id != "halloween") {
            return dev.wyz.clientcore.ui.SeasonEvent.kept("halloween") && theme == "halloween"
        }
        theme == season.paletteKey || (theme == "river" && cfg.seasonOffered != season.id)
    }.getOrDefault(false)

    /** True while the Halloween theme is the one in use. Everything Halloween hangs off this. */
    fun halloween(): Boolean = dev.wyz.clientcore.ui.RiverTheme.current.key == "halloween"

    /** Drawn right after the title background's own vignette. */
    fun renderBackdrop(g: GuiGraphics, w: Int, h: Int) {
        if (!halloween()) return
        val now = System.currentTimeMillis()
        // Seconds within the hour. Milliseconds since 1970 are too large for a Float, so
        // anything moved by them only advanced every few minutes and the mist stood still.
        val t = (now % 3_600_000L) / 1000f

        // The night itself is in the background art (menu_background_halloween.png): moon
        // and stars sit in the sky, behind the trees. Here only a warm glow low down,
        // where the lanterns are.
        g.fillGradient(0, (h * 0.7f).toInt(), w, h, 0x00000000, 0x44B4500C)

        ensureTextures()

        // A low pumpkin glow behind the RIVER title, at the title's own resting place.
        glowTex?.let { tex ->
            val titleW = (w * 0.26f).roundToInt().coerceIn(150, 260)
            val titleH = titleW * 551 / 2048
            val gw = (titleW * 1.7f).roundToInt()
            val gh = (titleH * 3.2f).roundToInt()
            val gy = (h * 0.11f).roundToInt() + titleH / 2 - gh / 2
            val pulse = (0.55f + 0.1f * sin(t * 1.3f)) * 255f
            g.riverBlit(tex, w / 2 - gw / 2, gy, 0f, 0f, gw, gh, 128, 128, 128, 128, (pulse.roundToInt() shl 24) or 0xFF8A3C)
        }


        // Bats: five shadows sized to the screen, each at its own height and speed.
        batTex?.let { tex ->
            for (i in 0 until 5) {
                val size = when (i) { 0 -> 1f; 3 -> 0.8f; else -> 0.62f }
                val bw = (w * 0.055f * size).roundToInt().coerceAtLeast(12)
                val bh = bw * BAT_H / BAT_W
                val speed = w * (0.03f + 0.012f * i)
                val span = w + bw * 2f
                val travel = ((t * speed + i * 211f) % span) - bw
                val x = if (i % 2 == 1) w - travel - bw else travel
                val y = h * (0.06f + 0.075f * i) + sin(t * 1.4f + i * 2.1f) * h * 0.012f
                val frame = ((now / 70L + i * 3) % BAT_FRAMES).toInt()
                // Shadows: see-through, and fainter the smaller (further away) they are.
                val shade = (255 * (0.32f + 0.38f * size)).roundToInt().coerceIn(0, 255)
                g.riverBlit(
                    tex, x.toInt(), y.toInt(), (frame * BAT_W).toFloat(), 0f,
                    bw, bh, BAT_W, BAT_H, BAT_W * BAT_FRAMES, BAT_H, (shade shl 24) or 0xFFFFFF
                )
            }
        }

        // Mist: two layers of the same soft strip, tiled and drifting at different speeds.
        mistTex?.let { tex ->
            drawMistLayer(g, tex, w, (h * 0.8f).toInt(), (h * 0.2f).toInt(), t, w * 0.018f, 0x66FFFFFF)
        }

        // Cobwebs in the top corners, and a spider letting itself down from the right one.
        val webSize = (h * 0.3f).roundToInt().coerceAtLeast(48)
        webLeftTex?.let { g.riverBlit(it, 0, 0, 0f, 0f, webSize, webSize, 256, 256, 256, 256, 0xB4FFFFFF.toInt()) }
        webRightTex?.let { g.riverBlit(it, w - webSize, 0, 0f, 0f, webSize, webSize, 256, 256, 256, 256, 0xB4FFFFFF.toInt()) }
        spiderTex?.let { tex ->
            val spiderSize = (h * 0.045f).roundToInt().coerceAtLeast(9)
            val anchorX = w - (webSize * 0.42f).roundToInt()
            val anchorY = (webSize * 0.22f).roundToInt()
            // Lowers itself, pauses, climbs back: a slow wave with a flat bottom.
            val drop = (h * 0.1f + h * 0.07f * sin(t * 0.45f).coerceAtLeast(-0.4f)).roundToInt()
            val sy = anchorY + drop
            // A barely-there thread and a shadow of a spider, like the bats: it should be
            // something you notice, not something pasted on.
            g.fill(anchorX, anchorY, anchorX + 1, sy + spiderSize / 3, 0x3CCFD0E6)
            g.riverBlit(tex, anchorX - spiderSize / 2, sy, 0f, 0f, spiderSize, spiderSize, 32, 32, 32, 32, 0xC8FFFFFF.toInt())
        }

        // Two jack o'lanterns in the bottom corners, above the corner labels. Whole-number
        // scale only, so the pixel art stays square.
        val k = (h * 0.085f / 32f).roundToInt().coerceAtLeast(1)
        val size = 32 * k
        drawLantern(g, (w * 0.06f).toInt(), h - size - 24, size, now, 0)
        drawLantern(g, w - (w * 0.06f).toInt() - size, h - size - 24, size, now, 1)
    }

    private fun drawMistLayer(g: GuiGraphics, tex: McId, w: Int, y: Int, layerH: Int, t: Float, speed: Float, tint: Int) {
        val tileW = (layerH * MIST_W.toFloat() / MIST_H).roundToInt().coerceAtLeast(1)
        val offset = ((t * speed) % tileW).toInt()
        var x = -offset
        while (x < w) {
            g.riverBlit(tex, x, y, 0f, 0f, tileW, layerH, MIST_W, MIST_H, MIST_W, MIST_H, tint)
            x += tileW
        }
    }

    /** A filled circle built from [step]-sized squares, so it stays pixelated at any scale. */
    private fun disc(g: GuiGraphics, cx: Int, cy: Int, r: Int, color: Int, step: Int) {
        if (r <= 0) return
        var y = -r
        while (y <= r) {
            val half = sqrt((r * r - y * y).toDouble()).toInt() / step * step
            if (half > 0) g.fill(cx - half, cy + y, cx + half, cy + y + step, color)
            y += step
        }
    }

    private fun drawLantern(g: GuiGraphics, x: Int, y: Int, size: Int, now: Long, seed: Int) {
        // Flicker: two waves out of step, so the candle never looks like a loop.
        val flicker = (0.78f + 0.14f * sin((now / 170.0 + seed * 3).toFloat()) + 0.08f * sin((now / 53.0 + seed).toFloat()))
            .coerceIn(0f, 1f)
        val cx = x + size / 2
        val cy = y + size * 21 / 32
        glowTex?.let { tex ->
            val gs = (size * 3.2f).roundToInt()
            val a = (flicker * 200f).roundToInt().coerceIn(0, 255)
            g.riverBlit(tex, cx - gs / 2, cy - gs / 2, 0f, 0f, gs, gs, 128, 128, 128, 128, (a shl 24) or 0xFF7A1E)
        }
        pumpkinTex?.let { g.riverBlit(it, x, y, 0f, 0f, size, size, 32, 32, 32, 32) }
        pumpkinLightTex?.let {
            val a = (flicker * 255f).roundToInt().coerceIn(0, 255)
            g.riverBlit(it, x, y, 0f, 0f, size, size, 32, 32, 32, 32, (a shl 24) or 0xFFFFFF)
        }
    }

    /** A faint web in the top right corner of a panel. */
    fun drawPanelWeb(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int) {
        ensureTextures()
        val size = (h * 0.16f).roundToInt().coerceIn(40, 96)
        webRightTex?.let { g.riverBlit(it, x + w - size - 1, y + 1, 0f, 0f, size, size, 256, 256, 256, 256, 0x40FFFFFF) }
    }

    /** Webs in both top corners of a full screen, for screens with no panel of their own. */
    fun drawScreenDecor(g: GuiGraphics, w: Int, h: Int) {
        if (!halloween()) return
        ensureTextures()
        val size = (h * 0.22f).roundToInt().coerceAtLeast(48)
        webLeftTex?.let { g.riverBlit(it, 0, 0, 0f, 0f, size, size, 256, 256, 256, 256, 0x70FFFFFF) }
        webRightTex?.let { g.riverBlit(it, w - size, 0, 0f, 0f, size, size, 256, 256, 256, 256, 0x70FFFFFF) }
    }

    /** A small pumpkin for UI labels. */
    fun drawPumpkinIcon(g: GuiGraphics, x: Int, y: Int, size: Int) {
        ensureTextures()
        pumpkinTex?.let { g.riverBlit(it, x, y, 0f, 0f, size, size, 32, 32, 32, 32) }
        pumpkinLightTex?.let { g.riverBlit(it, x, y, 0f, 0f, size, size, 32, 32, 32, 32) }
    }

    /** Short lines for the title splash while a season is on. No em dashes, like the others. */
    fun splashes(): List<String> = when (if (halloween()) "halloween" else null) {
        "halloween" -> listOf(
            "Boo!",
            "Spooky season!",
            "Trick or treat!",
            "Beware of creepers!",
            "Stay spooky!",
            "Pumpkins everywhere!",
            "Watch your totems!",
            "Something is in the water!",
            "Happy Halloween!",
            "Bats included!",
            "Carve a pumpkin!"
        )
        else -> emptyList()
    }
}
