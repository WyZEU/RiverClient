package dev.wyz.clientcore.input

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * Keeps River's pause menu button clear of what other mods add. ReplayMod puts its Pause and Stop
 * Recording buttons under Disconnect, the spot River's button takes, and they add theirs after
 * River has placed its own, so this looks once the menu is finished: on the first tick after.
 */
object PauseMenuLayout {
    private var placed: Button? = null

    fun tick(client: Minecraft) {
        val screen = client.screen as? PauseScreen ?: run {
            placed = null
            return
        }
        val river = screen.children().filterIsInstance<Button>().firstOrNull { it.message.string == "River Client" } ?: return
        if (river === placed) return
        placed = river
        val others = screen.children().filterIsInstance<AbstractWidget>().filter { it !== river && it.visible }
        val bottom = others
            .filter { it.x < river.x + river.width && it.x + it.width > river.x && it.y + it.height > river.y - 1 }
            .maxOfOrNull { it.y + it.height } ?: return
        river.setY(minOf(bottom + 4, screen.height - river.height - 2))
    }
}
