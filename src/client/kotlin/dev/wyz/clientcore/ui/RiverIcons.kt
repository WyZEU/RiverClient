package dev.wyz.clientcore.ui

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import kotlin.math.roundToInt

/**
 * UI icons. Primary path: real Lucide icons (ISC license, https://lucide.dev) shipped as
 * white 48px PNGs inside the jar and uploaded as dynamic textures — loaded via the
 * classloader, NOT the resource-pack system, so they work under agent injection where
 * clientcore is not a Fabric mod. Tinting happens at draw time via the blit color.
 * If a texture is missing/unreadable, the old procedural shapes draw as a fallback.
 */
object RiverIcons {

    private const val TEX_SIZE = 48
    private val textures = HashMap<String, Identifier?>()

    private fun textureFor(icon: String): Identifier? = textures.getOrPut(icon) {
        runCatching {
            val stream = RiverIcons::class.java.getResourceAsStream(
                "/assets/clientcore/textures/ui/icons/$icon.png"
            ) ?: return@runCatching null
            val image = stream.use { NativeImage.read(it) }
            val id = Identifier.fromNamespaceAndPath("clientcore", "river_ui_icon_$icon")
            Minecraft.getInstance().textureManager.register(id, DynamicTexture({ "river-icon-$icon" }, image))
            id
        }.getOrNull()
    }

    fun draw(g: GuiGraphics, icon: String, x: Int, y: Int, size: Int, color: Int) {
        val texture = textureFor(icon)
        if (texture != null) {
            g.blit(
                RenderPipelines.GUI_TEXTURED, texture,
                x, y, 0f, 0f, size, size,
                TEX_SIZE, TEX_SIZE, TEX_SIZE, TEX_SIZE,
                color
            )
            return
        }
        drawFallback(g, icon, x, y, size, color)
    }

