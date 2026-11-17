package dev.wyz.clientcore

import net.fabricmc.api.ClientModInitializer

object ClientCore : ClientModInitializer {
    const val MOD_ID = "clientcore"
    const val DISPLAY_NAME = "River Client"

    val config
        get() = RiverRuntime.config

    override fun onInitializeClient() {
        // Network channels belong to mod startup, before any connection.
        dev.wyz.clientcore.safety.FreelookOptOut.register()
        dev.wyz.clientcore.cosmetic.AccessoryLayer.register()
        RiverRuntime.initialize()
    }
}
