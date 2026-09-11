package dev.wyz.clientcore.ui.tooltip

import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}

/**
 * Draws a container's contents inside the tooltip itself.
 *
 * Being part of the tooltip rather than a panel floating beside it is the whole point: the
 * tooltip already knows how to keep itself on screen, above other windows, and next to the
 * item the contents belong to. It also means anything else in the tooltip - lore, a server
 * plugin's lines - is laid out around this rather than underneath it, because the width
 * and height reported here are reserved before a single line is drawn.
 */
class ShulkerTooltipComponent(private val data: ShulkerTooltipData) : ClientTooltipComponent {

    private companion object {
        /** 16px item plus a pixel of air each side. */
        const val CELL = 18
        const val COLUMNS = 9
        const val PADDING = 1
    }

    /** Cell to cell, so spacing pushes the next one along rather than shrinking this one. */
    private val pitch: Int get() = CELL + data.spacing.coerceIn(0, 4)

    private val shown: Int get() = minOf(data.items.size, COLUMNS * data.maxRows)
    private val columns: Int get() = if (shown <= 0) 1 else minOf(COLUMNS, shown)
    private val rows: Int get() = if (shown <= 0) 1 else (shown + COLUMNS - 1) / COLUMNS

    private val scale: Float get() = data.scale.coerceIn(0.5f, 1f)

    /*
      The reserved size is the scaled size, so the tooltip lays its other lines out around
      what is actually drawn. Reporting the unscaled size would leave a band of empty
      tooltip under a shrunken grid.
    */
    // The last cell contributes no trailing gap, so the panel still ends on the grid.
    private val rawWidth: Int get() = columns * pitch - data.spacing.coerceIn(0, 4) + PADDING * 2
    private val rawHeight: Int get() = rows * pitch - data.spacing.coerceIn(0, 4) + PADDING * 2

    override fun getWidth(font: Font): Int = Math.round(rawWidth * scale)

    override fun getHeight(font: Font): Int = Math.round(rawHeight * scale)

//? if >=26.1 {
/*    override fun extractImage(font: Font, x: Int, y: Int, width: Int, height: Int, graphics: GuiGraphics) {
        draw(font, x, y, graphics)
    }
*///?} else {
    override fun renderImage(font: Font, x: Int, y: Int, width: Int, height: Int, graphics: GuiGraphics) {
        draw(font, x, y, graphics)
    }
//?}

    private fun draw(font: Font, x: Int, y: Int, graphics: GuiGraphics) {
        if (scale == 1f) {
            drawGrid(font, x, y, graphics)
            return
        }
        // Scaled about the top left corner, which is where the tooltip placed us.
        val pose = graphics.pose()
//? if >=1.21.6 {
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(scale, scale)
        pose.translate(-x.toFloat(), -y.toFloat())
//?} else {
/*        pose.pushPose()
        pose.translate(x.toFloat(), y.toFloat(), 0f)
        pose.scale(scale, scale, 1f)
        pose.translate(-x.toFloat(), -y.toFloat(), 0f)
*///?}
        drawGrid(font, x, y, graphics)
//? if >=1.21.6 {
        pose.popMatrix()
//?} else {
/*        pose.popPose()
*///?}
    }

    private fun drawGrid(font: Font, x: Int, y: Int, graphics: GuiGraphics) {
        val w = rawWidth
        val h = rawHeight

        // Tinted to the dye the shulker was made with, so the box you are after is
        // recognisable before you have read a single item in it.
        if (data.tinted) {
            ClientUi.fillRounded(graphics, x, y, w, h, 3, (0x66 shl 24) or (data.tint and 0xFFFFFF))
        }

        for ((index, item) in data.items.take(shown).withIndex()) {
            val cellX = x + PADDING + (index % COLUMNS) * pitch
            val cellY = y + PADDING + (index / COLUMNS) * pitch
            ClientUi.fillRounded(graphics, cellX, cellY, CELL, CELL, 2, 0x55000000)
            // The item is 16 wide in an 18 cell, so one pixel of air on every side.
            graphics.renderItem(item, cellX + 1, cellY + 1)
            if (data.counts) graphics.renderItemDecorations(font, item, cellX + 1, cellY + 1)
        }
    }
}