    private fun drawFallback(g: GuiGraphics, icon: String, x: Int, y: Int, size: Int, color: Int) {
        val s = size / 12f
        fun px(v: Float): Int = (v * s).roundToInt()
        fun rect(x0: Float, y0: Float, w: Float, h: Float) {
            g.fill(x + px(x0), y + px(y0), x + px(x0) + px(w).coerceAtLeast(1), y + px(y0) + px(h).coerceAtLeast(1), color)
        }
        fun frame(x0: Float, y0: Float, w: Float, h: Float) {
            rect(x0, y0, w, 1f)
            rect(x0, y0 + h - 1f, w, 1f)
            rect(x0, y0 + 1f, 1f, h - 2f)
            rect(x0 + w - 1f, y0 + 1f, 1f, h - 2f)
        }

        when (icon) {
            // --- categories
            "monitor" -> { frame(1f, 2f, 10f, 7f); rect(4f, 10f, 4f, 1f); rect(5.5f, 9f, 1f, 1f) }
            "eye" -> { rect(2f, 5f, 8f, 3f); rect(3f, 4f, 6f, 5f); rect(5f, 5.5f, 2f, 2f) }
            "compass" -> { frame(1.5f, 1.5f, 9f, 9f); rect(5f, 4f, 2f, 4f); rect(4f, 5f, 4f, 2f) }
            "wrench" -> { rect(7f, 2f, 3f, 3f); rect(6f, 4f, 2f, 2f); rect(5f, 5f, 2f, 2f); rect(4f, 6f, 2f, 2f); rect(2f, 7f, 3f, 3f) }
            "sparkle" -> { rect(5f, 1f, 2f, 10f); rect(1f, 5f, 10f, 2f); rect(3f, 3f, 1f, 1f); rect(8f, 3f, 1f, 1f); rect(3f, 8f, 1f, 1f); rect(8f, 8f, 1f, 1f) }
            "gear" -> { frame(3f, 3f, 6f, 6f); rect(5f, 1f, 2f, 2f); rect(5f, 9f, 2f, 2f); rect(1f, 5f, 2f, 2f); rect(9f, 5f, 2f, 2f) }
            "star" -> { rect(5f, 1f, 2f, 3f); rect(2f, 4f, 8f, 2f); rect(3f, 6f, 6f, 2f); rect(2.5f, 8f, 2f, 2f); rect(7.5f, 8f, 2f, 2f) }
            "search" -> { frame(2f, 2f, 6f, 6f); rect(8f, 8f, 3f, 1.4f) }
            "layout" -> { frame(1f, 1f, 10f, 10f); rect(2f, 4f, 8f, 1f); rect(5f, 5f, 1f, 5f) }
            "shield" -> { rect(3f, 1.5f, 6f, 1f); rect(2f, 2.5f, 8f, 4f); rect(3f, 6.5f, 6f, 2f); rect(4f, 8.5f, 4f, 1.5f); rect(5f, 10f, 2f, 1f) }
            "folder" -> { rect(1f, 3f, 4f, 1.4f); frame(1f, 4f, 10f, 6f) }

            // --- module icons
            "gauge" -> { frame(1.5f, 2f, 9f, 8f); rect(5.5f, 5f, 1.4f, 3f); rect(6.5f, 4f, 1.6f, 1.4f) }
            "pointer" -> { rect(4f, 1f, 4f, 5f); frame(3f, 3f, 6f, 8f); rect(5.4f, 2f, 1.2f, 3f) }
            "wifi" -> { rect(2f, 4f, 8f, 1.2f); rect(3.5f, 6f, 5f, 1.2f); rect(5f, 8f, 2f, 1.2f); rect(5.4f, 10f, 1.2f, 1.2f) }
            "keys" -> { frame(1f, 3f, 10f, 6f); rect(3f, 5f, 1.4f, 1.4f); rect(5.4f, 5f, 1.4f, 1.4f); rect(7.8f, 5f, 1.4f, 1.4f) }
            "location" -> { rect(4f, 1.5f, 4f, 1f); rect(3f, 2.5f, 6f, 4f); rect(4f, 6.5f, 4f, 2f); rect(5f, 8.5f, 2f, 2f) ; rect(5.2f, 3.8f, 1.6f, 1.6f)}
            "speed" -> { rect(1.5f, 7f, 9f, 1.2f); rect(2.5f, 4.5f, 7f, 1.2f); rect(4f, 2f, 4f, 1.2f); rect(8.5f, 1.5f, 2f, 2f) }
            "tool" -> { rect(2f, 2f, 3f, 3f); rect(4.5f, 4.5f, 2f, 2f); rect(6f, 6f, 2f, 2f); rect(7.5f, 7.5f, 2.5f, 2.5f) }
            "clock" -> { frame(1.5f, 1.5f, 9f, 9f); rect(5.5f, 3.5f, 1.2f, 3f); rect(6f, 6f, 2.4f, 1.2f) }
            "hourglass" -> { rect(3f, 1.5f, 6f, 1.2f); rect(4f, 3f, 4f, 2f); rect(5f, 5f, 2f, 2f); rect(4f, 7f, 4f, 2f); rect(3f, 9.3f, 6f, 1.2f) }
            "chip" -> { frame(3f, 3f, 6f, 6f); rect(5f, 1f, 2f, 2f); rect(5f, 9f, 2f, 2f); rect(1f, 5f, 2f, 2f); rect(9f, 5f, 2f, 2f); rect(5f, 5f, 2f, 2f) }
            "shirt" -> { rect(2f, 2f, 3f, 2f); rect(7f, 2f, 3f, 2f); rect(4f, 2f, 4f, 3f); rect(3f, 4f, 6f, 6f) }
            "flask" -> { rect(4.5f, 1.5f, 3f, 1.2f); rect(5f, 2.5f, 2f, 3f); rect(4f, 5.5f, 4f, 1.5f); rect(3f, 7f, 6f, 3f) }
            "grid" -> { frame(1f, 1f, 10f, 10f); rect(2f, 5.4f, 8f, 1.2f); rect(5.4f, 2f, 1.2f, 8f) }
            "list" -> { rect(2f, 2.5f, 1.4f, 1.4f); rect(4.5f, 2.5f, 5.5f, 1.4f); rect(2f, 5.4f, 1.4f, 1.4f); rect(4.5f, 5.4f, 5.5f, 1.4f); rect(2f, 8.3f, 1.4f, 1.4f); rect(4.5f, 8.3f, 5.5f, 1.4f) }
            "swords" -> { rect(2f, 2f, 2f, 2f); rect(3.5f, 3.5f, 2f, 2f); rect(5f, 5f, 2f, 2f); rect(8f, 2f, 2f, 2f); rect(6.5f, 3.5f, 2f, 2f); rect(3f, 8f, 6f, 1.4f); rect(5.3f, 6.8f, 1.4f, 3.6f) }
            "target" -> { frame(1.5f, 1.5f, 9f, 9f); frame(3.5f, 3.5f, 5f, 5f); rect(5.4f, 5.4f, 1.4f, 1.4f) }
            "pearl" -> { frame(2.5f, 2.5f, 7f, 7f); rect(4f, 4f, 2f, 2f) }
            "totem" -> { rect(4f, 1.5f, 4f, 3f); rect(1.5f, 4.5f, 9f, 2f); rect(4f, 6.5f, 4f, 4f); rect(5f, 4.7f, 0.8f, 1f); rect(6.4f, 4.7f, 0.8f, 1f) }
            "apple" -> { rect(5.4f, 1f, 1.2f, 2f); rect(3f, 3f, 6f, 1.5f); rect(2f, 4.5f, 8f, 4f); rect(3f, 8.5f, 6f, 1.5f) }
            "burst" -> { rect(5f, 1f, 2f, 3f); rect(5f, 8f, 2f, 3f); rect(1f, 5f, 3f, 2f); rect(8f, 5f, 3f, 2f); rect(3f, 3f, 1.5f, 1.5f); rect(7.5f, 3f, 1.5f, 1.5f); rect(3f, 7.5f, 1.5f, 1.5f); rect(7.5f, 7.5f, 1.5f, 1.5f) }
            "crosshair" -> { rect(5.4f, 1f, 1.2f, 3.4f); rect(5.4f, 7.6f, 1.2f, 3.4f); rect(1f, 5.4f, 3.4f, 1.2f); rect(7.6f, 5.4f, 3.4f, 1.2f) }
            "zoom" -> { frame(2f, 2f, 6f, 6f); rect(8f, 8f, 3f, 1.4f); rect(4f, 4.4f, 2f, 1.2f) }
            "sun" -> { frame(4f, 4f, 4f, 4f); rect(5.4f, 1f, 1.2f, 2f); rect(5.4f, 9f, 1.2f, 2f); rect(1f, 5.4f, 2f, 1.2f); rect(9f, 5.4f, 2f, 1.2f); rect(2.2f, 2.2f, 1.2f, 1.2f); rect(8.6f, 2.2f, 1.2f, 1.2f); rect(2.2f, 8.6f, 1.2f, 1.2f); rect(8.6f, 8.6f, 1.2f, 1.2f) }
            "box" -> { frame(2f, 3f, 8f, 7f); rect(2f, 3f, 8f, 1f); rect(4f, 1.5f, 4f, 1.5f) }
            "border" -> { frame(1f, 1f, 10f, 10f); rect(3f, 3f, 1.2f, 1.2f); rect(5.4f, 5.4f, 1.2f, 1.2f); rect(7.8f, 7.8f, 1.2f, 1.2f) }
            "flame" -> { rect(5f, 1f, 2f, 2f); rect(4f, 3f, 4f, 2f); rect(3f, 5f, 6f, 3f); rect(4f, 8f, 4f, 2f) }
            "run" -> { rect(6f, 1.5f, 2f, 2f); rect(5f, 4f, 3f, 2f); rect(3f, 5f, 2f, 1.4f); rect(8f, 5.4f, 2f, 1.4f); rect(4f, 6.5f, 2f, 2f); rect(2.5f, 8.5f, 2f, 2f); rect(7f, 7f, 1.4f, 3f) }
            "orbit" -> { frame(4f, 4f, 4f, 4f); rect(1f, 1f, 2f, 2f); rect(9f, 9f, 2f, 2f); rect(2.5f, 5.4f, 1.2f, 1.2f); rect(8.3f, 5.4f, 1.2f, 1.2f) }
            "flag" -> { rect(3f, 1f, 1.4f, 10f); rect(4.4f, 1.5f, 5f, 4f) }
            // Two figures, the back one smaller and offset so they read as a pair at 11px.
            "users" -> {
                rect(2.5f, 2f, 3f, 3f); rect(1.5f, 6f, 5f, 4f)
                rect(7.5f, 2.5f, 2.5f, 2.5f); rect(7f, 6f, 4f, 3.5f)
            }
            "camera" -> { frame(1f, 3f, 10f, 7f); rect(4f, 1.8f, 4f, 1.4f); frame(4.5f, 5f, 3f, 3f) }
            "tag" -> { rect(1.5f, 1.5f, 5f, 5f); rect(4f, 4f, 5f, 5f); rect(3f, 3f, 1.4f, 1.4f) }

            // --- controls
            "x" -> { rect(2.5f, 2.5f, 2f, 2f); rect(4.2f, 4.2f, 1.6f, 1.6f); rect(5.6f, 5.6f, 1f, 1f); rect(7.5f, 2.5f, 2f, 2f); rect(5.8f, 4.2f, 1.6f, 1.6f); rect(2.5f, 7.5f, 2f, 2f); rect(4.2f, 5.8f, 1.6f, 1.6f); rect(7.5f, 7.5f, 2f, 2f); rect(5.8f, 5.8f, 1.6f, 1.6f) }
            "plus" -> { rect(5.2f, 2f, 1.6f, 8f); rect(2f, 5.2f, 8f, 1.6f) }
            "copy" -> { frame(1.5f, 1.5f, 6.5f, 6.5f); frame(4f, 4f, 6.5f, 6.5f) }
            "trash" -> { rect(2.5f, 2.5f, 7f, 1.2f); rect(4.5f, 1.2f, 3f, 1.3f); rect(3f, 4f, 6f, 6.5f); }
            "edit" -> { rect(7.5f, 1.5f, 3f, 3f); rect(6f, 4f, 2.5f, 2.5f); rect(4.5f, 5.5f, 2.5f, 2.5f); rect(3f, 7f, 2.5f, 2.5f); rect(1.5f, 8.5f, 2f, 2f) }
            "reset" -> { rect(2f, 2f, 1.4f, 4f); rect(2f, 2f, 4f, 1.4f); rect(3f, 3.5f, 2f, 2f); rect(4.5f, 5f, 3f, 3f); rect(6.5f, 3f, 3.5f, 1.4f); rect(8.6f, 4f, 1.4f, 4f); rect(5f, 8.6f, 5f, 1.4f) }
            "check" -> { rect(2f, 6f, 1.6f, 1.6f); rect(3.4f, 7.4f, 1.6f, 1.6f); rect(5f, 6f, 1.6f, 1.6f); rect(6.6f, 4.4f, 1.6f, 1.6f); rect(8.2f, 2.8f, 1.6f, 1.6f) }
            "chevron_down" -> { rect(2.5f, 4f, 2f, 2f); rect(4.2f, 5.5f, 1.8f, 1.8f); rect(6f, 5.5f, 1.8f, 1.8f); rect(7.5f, 4f, 2f, 2f) }
            "keyboard" -> { frame(1f, 3f, 10f, 6f); rect(3f, 5f, 1.2f, 1.2f); rect(5.4f, 5f, 1.2f, 1.2f); rect(7.8f, 5f, 1.2f, 1.2f); rect(4f, 7f, 4f, 1f) }
            "palette" -> { frame(1.5f, 1.5f, 9f, 9f); rect(3.5f, 3.5f, 1.6f, 1.6f); rect(7f, 3.5f, 1.6f, 1.6f); rect(3.5f, 7f, 1.6f, 1.6f); rect(7f, 7f, 1.6f, 1.6f) }

            // Spotify: disc with three stacked waves. Drawn by hand as well as shipped as a
            // PNG, because the generic else-branch square is what this module used to render.
            "spotify" -> {
                frame(1f, 1f, 10f, 10f)
                rect(3f, 3.4f, 6f, 1.2f)
                rect(3.6f, 5.4f, 4.8f, 1.1f)
                rect(4.2f, 7.2f, 3.6f, 1f)
            }
            else -> { frame(2f, 2f, 8f, 8f) }
        }
    }
}
