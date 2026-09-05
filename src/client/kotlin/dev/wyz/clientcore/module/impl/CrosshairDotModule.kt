package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.config.CrosshairSettings
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.world.entity.LivingEntity
import kotlin.math.roundToInt

class CrosshairDotModule : Module("crosshair_dot", "Custom Crosshair", "Crosshair shape, color and presets", ModuleCategory.VISUAL, "crosshair", 0, 0) {

    companion object {
        const val PRESET_CUSTOM = "custom"
        const val PRESET_CLASSIC = "classic"
        const val PRESET_DOT = "dot"
        const val PRESET_TACTICAL = "tactical"

        private val PRESETS = listOf(PRESET_CUSTOM, PRESET_CLASSIC, PRESET_DOT, PRESET_TACTICAL)
    }

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL

    override val comingSoon: Boolean = true

    override fun acceptsDraggablePosition(): Boolean = false

    override fun showPositionControlsInEditor(): Boolean = false

    override fun addModuleSettings(list: MutableList<Setting>) {
        val c = { mutableCrosshair() }
        list.add(SectionSetting("Presets"))
        list.add(ChoiceSetting("Style", PRESETS, { c().normalPreset }, { v -> c().normalPreset = v }))
        list.add(BoolSetting("Swap on target", { c().swapOnTarget }, { c().swapOnTarget = it }))
        list.add(ChoiceSetting("Target style", PRESETS, { c().targetPreset }, { v -> c().targetPreset = v }))
        list.add(BoolSetting("Hide vanilla crosshair", { c().hideVanillaCrosshair }, { c().hideVanillaCrosshair = it }))
        list.add(SectionSetting("Shape"))
        list.add(BoolSetting("Top arm", { c().showTop }, { c().showTop = it }))
        list.add(BoolSetting("Bottom arm", { c().showBottom }, { c().showBottom = it }))
        list.add(BoolSetting("Left arm", { c().showLeft }, { c().showLeft = it }))
        list.add(BoolSetting("Right arm", { c().showRight }, { c().showRight = it }))
        list.add(BoolSetting("Center dot", { c().showCenterDot }, { c().showCenterDot = it }))
        list.add(BoolSetting("Attack gap", { c().dynamicAttackGap }, { c().dynamicAttackGap = it }))
        list.add(IntSetting("Gap", 0, 12, { c().gap }, { c().gap = it }))
        list.add(IntSetting("Length", 1, 14, { c().length }, { c().length = it }))
        list.add(IntSetting("Thickness", 1, 5, { c().thickness }, { c().thickness = it }))
        list.add(IntSetting("Dot size", 1, 6, { c().dotSize }, { c().dotSize = it }))
        list.add(SectionSetting("Color"))
        list.add(ColorSetting("Crosshair", true,
            { argb(c().alpha, c().red, c().green, c().blue) },
            { v -> c().alpha = (v ushr 24) and 0xFF; c().red = (v ushr 16) and 0xFF; c().green = (v ushr 8) and 0xFF; c().blue = v and 0xFF }
        ))
        list.add(BoolSetting("Outline", { c().useOutline }, { c().useOutline = it }))
        list.add(ColorSetting("Outline color", true,
            { argb(c().outlineAlpha, c().outlineRed, c().outlineGreen, c().outlineBlue) },
            { v -> c().outlineAlpha = (v ushr 24) and 0xFF; c().outlineRed = (v ushr 16) and 0xFF; c().outlineGreen = (v ushr 8) and 0xFF; c().outlineBlue = v and 0xFF }
        ))
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
//? if >=26.2 {
/*        if (client.gui.hud.isHidden()) return
*///?} else {
        if (client.options.hideGui) return
//?}
        val settings = effectiveCrosshair()
        val onTarget = settings.swapOnTarget && client.crosshairPickEntity is LivingEntity
        val active = resolveProfile(settings, onTarget)
        val centerX = client.window.guiScaledWidth
        val centerY = client.window.guiScaledHeight

        val attackGap = if (active.dynamicAttackGap) {
            val strength = client.player?.getAttackStrengthScale(0f) ?: 1f
            ((1f - strength.coerceIn(0f, 1f)) * 6f).roundToInt()
        } else 0

        val scale = scaleFactor()
        val gap = (active.gap * scale).roundToInt().coerceAtLeast(0) + attackGap
        val length = (active.length * scale).roundToInt().coerceAtLeast(1)
        val thickness = (active.thickness * scale).roundToInt().coerceAtLeast(1)
        val dotSize = (active.dotSize * scale).roundToInt().coerceAtLeast(1)
        val outlineThickness = active.outlineThickness.coerceAtLeast(0)

        val color = argb(active.alpha, active.red, active.green, active.blue)
        val outlineColor = argb(active.outlineAlpha, active.outlineRed, active.outlineGreen, active.outlineBlue)

        if (active.showCenterDot) {
            val left = centeredStart(centerX, dotSize)
            val top = centeredStart(centerY, dotSize)
            drawRectWithOutline(
                graphics,
                left,
                top,
                dotSize,
                dotSize,
                color,
                active.useOutline,
                outlineThickness,
                outlineColor
            )
        }

        val verticalLeft = centeredStart(centerX, thickness)
        val horizontalTop = centeredStart(centerY, thickness)
        val topAllowed = active.showTop && !active.tShape
        if (topAllowed) {
            drawRectWithOutline(
                graphics,
                verticalLeft,
                centeredStart(centerY, 0) - gap - length,
                thickness,
                length,
                color,
                active.useOutline,
                outlineThickness,
                outlineColor
            )
        }
        if (active.showBottom) {
            drawRectWithOutline(
                graphics,
                verticalLeft,
                centeredStart(centerY, 0) + gap,
                thickness,
                length,
                color,
                active.useOutline,
                outlineThickness,
                outlineColor
            )
        }
        if (active.showLeft) {
            drawRectWithOutline(
                graphics,
                centeredStart(centerX, 0) - gap - length,
                horizontalTop,
                length,
                thickness,
                color,
                active.useOutline,
                outlineThickness,
                outlineColor
            )
        }
        if (active.showRight) {
            drawRectWithOutline(
                graphics,
                centeredStart(centerX, 0) + gap,
                horizontalTop,
                length,
                thickness,
                color,
                active.useOutline,
                outlineThickness,
                outlineColor
            )
        }
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        render(client, graphics, tickDelta)
    }

