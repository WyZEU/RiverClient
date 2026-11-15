package dev.wyz.clientcore.compat

/*
  26.1 split GUI drawing into two phases: a screen no longer paints, it extracts a
  render state that the renderer consumes afterwards. The object carrying that state is
  GuiGraphicsExtractor, and the calls on it were renamed to suit the new reading -
  drawString became text, renderItem became item. Everything else River touches on it
  (blit, blitSprite, pose, fill, fillGradient, the scissor pair, guiWidth) kept its
  exact signature.

  Forking that across the 33 files that draw something would have put a version
  conditional on almost every screen in the mod, which is the opposite of the point of
  keeping one source tree. Instead the type gets one name here and the renamed calls are
  re-exposed under the old ones, so a call site reads the same on either generation and
  only its import line differs.
*/

//? if >=26.1 {
/*import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack

typealias GuiGraphics = net.minecraft.client.gui.GuiGraphicsExtractor

fun GuiGraphics.drawString(font: Font, s: String, x: Int, y: Int, color: Int) =
    text(font, s, x, y, color)
fun GuiGraphics.drawString(font: Font, s: String, x: Int, y: Int, color: Int, shadow: Boolean) =
    text(font, s, x, y, color, shadow)
fun GuiGraphics.drawString(font: Font, s: FormattedCharSequence, x: Int, y: Int, color: Int) =
    text(font, s, x, y, color)
fun GuiGraphics.drawString(font: Font, s: FormattedCharSequence, x: Int, y: Int, color: Int, shadow: Boolean) =
    text(font, s, x, y, color, shadow)
fun GuiGraphics.drawString(font: Font, s: Component, x: Int, y: Int, color: Int) =
    text(font, s, x, y, color)
fun GuiGraphics.drawString(font: Font, s: Component, x: Int, y: Int, color: Int, shadow: Boolean) =
    text(font, s, x, y, color, shadow)
fun GuiGraphics.drawStringWithBackdrop(font: Font, s: Component, x: Int, y: Int, width: Int, color: Int) =
    textWithBackdrop(font, s, x, y, width, color)

fun GuiGraphics.renderItem(stack: ItemStack, x: Int, y: Int) = item(stack, x, y)
fun GuiGraphics.renderItem(stack: ItemStack, x: Int, y: Int, seed: Int) = item(stack, x, y, seed)
fun GuiGraphics.renderItem(entity: LivingEntity, stack: ItemStack, x: Int, y: Int, seed: Int) =
    item(entity, stack, x, y, seed)
fun GuiGraphics.renderItemDecorations(font: Font, stack: ItemStack, x: Int, y: Int) =
    itemDecorations(font, stack, x, y)
fun GuiGraphics.renderItemDecorations(font: Font, stack: ItemStack, x: Int, y: Int, label: String?) =
    itemDecorations(font, stack, x, y, label)
*///?} else {
typealias GuiGraphics = net.minecraft.client.gui.GuiGraphics
//?}

// The deferred-submit pair lost its verb the same way the draw calls did. 26.2 also
// widened the parameters - joml's read-only interfaces instead of the concrete types,
// and a plain Model.Simple where 26.1 still wanted a whole PlayerModel - so the two
// releases need their own signatures even though the call sites do not.
//? if >=26.2 {
/*fun GuiGraphics.submitEntityRenderState(
    state: net.minecraft.client.renderer.entity.state.EntityRenderState,
    scale: Float, translation: org.joml.Vector3fc,
    rotation: org.joml.Quaternionfc, overrideRotation: org.joml.Quaternionfc?,
    x0: Int, y0: Int, x1: Int, y1: Int
) = entity(state, scale, translation, rotation, overrideRotation, x0, y0, x1, y1)

fun GuiGraphics.submitSkinRenderState(
    model: net.minecraft.client.model.Model.Simple,
    texture: net.minecraft.resources.Identifier,
    scale: Float, rotX: Float, rotY: Float, offsetY: Float,
    x0: Int, y0: Int, x1: Int, y1: Int
) = skin(model, texture, scale, rotX, rotY, offsetY, x0, y0, x1, y1)
*///?} elif >=26.1 {
/*fun GuiGraphics.submitEntityRenderState(
    state: net.minecraft.client.renderer.entity.state.EntityRenderState,
    scale: Float, translation: org.joml.Vector3f,
    rotation: org.joml.Quaternionf, overrideRotation: org.joml.Quaternionf?,
    x0: Int, y0: Int, x1: Int, y1: Int
) = entity(state, scale, translation, rotation, overrideRotation, x0, y0, x1, y1)

fun GuiGraphics.submitSkinRenderState(
    model: net.minecraft.client.model.player.PlayerModel,
    texture: net.minecraft.resources.Identifier,
    scale: Float, rotX: Float, rotY: Float, offsetY: Float,
    x0: Int, y0: Int, x1: Int, y1: Int
) = skin(model, texture, scale, rotX, rotY, offsetY, x0, y0, x1, y1)
*///?}
