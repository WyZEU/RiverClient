package dev.wyz.clientcore.ui.tooltip

import dev.wyz.clientcore.mixin.AbstractContainerScreenAccessor
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.impl.ContainerPreviewModule
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
// 26.2 moved screen ownership off Minecraft onto Gui; MinecraftCompat puts it back under
// the name every other version uses.
//? if >=26.2 {
/*import dev.wyz.clientcore.compat.*
*///?}
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.core.component.DataComponents
import net.minecraft.world.inventory.tooltip.TooltipComponent
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.ItemStack
//? if >=26.2 {
/*import net.minecraft.world.item.DyeColor
*///?}
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.ShulkerBoxBlock

/**
 * Decides whether an item gets a contents preview, and what goes in it.
 *
 * Called from the item tooltip path, so it has to answer quickly and never throw - a
 * tooltip is built every frame the cursor is over something.
 */
object ContainerTooltips {

    /** Null means "no preview", which leaves the tooltip exactly as vanilla built it. */
    @JvmStatic
    fun previewFor(stack: ItemStack): TooltipComponent? {
        if (stack.isEmpty) return null
        val module = ModuleRegistry.get<ContainerPreviewModule>("container_preview") ?: return null
        if (!module.active) return null
        if (module.needsShift && !shiftHeld()) return null

        val items = contentsOf(stack, module) ?: return null
        return ShulkerTooltipData(
            items,
            if (isShulker(stack)) tintFor(stack) else ENDER_TINT,
            tinted = module.drawsTint,
            counts = module.drawsCounts,
            maxRows = module.rowLimit,
            spacing = module.spacing,
            scale = (module.scale.coerceIn(50, 100) / 100f)
        )
    }

    /*
      Moved from a static on Screen to an instance method on Minecraft in 1.21.10 - not
      1.21.11, which is where guessing would have put it.
    */
    private fun shiftHeld(): Boolean = runCatching {
//? if >=1.21.10 {
        Minecraft.getInstance().hasShiftDown()
//?} else {
/*        net.minecraft.client.gui.screens.Screen.hasShiftDown()
*///?}
    }.getOrDefault(false)

    private fun isShulker(stack: ItemStack): Boolean {
        val item = stack.item
        return item is BlockItem && item.block is ShulkerBoxBlock
    }

    /*
      Exact: a shulker carries its contents in the item itself.

      From 26.1 the component hands back templates rather than stacks - the same item and
      count, not yet materialised - so they have to be created before anything can draw them.
    */
    private fun shulkerContents(stack: ItemStack): List<ItemStack> {
        val contents = stack.get(DataComponents.CONTAINER) ?: return emptyList()
//? if >=26.1 {
/*        return contents.nonEmptyItems().map { it.create() }.toList()
*///?} else {
        return contents.nonEmptyItems().toList()
//?}
    }

    /*
      Your own ender chest, remembered from the last time you had it open.

      Reading player.enderChestInventory does not work: on the client that container is
      never filled in. An open ender chest is an ordinary server-backed menu, so the only
      moment the contents exist on this side is while the screen is up - which is why they
      are copied out then and kept, rather than asked for later and found empty.

      Null until that has happened once. There is no way around that: the contents are not
      on the item and never can be, they live on the server, per player.
    */
    @Volatile private var enderChestSnapshot: List<ItemStack>? = null

    /**
     * The contents an item would preview, or null if it would not preview at all.
     *
     * Shared by the preview, the hint line and the vanilla-text suppression, so those
     * three can never disagree about whether an item is previewable.
     */
    private fun contentsOf(stack: ItemStack, module: ContainerPreviewModule): List<ItemStack>? {
        val raw = when {
            isShulker(stack) -> shulkerContents(stack)
            stack.`is`(Items.ENDER_CHEST) && module.previewsEnderChests -> enderChestSnapshot
            else -> null
        } ?: return null
        return if (module.mergesDuplicates) merge(raw) else raw
    }

    /**
     * Collapses identical stacks into one cell carrying the total.
     *
     * A shulker of cobblestone is twenty-seven cells of the same texture, which says
     * nothing the first cell did not. Merged, the grid is as long as the number of
     * different things in the box, which is the thing worth seeing at a glance.
     *
     * Components are part of identity, so an enchanted sword never merges into a plain
     * one, and two differently enchanted swords stay apart.
     */
    private fun merge(items: List<ItemStack>): List<ItemStack> {
        val merged = ArrayList<ItemStack>(items.size)
        for (item in items) {
            val existing = merged.firstOrNull { ItemStack.isSameItemSameComponents(it, item) }
            if (existing == null) {
                merged.add(item.copy())
            } else {
                existing.count += item.count
            }
        }
        return merged
    }

    private const val ENDER_TINT = 0x2A6B62

