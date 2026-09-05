package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.HudStack
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.ui.ClientUi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=26.2 {
/*import net.minecraft.client.gui.Hud
*///?} else {
import net.minecraft.client.gui.Gui
//?}
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines
//?} else {
/*import net.minecraft.client.renderer.RenderType
*///?}
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import dev.wyz.clientcore.compat.riverBlitSprite

class PotionHudModule : Module("potion_hud", "Potion HUD", "Active effects and timers", ModuleCategory.HUD, "flask", 8, 198, false) {
    override val hudStack: HudStack = HudStack.TOP_RIGHT

    private companion object {
        const val CELL = 24
        const val CELL_GAP = 3
        const val MODE_ICONS = "icons"
        const val MODE_TEXT = "text"
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Potion HUD"))
        list.add(ChoiceSetting("Style", listOf(MODE_ICONS, MODE_TEXT), { mutablePotionHud().displayMode }, { mutablePotionHud().displayMode = it }))
        list.add(BoolSetting("Show timer", { mutablePotionHud().showTimer }, { mutablePotionHud().showTimer = it }))
        list.add(BoolSetting("Show level", { mutablePotionHud().showAmplifier }, { mutablePotionHud().showAmplifier = it }))
        list.add(BoolSetting("Hide vanilla icons", { mutablePotionHud().hideVanilla }, { mutablePotionHud().hideVanilla = it }))
        list.add(BoolSetting("Blink when expiring", { mutablePotionHud().blinkExpiring }, { mutablePotionHud().blinkExpiring = it }))
    }

    /** Consulted by the Gui mixin to cancel vanilla's top-right effect icons. */
    fun hideVanillaEffects(): Boolean = active && effectivePotionHud().hideVanilla

    /**
     * Blink phase for an effect with [seconds] left: 255 = fully visible.
     * Starts pulsing under 10s and speeds up as it gets closer to running out.
     */
    private fun blinkAlpha(seconds: Int): Int {
        if (!effectivePotionHud().blinkExpiring || seconds < 0 || seconds > 10) return 255
        val period = when {
            seconds <= 2 -> 220L
            seconds <= 5 -> 420L
            else -> 800L
        }
        val on = (System.currentTimeMillis() / (period / 2)) % 2 == 0L
        return if (on) 255 else 70
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val player = client.player ?: return
        val effects = player.activeEffects
            .sortedWith(
                compareBy<MobEffectInstance> { !it.effect.value().isBeneficial }
                    .thenByDescending { it.duration }
                    .thenBy { it.effect.value().displayName.string }
            )
        if (effects.isEmpty()) return

        if (effectivePotionHud().displayMode == MODE_TEXT) {
            drawPotionPanel(client, graphics, effects.map { effectEntry(it) }, tickDelta)
        } else {
            drawIconRow(client, graphics, effects)
        }
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        if (effectivePotionHud().displayMode == MODE_TEXT) {
            drawPotionPanel(client, graphics, listOf(
                PotionEntry("Speed II", "1:23", true),
                PotionEntry("Strength II", "0:38", true),
                PotionEntry("Weakness", "0:21", false)
            ), tickDelta)
        } else {
            drawIconCells(client, graphics, listOf(
//? if >=1.21.6 {
//? if >=26.2 {
/*                IconCell(runCatching { Hud.getMobEffectSprite(MobEffects.STRENGTH) }.getOrNull(), "II", "1:04", true, name = "Strength II"),
*///?} else {
                IconCell(runCatching { Gui.getMobEffectSprite(MobEffects.STRENGTH) }.getOrNull(), "II", "1:04", true, name = "Strength II"),
//?}
//? if >=26.2 {
/*                IconCell(runCatching { Hud.getMobEffectSprite(MobEffects.SPEED) }.getOrNull(), "", "7:34", true, name = "Speed"),
*///?} else {
                IconCell(runCatching { Gui.getMobEffectSprite(MobEffects.SPEED) }.getOrNull(), "", "7:34", true, name = "Speed"),
//?}
//? if >=26.2 {
/*                IconCell(runCatching { Hud.getMobEffectSprite(MobEffects.FIRE_RESISTANCE) }.getOrNull(), "", "7:34", true, name = "Fire Resistance")
*///?} else {
                IconCell(runCatching { Gui.getMobEffectSprite(MobEffects.FIRE_RESISTANCE) }.getOrNull(), "", "7:34", true, name = "Fire Resistance")
//?}
//?} elif >=1.21.5 {
/*                // 1.21.5 renamed these two effects but does not yet have the sprite lookup
                // the branch above uses, so it takes the new names down the older path.
                IconCell(spriteFor(MobEffects.STRENGTH), "II", "1:04", true, name = "Strength II"),
                IconCell(spriteFor(MobEffects.SPEED), "", "7:34", true, name = "Speed"),
                IconCell(spriteFor(MobEffects.FIRE_RESISTANCE), "", "7:34", true, name = "Fire Resistance")
*///?} else {
/*                IconCell(spriteFor(MobEffects.DAMAGE_BOOST), "II", "1:04", true, name = "Strength II"),
                IconCell(spriteFor(MobEffects.MOVEMENT_SPEED), "", "7:34", true, name = "Speed"),
                IconCell(spriteFor(MobEffects.FIRE_RESISTANCE), "", "7:34", true, name = "Fire Resistance")
*///?}
            ))
        }
    }

    override fun editorApproximateSize(client: Minecraft): Pair<Int, Int> {
        if (effectivePotionHud().displayMode == MODE_TEXT) {
            return super.editorApproximateSize(client)
        }
        // Vertical list: three sample rows, icon + widest sample name.
        val icon = 18
        val rowH = icon + 2
        val rows = 3
        val w = icon + 5 + client.font.width("Fire Resistance")
        val h = rows * (rowH + 1) - 1
        return Pair(w, h)
    }

    // ---------------------------------------------------------------- icons mode

//? if >=1.21.6 {
//?} else {
/*    // 1.21.4's mob-effect textures come as a baked atlas sprite, not a ResourceLocation.
    private fun spriteFor(effect: net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>) =
        runCatching { Minecraft.getInstance().mobEffectTextures.get(effect) }.getOrNull()

*///?}
    private data class IconCell(
//? if >=1.21.6 {
        val sprite: dev.wyz.clientcore.compat.McId?,
//?} else {
/*        val sprite: net.minecraft.client.renderer.texture.TextureAtlasSprite?,
*///?}
        val amplifier: String,
        val time: String,
        val beneficial: Boolean,
        /** Remaining seconds, or -1 for infinite/preview cells. */
        val seconds: Int = -1,
        /** Effect display name (with level), for the vertical list layout. */
        val name: String = ""
    )

    private fun drawIconRow(client: Minecraft, graphics: GuiGraphics, effects: List<MobEffectInstance>) {
        val settings = effectivePotionHud()
        val cells = effects.take(8).map { effect ->
            val seconds = effect.duration / 20
            val roman = romanAmplifier(effect.amplifier)
            val base = effect.effect.value().displayName.string
            IconCell(
//? if >=1.21.6 {
//? if >=26.2 {
/*                sprite = Hud.getMobEffectSprite(effect.effect),
*///?} else {
                sprite = Gui.getMobEffectSprite(effect.effect),
//?}
//?} else {
/*                sprite = spriteFor(effect.effect),
*///?}
                amplifier = if (settings.showAmplifier && effect.amplifier > 0) (effect.amplifier + 1).toString() else "",
                time = if (effect.isInfiniteDuration) "∞" else formatDuration(seconds),
                beneficial = effect.effect.value().isBeneficial,
                seconds = if (effect.isInfiniteDuration) -1 else seconds,
                name = if (settings.showAmplifier && roman.isNotEmpty()) "$base $roman" else base
            )
        }
        drawIconCells(client, graphics, cells)
    }

    /**
     * Vertical list, reference-style: each effect is a row - icon on the left, effect
     * name on top, timer below in muted text. No boxes; the icon carries the colour.
     */
    private fun drawIconCells(client: Minecraft, graphics: GuiGraphics, cells: List<IconCell>) {
        if (cells.isEmpty()) return
        val settings = effectivePotionHud()
        val font = client.font
        val icon = 18
        val gap = 5
        val rowH = icon + 2
        cells.forEachIndexed { i, cell ->
            val ry = y + i * (rowH + 1)
            val blink = blinkAlpha(cell.seconds)

            if (cell.sprite != null) {
//? if >=1.21.6 {
                graphics.riverBlitSprite(cell.sprite, x, ry, icon, icon, (blink shl 24) or 0xFFFFFF)
//?} else {
/*                graphics.riverBlitSprite(cell.sprite, x, ry, icon, icon, (blink shl 24) or 0xFFFFFF)
*///?}
            } else {
                RiverIconsPlaceholder.draw(graphics, x, ry)
            }

            val tx = x + icon + gap
            // Name on top; harmful effects tinted red so they read as a warning.
            val nameColor = if (cell.beneficial) 0xFFFFFFFF.toInt() else 0xFFFF8F8F.toInt()
            graphics.drawString(font, cell.name, tx, ry, ClientUi.alpha(nameColor, (blink / 255f).coerceAtLeast(0.4f)), true)

            if (settings.showTimer) {
                val lowTime = cell.seconds in 0..15
                val base = if (lowTime) 0xFFFFC46B.toInt() else 0xFF97A0B5.toInt()
                val timeColor = ClientUi.alpha(base, (blink / 255f).coerceAtLeast(0.4f))
                graphics.drawString(font, cell.time, tx, ry + font.lineHeight + 1, timeColor, true)
            }
        }
    }

    /** Neutral swirl stand-in for editor previews outside a world. */
    private object RiverIconsPlaceholder {
        fun draw(graphics: GuiGraphics, x: Int, y: Int) {
            ClientUi.fillRounded(graphics, x + 2, y + 2, 14, 14, 7, 0xFF3A4266.toInt())
            ClientUi.fillRounded(graphics, x + 6, y + 6, 6, 6, 3, 0xFF8B96D8.toInt())
        }
    }

    private fun effectEntry(effect: MobEffectInstance): PotionEntry {
        val name = effect.effect.value().displayName.string
        val amplifier = romanAmplifier(effect.amplifier)
        val duration = formatDuration(effect.duration / 20)
        val title = buildString {
            append(name)
            if (amplifier.isNotEmpty()) {
                append(' ')
                append(amplifier)
            }
        }
        return PotionEntry(title, duration, effect.effect.value().isBeneficial)
    }

    private fun romanAmplifier(level: Int): String {
        return when (level + 1) {
            1 -> ""
            2 -> "II"
            3 -> "III"
            4 -> "IV"
            5 -> "V"
            6 -> "VI"
            7 -> "VII"
            8 -> "VIII"
            9 -> "IX"
            10 -> "X"
            else -> (level + 1).toString()
        }
    }

    private fun formatDuration(totalSeconds: Int): String {
        val clamped = totalSeconds.coerceAtLeast(0)
        val minutes = clamped / 60
        val seconds = clamped % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun drawPotionPanel(client: Minecraft, graphics: GuiGraphics, entries: List<PotionEntry>, tickDelta: Float) {
        if (entries.isEmpty()) return
        val font = client.font
        val st = effectiveStyle()
        val padX = st.padding
        val padY = st.padding
        val lineH = font.lineHeight + 3 + st.spacing
        val timeGap = 10
        val nameW = entries.maxOf { font.width(it.name) }
        val timeW = entries.maxOf { font.width(it.time) }
        val innerW = padX * 2 + nameW + timeGap + timeW
        val innerH = padY * 2 + entries.size * lineH - 3
        val bt = if (st.showBorder) st.borderThickness.coerceIn(1, 4) else 0
        val w = innerW + bt * 2
        val h = innerH + bt * 2
        val ox = x - bt
        val oy = y - bt

        if (st.showBackground) {
            val a = st.backgroundOpacity.coerceIn(0, 255)
            ClientUi.fillRounded(graphics, ox + bt, oy + bt, w - bt * 2, h - bt * 2, 7, (a shl 24) or 0x000D1624)
            ClientUi.fillRoundedGradient(graphics, ox + bt, oy + bt, w - bt * 2, ((h - bt * 2) / 2).coerceAtLeast(1), 7, ClientUi.alpha(ClientUi.ACCENT_A, 0.08f), 0x00000000)
        }

        if (st.showBorder && bt > 0) {
            val bo = st.borderOpacity.coerceIn(0, 255)
            ClientUi.drawRoundedBorder(graphics, ox, oy, w, h, 7 + bt, (bo shl 24) or 0x0088A2FF)
        }

        drawEntries(graphics, font, entries, padX, padY, lineH, nameW, timeGap, st.textShadow)
    }

    private fun drawEntries(
        graphics: GuiGraphics,
        font: Font,
        entries: List<PotionEntry>,
        padX: Int,
        padY: Int,
        lineH: Int,
        nameW: Int,
        timeGap: Int,
        shadow: Boolean
    ) {
        entries.forEachIndexed { index, entry ->
            val ly = y + padY + index * lineH
            val lx = x + padX
            val nameColor = when {
                entry.placeholder -> 0xFF9CA3AF.toInt()
                entry.beneficial -> 0xFFEAEAEA.toInt()
                else -> 0xFFFFC7C7.toInt()
            }
            val timeColor = when {
                entry.placeholder -> 0x00000000
                entry.beneficial -> 0xFFA5B4FC.toInt()
                else -> 0xFFFCA5A5.toInt()
            }
            graphics.drawString(font, entry.name, lx, ly, nameColor, shadow)
            if (entry.time.isNotEmpty()) {
                graphics.drawString(font, entry.time, lx + nameW + timeGap, ly, timeColor, shadow)
            }
        }
    }

    private data class PotionEntry(
        val name: String,
        val time: String,
        val beneficial: Boolean,
        val placeholder: Boolean = false
    )
}
