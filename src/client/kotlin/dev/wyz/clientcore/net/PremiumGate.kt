package dev.wyz.clientcore.net

import com.mojang.authlib.exceptions.InvalidCredentialsException
import dev.wyz.clientcore.mixin.MinecraftUserApiAccess
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.AlertScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * The standalone mod needs a real Minecraft account.
 *
 * River never reads the login token. It asks the game's own Mojang account service, which
 * the game signed in with, whether Mojang accepts this account. Only a clear "these
 * credentials are not valid" from Mojang closes the game; an outage or no internet never
 * locks a paying player out. River's launcher signs players in itself, so it is skipped there.
 */
object PremiumGate {
    @Volatile private var verdict: Boolean? = null
    private var started = false
    private var shown = false

    private fun skipped(): Boolean =
        (net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment && !java.lang.Boolean.getBoolean("river.premiumCheck.test")) ||
            System.getProperty("minecraft.launcher.brand") == "river-client"

    fun tick(client: Minecraft) {
        if (shown) return
        if (!started) {
            if (skipped()) { shown = true; return }
            started = true
            val service = (client as MinecraftUserApiAccess).clientcore_userApiService()
            Thread({
                verdict = try {
                    service.fetchProperties()
                    true
                } catch (_: InvalidCredentialsException) {
                    false
                } catch (_: Throwable) {
                    true
                }
            }, "River-PremiumGate").apply { isDaemon = true }.start()
            return
        }
        if (verdict != false || client.screen !is TitleScreen) return
        shown = true
        client.setScreen(
            AlertScreen(
                { client.stop() },
                Component.literal("River Client needs a premium Minecraft account"),
                Component.literal("Your launcher is not signed in to one. If you own Minecraft, sign in again and restart.")
            )
        )
    }
}
