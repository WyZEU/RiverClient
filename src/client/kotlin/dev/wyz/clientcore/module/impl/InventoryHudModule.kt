package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.HudStack
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.scores.DisplaySlot
import kotlin.math.roundToInt

class InventoryHudModule : Module("inventory_hud", "Inventory HUD", "Live preview of your inventory", ModuleCategory.HUD, "grid", 8, 372, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override val hudStack: HudStack = HudStack.BOTTOM_LEFT

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Inventory"))
        list.add(BoolSetting("Show title", { mutableInventoryHud().showTitle }, { mutableInventoryHud().showTitle = it }))
        list.add(IntSetting("Slot scale", 80, 160, { mutableInventoryHud().slotScalePercent }, { mutableInventoryHud().slotScalePercent = it }, "%"))
        /*
          This module draws the shared panel background but is not stylable, because that
          section only applies to text panels, so the background it drew could never be
          switched off. The toggle is added here rather than by making the whole module
          stylable, which would bring in padding, borders and line spacing that a grid of
          item slots has no use for.
        */
        list.add(BoolSetting("Background", { mutableStyle().showBackground }, { mutableStyle().showBackground = it }))
        list.add(BoolSetting("Slot backgrounds", { mutableInventoryHud().showSlotBackgrounds }, { mutableInventoryHud().showSlotBackgrounds = it }))
        list.add(BoolSetting("Compact counts", { mutableInventoryHud().compactCountText }, { mutableInventoryHud().compactCountText = it }))
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val player = client.player ?: return
        drawInventoryGrid(
            client,
            graphics,
            (9 until 36).map { player.inventory.getItem(it) },
            tickDelta
        )
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        drawInventoryGrid(client, graphics, previewStacks(), tickDelta)
    }

    override fun editorApproximateSize(client: Minecraft): Pair<Int, Int> {
        val style = effectiveStyle()
        val settings = effectiveInventoryHud()
        val slot = slotSize(settings)
        val titleH = if (settings.showTitle) client.font.lineHeight + 4 else 0
        val padding = style.padding
        val width = padding * 2 + slot * 9
        val height = padding * 2 + slot * 3 + titleH
        val scale = scaleFactor()
        return Pair((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
    }

    private fun drawInventoryGrid(client: Minecraft, graphics: GuiGraphics, stacks: List<ItemStack>, tickDelta: Float) {
        val font = client.font
        val style = effectiveStyle()
        val settings = effectiveInventoryHud()
        val padding = style.padding
        val slot = slotSize(settings)
        val titleH = if (settings.showTitle) font.lineHeight + 4 else 0
        val boxW = padding * 2 + slot * 9
        val boxH = padding * 2 + slot * 3 + titleH
        val bt = if (style.showBorder) style.borderThickness.coerceIn(1, 4) else 0
        val outerX = x - bt
        val outerY = y - bt

        if (style.showBackground) {
            ClientUi.fillRounded(graphics, outerX + bt, outerY + bt, boxW, boxH, 7, (style.backgroundOpacity.coerceIn(0, 255) shl 24) or 0x000D1624)
        }
        if (style.showBorder && bt > 0) {
            ClientUi.drawRoundedBorder(graphics, outerX, outerY, boxW + bt * 2, boxH + bt * 2, 7 + bt, (style.borderOpacity.coerceIn(0, 255) shl 24) or 0x0088A2FF)
        }

        if (settings.showTitle) {
            graphics.drawString(font, "Inventory", x + padding, y + padding - 1, 0xFFEAEAEA.toInt(), style.textShadow)
        }

        val startX = x + padding
        val startY = y + padding + titleH
        stacks.chunked(9).forEachIndexed { row, rowStacks ->
            rowStacks.forEachIndexed { col, stack ->
                val sx = startX + col * slot
                val sy = startY + row * slot
                if (settings.showSlotBackgrounds) {
                    ClientUi.fillRounded(graphics, sx, sy, slot, slot, 4, 0xAA11161F.toInt())
                }
                if (!stack.isEmpty) {
                    val itemX = sx + ((slot - 16) / 2).coerceAtLeast(0)
                    val itemY = sy + ((slot - 16) / 2).coerceAtLeast(0)
                    graphics.renderItem(stack, itemX, itemY)
                    if (settings.compactCountText && stack.count > 1) {
                        val count = stack.count.toString()
                        graphics.drawString(font, count, sx + slot - 2 - font.width(count), sy + slot - font.lineHeight, 0xFFEAEAEA.toInt(), style.textShadow)
                    } else {
                        graphics.renderItemDecorations(font, stack, itemX, itemY)
                    }
                }
            }
        }
    }

    private fun slotSize(settings: dev.wyz.clientcore.config.InventoryHudSettings): Int {
        return slotSizeForBudget(Minecraft.getInstance(), settings)
    }

    /**
     * How wide a slot renders, after making room for a sidebar scoreboard if one is
     * showing.
     *
     * A 9-slot grid at the user's chosen scale can be wider than what is left after the
     * scoreboard takes the right edge on a narrow window or high GUI scale, and the two
     * ended up meeting in the middle. This shrinks the slot size just enough to fit -
     * never enlarges - so the whole grid stays visible instead of getting clipped.
     *
     * Deliberately uses a conservative fraction of screen width for the sidebar rather
     * than reading its exact bounds: those depend on the objective's own font layout,
     * only exist during vanilla's own render pass, and their calculation has drifted
     * between Minecraft versions before. Reserving too much is a smaller HUD; drifting
     * off the exact bounds after a version bump is silent overlap.
     */
    private fun slotSizeForBudget(client: Minecraft, settings: dev.wyz.clientcore.config.InventoryHudSettings): Int {
        /*
          The clamp used to be 16..28, but the slider runs 80..160% over a base of 18px,
          which is 14.4 to 28.8. Everything from 80 to 89 clamped to 16 and everything from
          156 to 160 clamped to 28, so both ends of the slider did nothing and the control
          felt broken. The bounds now match what the range can actually produce, and it
          rounds rather than truncating so each step of the slider moves the size.
        */
        val base = (18f * (settings.slotScalePercent.coerceIn(80, 160) / 100f)).roundToInt().coerceIn(14, 29)
        val screenWidth = client.window.guiScaledWidth
        if (screenWidth <= 0) return base

        // Give the scoreboard a generous chunk of the right side when it is visible.
        // ~30% of screen width covers the objectives found on the servers the user is
        // most likely to play on, with the small floor keeping this sane on tiny windows.
        val hasSidebar = runCatching {
            client.level?.scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR) != null
        }.getOrDefault(false)
        val leftMargin = x
        val available = if (hasSidebar) (screenWidth * 0.7f - leftMargin).toInt() else screenWidth - leftMargin
        if (available <= 0) return base

        // slot * 9 + padding * 2 must fit in [available].
        val padding = effectiveStyle().padding
        val slotBudget = (available - padding * 2) / 9
        return slotBudget.coerceIn(10, base)
    }

    /*
      Sample contents for the HUD editor, which can be opened from the main menu with no
      player in the world.

      No shield in here on purpose. Drawing an item runs every mod that hooks item model
      rendering, and at least one of those - Shield Status - asks the player whether the
      shield is on cooldown without checking a player exists, which throws when there
      isn't one. River cannot fix somebody else's null check, but it can decline to be the
      thing that fires it, and nothing about the preview needs that particular item.
    */
    private fun previewStacks(): List<ItemStack> {
        return listOf(
            ItemStack(Items.GOLDEN_APPLE, 8),
            ItemStack(Items.ENDER_PEARL, 16),
            ItemStack(Items.TOTEM_OF_UNDYING, 2),
            ItemStack(Items.SPLASH_POTION),
            ItemStack(Items.COBWEB, 12),
            ItemStack(Items.OBSIDIAN, 32),
            ItemStack(Items.GOLDEN_CARROT, 24),
            ItemStack(Items.WATER_BUCKET),
            ItemStack(Items.COOKED_BEEF, 21),
            ItemStack(Items.BOW),
            ItemStack(Items.ARROW, 48),
            ItemStack(Items.MACE),
            ItemStack(Items.CHORUS_FRUIT, 6),
            ItemStack(Items.WIND_CHARGE, 5),
            ItemStack(Items.EXPERIENCE_BOTTLE, 16),
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            ItemStack(Items.DIAMOND_HELMET),
            ItemStack(Items.DIAMOND_CHESTPLATE),
            ItemStack(Items.DIAMOND_LEGGINGS),
            ItemStack(Items.DIAMOND_BOOTS),
            ItemStack(Items.CROSSBOW),
            ItemStack(Items.TRIDENT),
            ItemStack(Items.ANVIL),
            ItemStack(Items.REDSTONE_BLOCK, 12),
            ItemStack(Items.STRING, 16)
        )
    }
}
