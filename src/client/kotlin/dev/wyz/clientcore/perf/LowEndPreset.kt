package dev.wyz.clientcore.perf

import net.minecraft.client.CloudStatus
import net.minecraft.client.Minecraft
//? if >=1.21.2 {
import net.minecraft.server.level.ParticleStatus
//?} else {
/*import net.minecraft.client.ParticleStatus
*///?}
import net.minecraft.network.chat.Component
import java.lang.management.ManagementFactory
import java.nio.file.Files

/**
 * Lighter settings for a weak PC, applied once, on an instance's very first launch.
 *
 * Minecraft's defaults assume a decent graphics card: twelve chunks of render and
 * simulation distance, all particles, clouds. On integrated graphics or a few-core CPU
 * that is the difference between playable and not, and someone new to River should not
 * have to know which levers to pull. Lunar does the same.
 *
 * Only a brand-new instance is touched: one with no River config yet and no options.txt
 * from before this launch. An instance somebody already set up, or imported from another
 * launcher, keeps exactly what it has. The player is told in chat what changed and where
 * to change it back.
 *
 * Only vanilla options: River's own Performance module (and its culling toggles) is
 * deliberately unregistered, so its settings would do nothing.
 */
object LowEndPreset {

    private var freshInstance = false
    /** Dev testing only: -Driver.lowend.force treats this launch as a fresh instance on weak hardware. */
    private val forced = System.getProperty("river.lowend.force") != null
    private var decided = false
    private var pendingMessage: String? = null

    /** Called before River writes its own config, while "first launch" can still be told apart. */
    fun noteLaunch() {
        if (forced) {
            freshInstance = true
            return
        }
        runCatching {
            // Fabric's game dir, not Minecraft's: this runs while the game is still being
            // constructed, before Minecraft.getInstance() can be relied on.
            val game = net.fabricmc.loader.api.FabricLoader.getInstance().gameDir
            val riverConfig = game.resolve("config").resolve("river-client")
            val legacyConfig = game.resolve("config").resolve("clientcore.json")
            val options = game.resolve("options.txt")
            // The game saves options.txt early in its own startup, so a file written during
            // this launch still counts as fresh; one older than the JVM is the player's.
            val jvmStart = ManagementFactory.getRuntimeMXBean().startTime
            val optionsFromBefore = Files.exists(options) &&
                Files.getLastModifiedTime(options).toMillis() < jvmStart - 2_000L
            freshInstance = !Files.exists(riverConfig) && !Files.exists(legacyConfig) && !optionsFromBefore
        }.onFailure { println("[river] low-end preset: could not tell whether this is a first launch: $it") }
        if (freshInstance) println("[river] first launch of this instance; checking the hardware for the low-end preset")
    }

    fun tick(client: Minecraft) {
        if (!decided) {
            decided = true
            if (freshInstance) {
                val reason = if (forced) "forced for testing" else weakHardwareReason()
                if (reason != null) {
                    runCatching { apply(client) }
                        .onSuccess {
                            println("[river] low-end preset applied on first launch ($reason)")
                            pendingMessage = "Low-end PC ($reason): render distance 8, simulation 6, " +
                                "fewer particles, clouds off. Change it in Options."
                        }
                        .onFailure { println("[river] low-end preset could not be applied: $it") }
                }
            }
        }
        val message = pendingMessage ?: return
        val player = client.player ?: return
        pendingMessage = null
//? if >=26.1 {
/*        player.sendSystemMessage(Component.literal(message))
*///?} else {
        player.displayClientMessage(Component.literal(message), false)
//?}
    }

    /**
     * Lighter on distance, gentle on looks: graphics mode, smooth lighting and mipmaps are
     * left alone so the world still looks like Minecraft.
     */
    private fun apply(client: Minecraft) {
        val o = client.options
        o.renderDistance().set(8)
        o.simulationDistance().set(6)
        o.entityDistanceScaling().set(0.75)
        o.entityShadows().set(false)
        o.particles().set(ParticleStatus.DECREASED)
        o.cloudStatus().set(CloudStatus.OFF)
        o.save()
//? if >=26.2 {
/*        runCatching { client.levelExtractor.allChanged() }
*///?} else {
        runCatching { client.levelRenderer.allChanged() }
//?}
    }

    /** Why this PC counts as low-end, or null when it does not. Read on the render thread. */
    fun weakHardwareReason(): String? {
        val ramGb = runCatching {
            (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean)
                .totalMemorySize / (1024.0 * 1024 * 1024)
        }.getOrNull()
        if (ramGb != null && ramGb < 7.5) return "%.0f GB of RAM".format(ramGb)

        val cores = Runtime.getRuntime().availableProcessors()
        if (cores <= 4) return "$cores CPU threads"

        // The renderer string of the GPU Minecraft is actually drawing with. Throws when
        // there is no GL context on this thread, which simply means "can't tell".
        val gpu = runCatching { org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER) }.getOrNull().orEmpty()
        if (gpu.isNotBlank() && INTEGRATED.containsMatchIn(gpu) && !DISCRETE.containsMatchIn(gpu)) {
            return "integrated graphics"
        }
        return null
    }

    private val INTEGRATED = Regex(
        "Intel\\(R\\) (HD|UHD|Iris)|Intel.*Graphics|Radeon\\(TM\\) (Graphics|Vega|R\\d)|Radeon Vega|" +
            "Microsoft Basic|GDI Generic|llvmpipe|softpipe|Mali|Adreno",
        RegexOption.IGNORE_CASE
    )
    private val DISCRETE = Regex("RTX|GTX|Quadro|Radeon RX|Radeon Pro|Arc A\\d|Arc B\\d", RegexOption.IGNORE_CASE)
}
