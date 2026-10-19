package dev.wyz.clientcore

import net.fabricmc.api.ClientModInitializer

object ClientCore : ClientModInitializer {
    const val MOD_ID = "clientcore"
    const val DISPLAY_NAME = "River Client"

    val config
        get() = RiverRuntime.config

    override fun onInitializeClient() {
        RiverRuntime.initialize()
    }
}
