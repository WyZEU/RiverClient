package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.RowsHudModule
import net.minecraft.client.Minecraft

/**
 * Hunger and saturation as numbers. Saturation is the hidden bar that runs down before
 * the drumsticks start to: it decides when you have to eat again.
 */
class SaturationModule : RowsHudModule("saturation", "Saturation", "Hunger and hidden saturation", "item:minecraft:golden_carrot", 8, 492) {

    override val multiLine: Boolean
        get() = true

    override fun rows(client: Minecraft): List<Pair<String, String>> {
        val food = client.player?.foodData ?: return emptyList()
        return listOf(
            "Food" to "${food.foodLevel}/20",
            "Saturation" to "%.1f".format(food.saturationLevel)
        )
    }

    override fun previewRows() = listOf("Food" to "18/20", "Saturation" to "6.4")
}
