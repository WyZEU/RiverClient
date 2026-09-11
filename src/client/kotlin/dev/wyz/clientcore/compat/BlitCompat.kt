package dev.wyz.clientcore.compat

/*
  One spelling of "draw a texture" across every supported version.

  The call has been rewritten twice. 1.21.2 put a render type in front of it, 1.21.6
  replaced that with a render pipeline, and before either of those the argument order was
  different and blit could not tint at all - the tint had to be set on the graphics object
  around the call instead. Spreading that across the dozen places River draws a texture
  meant a version conditional at every one of them, so it lives here instead.

  Argument order is the modern one throughout: destination, then source offset, then
  source size, then texture size. The older call takes destination size before the source
  offset, which is easy to get backwards - except that u and v are floats and width and
  height are ints, so swapping them fails to compile rather than quietly drawing the
  wrong part of the texture.
*/

// GuiGraphics is the typealias from GuiGraphicsCompat in this same package, which is
// GuiGraphicsExtractor from 26.1. Importing the Minecraft class here would break that.
//? if <1.21.6 {
/*import net.minecraft.client.renderer.texture.TextureAtlasSprite
*///?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines
//?} elif >=1.21.2 {
/*import net.minecraft.client.renderer.RenderType
*///?}

/**
 * @param tint ARGB. Opaque white leaves the texture untouched.
 */
fun GuiGraphics.riverBlit(
    texture: McId,
    x: Int,
    y: Int,
    u: Float,
    v: Float,
    width: Int,
    height: Int,
    sourceWidth: Int,
    sourceHeight: Int,
    textureWidth: Int,
    textureHeight: Int,
    tint: Int = -1
) {
//? if >=1.21.6 {
    blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, sourceWidth, sourceHeight, textureWidth, textureHeight, tint)
//?} elif >=1.21.2 {
/*    blit(RenderType::guiTextured, texture, x, y, u, v, width, height, sourceWidth, sourceHeight, textureWidth, textureHeight, tint)
*///?} else {
/*    // No tint parameter on this one, so it is set on the graphics object and put back
    // afterwards - leaving it set would tint everything drawn after this.
    val a = ((tint ushr 24) and 0xFF) / 255f
    val r = ((tint ushr 16) and 0xFF) / 255f
    val g = ((tint ushr 8) and 0xFF) / 255f
    val b = (tint and 0xFF) / 255f
    val tinted = tint != -1
    if (tinted) setColor(r, g, b, a)
    blit(texture, x, y, width, height, u, v, sourceWidth, sourceHeight, textureWidth, textureHeight)
    if (tinted) setColor(1f, 1f, 1f, 1f)
*///?}
}

/**
 * Draws a sprite from the block/item atlas.
 *
 * The sprite itself is identified differently per version - a resource id from 1.21.6,
 * an atlas sprite before that - and the sprite-taking blitSprite overloads are private
 * before 1.21.2, so that generation goes through the plain blit that takes a sprite and
 * separate colour components. The whole function is branched rather than just its body
 * because the parameter type is part of the difference.
 *
 * @param tint ARGB. Opaque white leaves the sprite untouched.
 */
//? if >=1.21.6 {
fun GuiGraphics.riverBlitSprite(sprite: McId, x: Int, y: Int, width: Int, height: Int, tint: Int = -1) {
    blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height, tint)
}
//?} elif >=1.21.2 {
/*fun GuiGraphics.riverBlitSprite(sprite: TextureAtlasSprite, x: Int, y: Int, width: Int, height: Int, tint: Int = -1) {
    blitSprite(RenderType::guiTextured, sprite, x, y, width, height, tint)
}
*///?} else {
/*fun GuiGraphics.riverBlitSprite(sprite: TextureAtlasSprite, x: Int, y: Int, width: Int, height: Int, tint: Int = -1) {
    val a = ((tint ushr 24) and 0xFF) / 255f
    val r = ((tint ushr 16) and 0xFF) / 255f
    val g = ((tint ushr 8) and 0xFF) / 255f
    val b = (tint and 0xFF) / 255f
    blit(x, y, 0, width, height, sprite, r, g, b, a)
}
*///?}