    /**
     * The line that stands in for vanilla's contents list while the grid is not showing.
     *
     * Only ever the prompt. A written summary of the contents was tried and is not what
     * this is for - the grid is the answer, and a sentence describing it is a worse one
     * taking up the same room.
     */
    @JvmStatic
    fun hintFor(stack: ItemStack): Component? {
        val module = ModuleRegistry.get<ContainerPreviewModule>("container_preview") ?: return null
        if (!module.active || !module.needsShift || shiftHeld()) return null
        if (contentsOf(stack, module) == null) return null
        return Component.translatable("clientcore.tooltip.hold_shift").withStyle(ChatFormatting.DARK_GRAY)
    }

    /**
     * Whether vanilla's own text list of the contents should be dropped for this item.
     *
     * Not conditional on shift: with the module on, that list is replaced either by the
     * grid or by the hint, and showing it as well would be the same information twice.
     */
    @JvmStatic
    fun suppressesVanillaText(): Boolean = runCatching {
        val module = ModuleRegistry.get<ContainerPreviewModule>("container_preview") ?: return false
        if (!module.active) return false
        val screen = Minecraft.getInstance().screen as? AbstractContainerScreen<*> ?: return false
        val hovered = (screen as AbstractContainerScreenAccessor).hoveredSlot?.item ?: return false
        isShulker(hovered)
    }.getOrDefault(false)

    /** Called every tick; copies the contents out while an ender chest screen is open. */
    @JvmStatic
    fun tick(client: Minecraft) {
        val screen = client.screen as? AbstractContainerScreen<*> ?: return
        if (!isEnderChest(screen)) return
        val slots = screen.menu.slots
        // The player inventory is part of the same menu, so only the chest's own rows count.
        val chest = slots.take(27).map { it.item }.filter { !it.isEmpty }
        enderChestSnapshot = chest
    }

    private fun isEnderChest(screen: AbstractContainerScreen<*>): Boolean = runCatching {
        screen.menu.slots.size >= 27 &&
            screen.title.string == Component.translatable("container.enderchest").string
    }.getOrDefault(false)

    /*
      The dye the shulker was made with.

      26.2 collapsed the sixteen dyed shulker boxes into one item that carries its colour
      in a component, so there is nothing left to match on by identity there. Below that,
      the item constants are the colour and have not moved in any version River builds for.
      An undyed shulker has no colour either way and gets the default purple.
    */
    private fun tintFor(stack: ItemStack): Int {
//? if >=26.2 {
/*        val dye = stack.get(DataComponents.DYE) ?: return DEFAULT_TINT
        return DYE_COLOURS[dye] ?: DEFAULT_TINT
*///?} else {
        for ((item, colour) in DYED) if (stack.`is`(item)) return colour
        return DEFAULT_TINT
//?}
    }

    private const val DEFAULT_TINT = 0x7B4B94

//? if >=26.2 {
/*    private val DYE_COLOURS by lazy {
        mapOf(
            DyeColor.WHITE to 0xD9D9D4, DyeColor.LIGHT_GRAY to 0x9D9D97, DyeColor.GRAY to 0x474F52,
            DyeColor.BLACK to 0x1D1D21, DyeColor.BROWN to 0x835432, DyeColor.RED to 0xB02E26,
            DyeColor.ORANGE to 0xF9801D, DyeColor.YELLOW to 0xFED83D, DyeColor.LIME to 0x80C71F,
            DyeColor.GREEN to 0x5E7C16, DyeColor.CYAN to 0x169C9C, DyeColor.LIGHT_BLUE to 0x3AB3DA,
            DyeColor.BLUE to 0x3C44AA, DyeColor.PURPLE to 0x8932B8, DyeColor.MAGENTA to 0xC74EBD,
            DyeColor.PINK to 0xF38BAA
        )
    }
*///?} else {
    private val DYED by lazy {
        listOf(
            Items.WHITE_SHULKER_BOX to 0xD9D9D4,
            Items.LIGHT_GRAY_SHULKER_BOX to 0x9D9D97,
            Items.GRAY_SHULKER_BOX to 0x474F52,
            Items.BLACK_SHULKER_BOX to 0x1D1D21,
            Items.BROWN_SHULKER_BOX to 0x835432,
            Items.RED_SHULKER_BOX to 0xB02E26,
            Items.ORANGE_SHULKER_BOX to 0xF9801D,
            Items.YELLOW_SHULKER_BOX to 0xFED83D,
            Items.LIME_SHULKER_BOX to 0x80C71F,
            Items.GREEN_SHULKER_BOX to 0x5E7C16,
            Items.CYAN_SHULKER_BOX to 0x169C9C,
            Items.LIGHT_BLUE_SHULKER_BOX to 0x3AB3DA,
            Items.BLUE_SHULKER_BOX to 0x3C44AA,
            Items.PURPLE_SHULKER_BOX to 0x8932B8,
            Items.MAGENTA_SHULKER_BOX to 0xC74EBD,
            Items.PINK_SHULKER_BOX to 0xF38BAA
        )
    }
//?}
}
