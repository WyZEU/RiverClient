package dev.wyz.clientcore.hud

import com.mojang.blaze3d.platform.NativeImage
import dev.wyz.clientcore.RiverRuntime
import dev.wyz.clientcore.ui.ClientUi
import dev.wyz.clientcore.ui.RiverTheme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import kotlin.math.roundToInt

/**
 * Lunar/Feather-style watermark: just the River logo + "River Client", no panel.
 * It only draws over screens (pause, inventory, ...) — never in-world and never on
 * the main menu — and anchors to the corner chosen in settings (default bottom-right).
 */
object WatermarkRenderer {

    private const val TEX_W = 500
    private const val TEX_H = 500
    private const val LOGO_H = 15
    private const val GAP = 5
    private const val MARGIN = 6
    private const val SHADOW = 0xEE000000.toInt()
    private const val LABEL = "River Client"

    private var logoTexture: ResourceLocation? = null
    private var logoLoadAttempted = false

    fun initialize() = Unit

    private fun logo(): ResourceLocation? {
        if (!logoLoadAttempted) {
            logoLoadAttempted = true
            logoTexture = runCatching {
                val stream = WatermarkRenderer::class.java.getResourceAsStream(
                    "/assets/clientcore/textures/watermark_logo.png"
                ) ?: return@runCatching null
                val image = stream.use { NativeImage.read(it) }
                val id = ResourceLocation.fromNamespaceAndPath("clientcore", "river_watermark_dynamic")
                Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
                id
            }.getOrNull()
        }
        return logoTexture
    }

    private fun logoDrawWidth(): Int = (LOGO_H * TEX_W.toFloat() / TEX_H.toFloat()).roundToInt().coerceAtLeast(8)

    /** Draws the watermark for the current screen, if it should show here. */
    fun renderOverlay(client: Minecraft, g: GuiGraphics) {
        val watermark = dev.wyz.clientcore.module.ModuleRegistry.get<dev.wyz.clientcore.module.Module>("watermark") ?: return
        if (!watermark.active) return

        val font = client.font
        val hasLogo = logo() != null
        val logoW = if (hasLogo) logoDrawWidth() else 0
        val textW = font.width(LABEL)
        val blockW = logoW + (if (hasLogo) GAP else 0) + textW
        val blockH = maxOf(LOGO_H, font.lineHeight)

        val sw = client.window.guiScaledWidth
        val sh = client.window.guiScaledHeight
        val corner = RiverRuntime.configOrNull()?.watermarkCorner ?: 3
        val x = if (corner == 1 || corner == 3) sw - MARGIN - blockW else MARGIN
        val y = if (corner == 2 || corner == 3) sh - MARGIN - blockH else MARGIN

        if (hasLogo) {
            val logoY = y + (blockH - LOGO_H) / 2
            // 12-arg blit: scale the whole TEX_W x TEX_H logo down into logoW x LOGO_H.
            g.blit(RenderType::guiTextured, logo()!!, x, logoY, 0f, 0f, logoW, LOGO_H, TEX_W, TEX_H, TEX_W, TEX_H)
        }
        val textX = x + logoW + if (hasLogo) GAP else 0
        val textY = y + (blockH - font.lineHeight) / 2
        drawGradientText(font, g, textX, textY)
    }

    private var colorCacheStart = 0
    private var colorCacheEnd = 0
    private var colorCache: IntArray = IntArray(0)
    private val chars: Array<String> = Array(LABEL.length) { LABEL[it].toString() }

    private fun drawGradientText(font: Font, g: GuiGraphics, x: Int, y: Int) {
        val theme = RiverTheme.current
        val start = ClientUi.mix(theme.accentA, 0xFFFFFFFF.toInt(), 0.35f)
        val end = ClientUi.mix(theme.accentB, 0xFFFFFFFF.toInt(), 0.20f)
        if (colorCache.size != LABEL.length || colorCacheStart != start || colorCacheEnd != end) {
            colorCacheStart = start
            colorCacheEnd = end
            val lastIndex = (LABEL.length - 1).coerceAtLeast(1)
            colorCache = IntArray(LABEL.length) { lerpColor(start, end, it.toFloat() / lastIndex.toFloat()) }
        }

        g.drawString(font, LABEL, x + 1, y + 1, SHADOW, false)
        var cursor = x
        for (i in chars.indices) {
            g.drawString(font, chars[i], cursor, y, colorCache[i], false)
            cursor += font.width(chars[i])
        }
    }

    private fun lerpColor(start: Int, end: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        val a = channel(start, 24) + ((channel(end, 24) - channel(start, 24)) * t).roundToInt()
        val r = channel(start, 16) + ((channel(end, 16) - channel(start, 16)) * t).roundToInt()
        val g = channel(start, 8) + ((channel(end, 8) - channel(start, 8)) * t).roundToInt()
        val b = channel(start, 0) + ((channel(end, 0) - channel(start, 0)) * t).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun channel(color: Int, shift: Int): Int = (color shr shift) and 0xFF
}