    private fun drawRectWithOutline(
        graphics: GuiGraphics,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        fillColor: Int,
        outline: Boolean,
        outlineThickness: Int,
        outlineColor: Int
    ) {
        if (outline && outlineThickness > 0) {
            graphics.fill(x - outlineThickness, y - outlineThickness, x + width + outlineThickness, y + height + outlineThickness, outlineColor)
        }
        graphics.fill(x, y, x + width, y + height, fillColor)
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return ((alpha.coerceIn(0, 255) and 0xFF) shl 24) or
            ((red.coerceIn(0, 255) and 0xFF) shl 16) or
            ((green.coerceIn(0, 255) and 0xFF) shl 8) or
            (blue.coerceIn(0, 255) and 0xFF)
    }

    private fun centeredStart(total: Int, size: Int): Int = (total - size) / 2

    private fun resolveProfile(settings: CrosshairSettings, onTarget: Boolean): CrosshairSettings {
        val preset = if (onTarget) settings.targetPreset else settings.normalPreset
        return when (preset.lowercase()) {
            PRESET_CLASSIC -> settings.copy(
                showTop = true,
                showBottom = true,
                showLeft = true,
                showRight = true,
                showCenterDot = false,
                useOutline = true,
                dynamicAttackGap = false,
                tShape = false,
                gap = 4,
                length = 6,
                thickness = 2,
                dotSize = 2,
                outlineThickness = 1
            )
            PRESET_DOT -> settings.copy(
                showTop = false,
                showBottom = false,
                showLeft = false,
                showRight = false,
                showCenterDot = true,
                useOutline = true,
                dynamicAttackGap = false,
                tShape = false,
                gap = 0,
                length = 0,
                thickness = 2,
                dotSize = 3,
                outlineThickness = 1
            )
            PRESET_TACTICAL -> settings.copy(
                showTop = false,
                showBottom = true,
                showLeft = true,
                showRight = true,
                showCenterDot = true,
                useOutline = true,
                dynamicAttackGap = true,
                tShape = true,
                gap = 5,
                length = 7,
                thickness = 2,
                dotSize = 2,
                outlineThickness = 1
            )
            else -> settings
        }
    }
}
