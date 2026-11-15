package dev.wyz.river.legacy

import net.fabricmc.api.ClientModInitializer
import org.apache.logging.log4j.LogManager

/**
 * River Client on Minecraft 1.8.9 (Legacy Fabric). A class rather than an object: there is
 * no fabric-language-kotlin on 1.8.9, so the loader builds entrypoints with a plain
 * constructor.
 */
class RiverLegacy : ClientModInitializer {
    override fun onInitializeClient() {
        println("[River] River Client for 1.8.9 loaded")
    }

    companion object {
        val log = LogManager.getLogger("River")
    }
}
