package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.RowsHudModule
import net.minecraft.client.Minecraft

/**
 * Which in-game day it is. Counted from the world's age rather than its time of day,
 * so sleeping or /time set doesn't change it: it's how many days the world has run.
 */
class DayCounterModule : RowsHudModule("day_counter", "Day Counter", "How many in-game days the world has run", "item:minecraft:clock", 8, 508) {

    override fun rows(client: Minecraft): List<Pair<String, String>> {
        val level = client.level ?: return emptyList()
        return listOf("Day" to (level.gameTime / 24_000L + 1).toString())
    }

    override fun previewRows() = listOf("Day" to "142")
}
