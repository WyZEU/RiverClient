package dev.wyz.clientcore.ui.tooltip

import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.ItemStack

/**
 * What a container preview needs to draw, handed from the item to the tooltip renderer.
 *
 * TooltipComponent is only a marker - vanilla carries bundle contents the same way.
 * Splitting the data from the drawing is not ceremony: this side is reached from item
 * code that also exists on the server, so it must not touch net.minecraft.client.
 *
 * The settings ride along rather than being read at draw time, because the renderer is
 * built once per tooltip and reading them twice could disagree with itself mid-frame.
 */
class ShulkerTooltipData(
    val items: List<ItemStack>,
    val tint: Int,
    val tinted: Boolean = true,
    val counts: Boolean = true,
    val maxRows: Int = 3,
    val spacing: Int = 0,
    val scale: Float = 1f
) : TooltipComponent
