package dev.wyz.clientcore.pvp

import net.minecraft.client.Minecraft
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemCooldowns
import net.minecraft.world.item.ItemStack
//? if >=1.21.2 {
import dev.wyz.clientcore.compat.McId
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Items
//?}

/**
 * The local player's item cooldowns with their full length, fed by ItemCooldownsMixin.
 * How much is left always comes from vanilla's own percent, so it pauses with the game
 * and matches the white sweep on the hotbar exactly; the length recorded here only
 * turns that percent into seconds.
 */
object CooldownTracker {
    class Entry(val item: Item, val stack: ItemStack, val durationTicks: Int)

    private val entries = LinkedHashMap<Item, Entry>()

    private fun isLocal(cooldowns: ItemCooldowns): Boolean =
        Minecraft.getInstance().player?.cooldowns === cooldowns

    @JvmStatic
    fun started(cooldowns: ItemCooldowns, group: Any, ticks: Int) {
        if (ticks <= 0 || !isLocal(cooldowns)) return
        val item = itemFor(group) ?: return
        entries.remove(item)
        entries[item] = Entry(item, ItemStack(item), ticks)
    }

    @JvmStatic
    fun ended(cooldowns: ItemCooldowns, group: Any) {
        if (!isLocal(cooldowns)) return
        itemFor(group)?.let { entries.remove(it) }
    }

    /** Pre-1.21.2 the key is the item itself; after it, a group id that is the item id. */
    private fun itemFor(group: Any): Item? {
        if (group is Item) return group
//? if >=1.21.2 {
        val item = BuiltInRegistries.ITEM.getValue(group as McId)
        return if (item == Items.AIR) null else item
//?} else {
/*        return null
*///?}
    }

    private fun remaining(entry: Entry, partialTick: Float): Float {
        val cooldowns = Minecraft.getInstance().player?.cooldowns ?: return 0f
//? if >=1.21.2 {
        return cooldowns.getCooldownPercent(entry.stack, partialTick)
//?} else {
/*        return cooldowns.getCooldownPercent(entry.item, partialTick)
*///?}
    }

    /** Running cooldowns, oldest first, with the fraction left. Finished ones are dropped. */
    fun active(partialTick: Float): List<Pair<Entry, Float>> {
        if (Minecraft.getInstance().player == null) {
            entries.clear()
            return emptyList()
        }
        val out = ArrayList<Pair<Entry, Float>>(entries.size)
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val left = remaining(entry, partialTick)
            if (left <= 0f) iterator.remove() else out.add(entry to left)
        }
        return out
    }
}
