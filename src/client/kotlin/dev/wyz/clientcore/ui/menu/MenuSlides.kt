package dev.wyz.clientcore.ui.menu

import com.mojang.blaze3d.platform.NativeImage
import dev.wyz.clientcore.compat.McId
import dev.wyz.clientcore.compat.riverBlit
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.renderer.texture.DynamicTexture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The menu background: a slow slideshow of screenshots, each drifting in and zooming a little,
 * crossfading into the next. Used by the main menu and by River's screens outside a world.
 *
 * The slides are JPEGs (a fifth of the size as PNG), which NativeImage cannot read, so each is
 * decoded with ImageIO and handed over as PNG bytes on a background thread. Only two are ever
 * in memory: the one showing and the next; the one before is freed as soon as it has faded out.
 */
object MenuSlides {
    private const val COUNT = 20
    private const val SLIDE_MS = 12_000L
    private const val FADE_MS = 1_600L

    private class Slide(val id: McId, val w: Int, val h: Int, var shownAt: Long = 0L)

    private val loader = Executors.newSingleThreadExecutor { r -> Thread(r, "River menu slides").apply { isDaemon = true } }

    /** A different order, and a different first picture, every time the game starts. */
    private val order: List<Int> = (0 until COUNT).shuffled()
    private var position = 0
    private var current: Slide? = null
    private var next: Slide? = null
    private var pending: CompletableFuture<NativeImage?>? = null
    private var textures = 0

    private fun path(index: Int) = "/assets/clientcore/textures/menu/slide_%02d.jpg".format(index)

    private fun decode(index: Int): NativeImage? = runCatching {
        val stream = MenuSlides::class.java.getResourceAsStream(path(index)) ?: return@runCatching null
        val image = stream.use { javax.imageio.ImageIO.read(it) } ?: return@runCatching null
        val png = java.io.ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", png)
        NativeImage.read(java.io.ByteArrayInputStream(png.toByteArray()))
    }.getOrNull()

    private fun register(image: NativeImage): Slide {
        val name = "river_menu_slide_${textures++}"
        val id = McId.fromNamespaceAndPath("clientcore", name)
//? if >=1.21.5 {
        Minecraft.getInstance().textureManager.register(id, DynamicTexture({ name }, image))
//?} else {
/*        Minecraft.getInstance().textureManager.register(id, DynamicTexture(image))
*///?}
        return Slide(id, image.width, image.height)
    }

    private fun release(slide: Slide?) {
        if (slide != null) runCatching { Minecraft.getInstance().textureManager.release(slide.id) }
    }

    private fun loadNext() {
        if (pending != null || next != null) return
        val index = order[(position + 1) % order.size]
        pending = CompletableFuture.supplyAsync({ decode(index) }, loader)
    }

    /** For the hidden screenshot runs: makes the picture showing [ms] older than it is. */
    fun ageForDevShot(ms: Long) {
        current?.let { it.shownAt = dev.wyz.clientcore.dev.DevClock.millis() - ms }
    }

    /** Draws the slideshow over the whole screen; false when no slide could be loaded at all. */
    fun draw(g: GuiGraphics, w: Int, h: Int): Boolean {
        val now = dev.wyz.clientcore.dev.DevClock.millis()
        if (current == null) {
            // The very first one is read right here so the menu never opens on an empty screen.
            current = decode(order[position])?.let(::register)?.also { it.shownAt = now } ?: return false
        }
        pending?.let { job ->
            if (job.isDone) {
                pending = null
                job.getNow(null)?.let { next = register(it) }
            }
        }
        val shown = current!!
        val age = now - shown.shownAt
        if (age > SLIDE_MS - FADE_MS * 3) loadNext()
        blit(g, shown, w, h, now, 1f)
        val incoming = next
        if (incoming != null && age > SLIDE_MS - FADE_MS) {
            if (incoming.shownAt == 0L) incoming.shownAt = now
            val fade = ((now - incoming.shownAt) / FADE_MS.toFloat()).coerceIn(0f, 1f)
            blit(g, incoming, w, h, now, fade)
            if (fade >= 1f) {
                release(shown)
                current = incoming
                next = null
                position = (position + 1) % order.size
            }
        }
        return true
    }

    /** One slide, cover-fit to the screen, zooming in slowly and drifting one way or the other. */
    private fun blit(g: GuiGraphics, slide: Slide, w: Int, h: Int, now: Long, alpha: Float) {
        val p = ((now - slide.shownAt) / (SLIDE_MS + FADE_MS).toFloat()).coerceIn(0f, 1f)
        val zoom = 1.06f + 0.08f * p
        var srcW = slide.w / zoom
        var srcH = slide.h / zoom
        val aspect = w.toFloat() / h.toFloat()
        if (srcW / srcH > aspect) srcW = srcH * aspect else srcH = srcW / aspect
        val spareX = slide.w - srcW
        val spareY = slide.h - srcH
        val leftToRight = slide.id.path.hashCode() % 2 == 0
        val u = spareX * (if (leftToRight) 0.3f + 0.4f * p else 0.7f - 0.4f * p)
        val v = spareY * 0.5f
        val tint = ((alpha * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        g.riverBlit(slide.id, 0, 0, u, v, w, h, srcW.roundToInt(), srcH.roundToInt(), slide.w, slide.h, tint)
    }
}
