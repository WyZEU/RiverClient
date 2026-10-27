package dev.wyz.clientcore.ui

import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ItemLike

/**
 * Item stacks that cannot crash the screen asking for one.
 *
 * From 26.1 an item's components are bound when a world or a server's registry data is
 * loaded, not at startup, and building an ItemStack before that throws "Components not
 * bound yet". River's menu, the HUD editor and the module previews can all be opened from
 * the title screen, where item pictures were fine on every earlier version and crashed the
 * game on 26.1.2.
 *
 * An empty stack draws nothing, so callers need no special case. The attempt is repeated
 * after a second rather than on every frame, because a failure is an exception, and it
 * starts working by itself once a world is loaded.
 */
object RiverItems {

    private var retryAt = 0L

    fun stack(item: ItemLike, count: Int = 1): ItemStack {
        val now = System.currentTimeMillis()
        if (now < retryAt) return ItemStack.EMPTY
        return try {
            ItemStack(item, count)
        } catch (_: NullPointerException) {
            retryAt = now + 1000
            ItemStack.EMPTY
        }
    }
}
