package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.config.ArmorHudSettings
import dev.wyz.clientcore.module.HudStack
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?} else {
import net.minecraft.client.gui.GuiGraphics
//?}
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import kotlin.math.sin

class ArmorHudModule : Module("armor_hud", "Armor HUD", "Equipped armor and durability", ModuleCategory.HUD, "shirt", 8, 140) {

    private companion object {
        // Classic worn-equipment gold - warm, on the ClientUi WARNING hue - with a red for critical pieces.
        const val GOLD = 0xFFFFD46B.toInt()
        const val CRITICAL = 0xFFFF6B6B.toInt()
    }

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.ARMOR
    override val hudStack: HudStack = HudStack.TOP_RIGHT

    private var warnSoundLastGameTime: Long = -1_000_000L
    private var wasArmorLow: Boolean = false

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Armor"))
        list.add(BoolSetting("Horizontal layout", { mutableStyle().layoutHorizontal }, { mutableStyle().layoutHorizontal = it }))
        list.add(IntSetting("Slot size", 14, 40, { mutableArmorHud().slotSize }, { mutableArmorHud().slotSize = it }))
        list.add(IntSetting("Slot spacing", 0, 12, { mutableArmorHud().slotSpacing }, { mutableArmorHud().slotSpacing = it }))
        list.add(IntSetting("Icon scale", 60, 130, { mutableArmorHud().itemIconScalePercent }, { mutableArmorHud().itemIconScalePercent = it }, "%"))
        list.add(BoolSetting("Durability numbers", { mutableArmorHud().showDurabilityNumbers }, { mutableArmorHud().showDurabilityNumbers = it }))
        list.add(ChoiceSetting(
            "Number style",
            listOf("Gold", "Durability"),
            { if (mutableArmorHud().numberStyle == "gold") "Gold" else "Durability" },
            { mutableArmorHud().numberStyle = if (it == "Gold") "gold" else "durability" }
        ))
        list.add(ChoiceSetting(
            "Number side",
            listOf("Left", "Right"),
            { if (mutableArmorHud().durabilityNumbersLeft) "Left" else "Right" },
            { mutableArmorHud().durabilityNumbersLeft = it == "Left" }
        ))
        list.add(BoolSetting("Show empty slots", { mutableArmorHud().showEmptySlots }, { mutableArmorHud().showEmptySlots = it }))
        list.add(SectionSetting("Low durability warning"))
        list.add(BoolSetting("Visual warning", { mutableArmorHud().warnVisual }, { mutableArmorHud().warnVisual = it }))
        list.add(BoolSetting("Warning sound", { mutableArmorHud().warnSound }, { mutableArmorHud().warnSound = it }))
        list.add(IntSetting("Warn below", 5, 45, { mutableArmorHud().warnDurabilityPercent }, { mutableArmorHud().warnDurabilityPercent = it }, "%"))
    }

    override fun resetModuleSettings() {
        super.resetModuleSettings()
        mutableStyle().layoutHorizontal = false
        mutableArmorHud().apply {
            showOuterBackground = false
            showDurability = false
            showDurabilityNumbers = true
            numberStyle = "gold"
            durabilityNumbersLeft = true
            showEmptySlots = false
            slotBackgroundOpacity = 0
            borderOpacity = 0
            borderThickness = 0
            outerBackgroundOpacity = 0
        }
    }

    override fun editorApproximateSize(client: Minecraft): Pair<Int, Int> {
        val ah = effectiveArmorHud()
        val horizontal = effectiveStyle().layoutHorizontal
        val gap = ah.slotSpacing
        val cell = ah.slotSize
        val textW = if (ah.showDurabilityNumbers) 26 else 0
        val rowH = maxOf(cell, client.font.lineHeight)
        val rowW = cell + if (textW > 0) textW + 8 else 0
        val w = if (horizontal) rowW * 4 + gap * 3 else rowW
        val h = if (horizontal) rowH else rowH * 4 + gap * 3
        val s = scaleFactor()
        return Pair((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
    }

    override fun tick(client: Minecraft) {
        if (!active) return
        val player = client.player ?: return
        val level = player.level()
        val ah = effectiveArmorHud()
        if (!ah.warnSound) return

        val threshold = ah.warnDurabilityPercent / 100f
        val slots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        val anyLow = slots.any { slot ->
            val st = player.getItemBySlot(slot)
            durabilityRatio(st)?.let { it <= threshold } == true
        }

        val now = level.gameTime
        val interval = ah.warnSoundIntervalTicks.coerceAtLeast(15)
        if (anyLow) {
            val firstEdge = !wasArmorLow
            val dueRepeat = now - warnSoundLastGameTime >= interval
            if (firstEdge || dueRepeat) {
                client.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BIT, 1.2f))
                warnSoundLastGameTime = now
            }
        }
        wasArmorLow = anyLow
    }

    override fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        val player = client.player ?: return
        renderSlots(
            client,
            graphics,
            listOf(
                player.getItemBySlot(EquipmentSlot.HEAD),
                player.getItemBySlot(EquipmentSlot.CHEST),
                player.getItemBySlot(EquipmentSlot.LEGS),
                player.getItemBySlot(EquipmentSlot.FEET)
            ),
            tickDelta
        )
    }

    override fun renderEditorPreview(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        // Mixed set with one piece missing, closer to what a real inventory looks like.
        val sample = listOf(
            ItemStack(Items.NETHERITE_HELMET),
            ItemStack(Items.DIAMOND_CHESTPLATE),
            ItemStack.EMPTY,
            ItemStack(Items.IRON_BOOTS)
        )
        renderSlots(client, graphics, sample, tickDelta, listOf(92, 81, null, 38))
    }

    private fun renderSlots(
        client: Minecraft,
        graphics: GuiGraphics,
        stacks: List<ItemStack>,
        tickDelta: Float,
        sampleDurability: List<Int?>? = null
    ) {
        val ah = effectiveArmorHud()
        val st = effectiveStyle()
        val horizontal = st.layoutHorizontal
        val gap = ah.slotSpacing
        val cell = ah.slotSize
        val font = client.font
        val rowH = maxOf(cell, font.lineHeight)
        val textW = if (ah.showDurabilityNumbers) 34 else 0
        val rowW = cell + textW
        // When numbers sit on the left, the icon column shifts right to make room.
        val iconOffset = if (ah.durabilityNumbersLeft) textW else 0

        var ox = x
        var oy = y
        val pulse = warnPulse(client, tickDelta)
        val threshold = ah.warnDurabilityPercent / 100f

        val slots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        slots.forEachIndexed { i, slot ->
            val sx = (if (horizontal) ox + i * (rowW + gap) else ox) + iconOffset
            val sy = if (horizontal) oy else oy + i * (rowH + gap)
            val stack = stacks.getOrNull(i) ?: ItemStack.EMPTY
            if (stack.isEmpty && sampleDurability == null && !ah.showEmptySlots) return@forEachIndexed
            val ratio = durabilityRatio(stack)
            val warn = ah.warnVisual && ratio != null && ratio <= threshold
            drawSlot(graphics, font, stack, slot, sx, sy, cell, ah, pulse, warn, sampleDurability?.getOrNull(i))
        }
    }

    private fun durabilityRatio(stack: ItemStack): Float? {
        if (!stack.isDamageableItem) return null
        val max = stack.maxDamage
        if (max <= 0) return null
        return (max - stack.damageValue).toFloat() / max.toFloat()
    }

    private fun warnPulse(client: Minecraft, tickDelta: Float): Float {
        val t = (client.level?.gameTime ?: 0L).toDouble() + tickDelta.toDouble()
        return ((sin(t * 0.15) * 0.5 + 0.5).toFloat()).coerceIn(0f, 1f)
    }

    private fun drawSlot(
        graphics: GuiGraphics,
        font: Font,
        stack: ItemStack,
        slot: EquipmentSlot,
        ax: Int,
        ay: Int,
        size: Int,
        ah: ArmorHudSettings,
        pulse: Float,
        warn: Boolean,
        sampleRemaining: Int? = null
    ) {
        val showItem = stack.isEmpty.not() || ah.showEmptySlots
        if (showItem) {
            val toRender = if (stack.isEmpty) ghostStack(slot) else stack
            val scale = ah.itemIconScalePercent.coerceIn(60, 130) / 100f
            val cx = ax + size / 2
            val cy = ay + size / 2
            renderItemCentered(graphics, toRender, cx, cy, scale)
            if (stack.isEmpty) {
                val ghostMask = (0x7A shl 24) or 0x00_00_00
                graphics.fill(ax, ay, ax + size, ay + size, ghostMask)
            }
        }

        val ratio = durabilityRatio(stack)
        val maxD = stack.maxDamage
        if (ah.showDurabilityNumbers && ((ratio != null && stack.isEmpty.not()) || sampleRemaining != null)) {
            val rem = sampleRemaining ?: (maxD - stack.damageValue)
            val shownRatio = sampleRemaining?.let { rem.toFloat() / maxD.coerceAtLeast(1).toFloat() } ?: ratio ?: 1f
            val remText = rem.toString()
            val tx = if (ah.durabilityNumbersLeft) ax - 6 - font.width(remText) else ax + size + 6
            val ty = ay + (size - font.lineHeight) / 2
            val warnRatio = ah.warnDurabilityPercent / 100f
            val goldStyle = ah.numberStyle == "gold"
            val col = if (goldStyle) {
                // Fresh armor reads clean white; gold means it has taken wear, red is critical.
                when {
                    shownRatio >= 0.999f -> 0xFFFFFFFF.toInt()
                    shownRatio <= warnRatio -> CRITICAL
                    else -> GOLD
                }
            } else {
                when {
                    shownRatio >= 0.999f -> 0xFFFFFFFF.toInt()
                    shownRatio > 0.6f -> 0xFFD1D5DB.toInt()
                    shownRatio > warnRatio -> 0xFFE6C229.toInt()
                    else -> CRITICAL
                }
            }
            // Gold reads best with a drop shadow (RuneScape-style); graded numbers stay flat.
            graphics.drawString(font, remText, tx, ty, col, goldStyle)
        }

        if (warn && stack.isEmpty.not()) {
            graphics.drawString(font, "!", ax + size - 2, ay + 1, 0xFFFFD166.toInt(), false)
        }
    }

    private fun ghostStack(slot: EquipmentSlot): ItemStack = when (slot) {
        EquipmentSlot.HEAD -> ItemStack(Items.IRON_HELMET)
        EquipmentSlot.CHEST -> ItemStack(Items.IRON_CHESTPLATE)
        EquipmentSlot.LEGS -> ItemStack(Items.IRON_LEGGINGS)
        EquipmentSlot.FEET -> ItemStack(Items.IRON_BOOTS)
        else -> ItemStack.EMPTY
    }

    private fun renderItemCentered(graphics: GuiGraphics, stack: ItemStack, cx: Int, cy: Int, scale: Float) {
        if (stack.isEmpty) return
        val pose = graphics.pose()
//? if >=1.21.6 {
        pose.pushMatrix()
        pose.translate(cx.toFloat(), cy.toFloat())
        pose.scale(scale, scale)
        pose.translate(-8f, -8f)
//?} else {
/*        pose.pushPose()
        pose.translate(cx.toFloat(), cy.toFloat(), 0f)
        pose.scale(scale, scale, 1f)
        pose.translate(-8f, -8f, 0f)
*///?}
        graphics.renderItem(stack, 0, 0)
//? if >=1.21.6 {
        pose.popMatrix()
//?} else {
/*        pose.popPose()
*///?}
    }
}
