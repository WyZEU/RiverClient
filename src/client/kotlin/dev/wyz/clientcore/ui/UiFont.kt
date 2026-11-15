package dev.wyz.clientcore.ui

import dev.wyz.clientcore.compat.McId
import dev.wyz.clientcore.mixin.FontAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * River's interface typeface (Inter, assets/clientcore/font/ui.json).
 *
 * A second Font built on the game's own glyph source, which answers the default font with
 * River's and passes every other font (lyrics, badges, a server's custom ones) through. A
 * screen that draws with it gets smooth text everywhere, and its widths, wrapping and
 * truncation all agree with what is drawn, because it is a real Font and not a style patch.
 *
 * The Minecraft interface styles keep the game's pixel font, and so does anything drawn
 * over the world (HUD modules, nametags): they never see this object.
 */
object UiFont {
    private val ID: McId = McId.fromNamespaceAndPath("clientcore", "ui")

    private val SEMIBOLD_ID: McId = McId.fromNamespaceAndPath("clientcore", "ui_semibold")

    private var base: Font? = null
    private var smooth: Font? = null

    /** True for River's smooth font, where bold is drawn with a real semibold face instead of the game's doubled glyphs. */
    @JvmStatic
    fun isSmooth(font: Font): Boolean = font === smooth

    /** The style for semibold text in the smooth font. */
    val semibold: net.minecraft.network.chat.Style by lazy {
//? if >=1.21.9 {
        net.minecraft.network.chat.Style.EMPTY.withFont(net.minecraft.network.chat.FontDescription.Resource(SEMIBOLD_ID))
//?} else {
        /*net.minecraft.network.chat.Style.EMPTY.withFont(SEMIBOLD_ID)
*///?}
    }

    /** The font a River screen draws with: smooth on the River styles, the game's on the Minecraft ones. */
    @JvmStatic
    fun forScreen(gameFont: Font): Font {
        val style = dev.wyz.clientcore.RiverRuntime.configOrNull()?.interfaceStyle ?: "river-dark"
        if (style.startsWith("minecraft")) return gameFont
        return smooth(gameFont)
    }

    /** Points the open River screen at the font for the current interface style. Cheap: both fonts are cached. */
    fun applyToOpenScreen() {
        val client = Minecraft.getInstance()
        val screen = client.screen ?: return
        if (!screen.javaClass.name.startsWith("dev.wyz.clientcore.ui.screen.")) return
        val access = screen as dev.wyz.clientcore.mixin.ScreenFontAccessor
        val want = forScreen(client.font)
        if (access.`clientcore$font`() !== want) access.`clientcore$setFont`(want)
    }

    fun smooth(gameFont: Font = Minecraft.getInstance().font): Font {
        val cached = smooth
        if (cached != null && base === gameFont) return cached
        val built = runCatching { build(gameFont) }.getOrNull() ?: return gameFont
        base = gameFont
        smooth = built
        return built
    }

    private fun build(gameFont: Font): Font {
        val access = gameFont as FontAccessor
//? if >=1.21.9 {
        val source = access.`clientcore$provider`()
        val ui = net.minecraft.network.chat.FontDescription.Resource(ID)
        return Font(object : Font.Provider {
            override fun glyphs(description: net.minecraft.network.chat.FontDescription) =
                source.glyphs(if (description == net.minecraft.network.chat.FontDescription.DEFAULT) ui else description)

            override fun effect() = source.effect()
        })
//?} else {
        /*val fonts = access.`clientcore$fonts`()
        val default = net.minecraft.network.chat.Style.DEFAULT_FONT
        return Font({ id -> fonts.apply(if (id == default) ID else id) }, access.`clientcore$filterFishyGlyphs`())
*///?}
    }
}
