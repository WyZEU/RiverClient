package dev.wyz.clientcore.module

import dev.wyz.clientcore.module.impl.ArmorHudModule
import dev.wyz.clientcore.module.impl.AutoReconnectModule
import dev.wyz.clientcore.module.impl.ChatTweaksModule
import dev.wyz.clientcore.module.impl.ChunkBordersModule
import dev.wyz.clientcore.module.impl.ClearChatModule
import dev.wyz.clientcore.module.impl.CapeModule
import dev.wyz.clientcore.module.impl.CoordsCopyModule
import dev.wyz.clientcore.module.impl.ClockModule
import dev.wyz.clientcore.module.impl.CombatVisualsModule
import dev.wyz.clientcore.module.impl.CountersModule
import dev.wyz.clientcore.module.impl.CoordinatesModule
import dev.wyz.clientcore.module.impl.CpsModule
import dev.wyz.clientcore.module.impl.CrosshairDotModule
import dev.wyz.clientcore.module.impl.DirectionModule
import dev.wyz.clientcore.module.impl.DurabilityModule
import dev.wyz.clientcore.module.impl.EnabledModulesModule
import dev.wyz.clientcore.module.impl.FpsModule
import dev.wyz.clientcore.module.impl.FreelookModule
import dev.wyz.clientcore.module.impl.FullbrightModule
import dev.wyz.clientcore.module.impl.HitboxesModule
import dev.wyz.clientcore.module.impl.InventoryHudModule
import dev.wyz.clientcore.module.impl.KeystrokesModule
import dev.wyz.clientcore.module.impl.MemoryModule
import dev.wyz.clientcore.module.impl.NameTagModule
import dev.wyz.clientcore.module.impl.NoHurtCamModule
import dev.wyz.clientcore.module.impl.NoViewBobModule
import dev.wyz.clientcore.module.impl.PerfStatsModule
import dev.wyz.clientcore.module.impl.PerformanceModule
import dev.wyz.clientcore.module.impl.PerspectiveModule
import dev.wyz.clientcore.module.impl.NowPlayingModule
import dev.wyz.clientcore.module.impl.PingGraphModule
import dev.wyz.clientcore.module.impl.PingModule
import dev.wyz.clientcore.module.impl.PotionHudModule
import dev.wyz.clientcore.module.impl.ReachDisplayModule
import dev.wyz.clientcore.module.impl.ScoreboardModule
import dev.wyz.clientcore.module.impl.ScreenshotModule
import dev.wyz.clientcore.module.impl.SessionTimeModule
import dev.wyz.clientcore.module.impl.SpeedModule
import dev.wyz.clientcore.module.impl.StaticFovModule
import dev.wyz.clientcore.module.impl.QuickDisconnectModule
import dev.wyz.clientcore.module.impl.TimeChangerModule
import dev.wyz.clientcore.module.impl.ToggleSprintModule
import dev.wyz.clientcore.module.impl.ViewModelModule
import dev.wyz.clientcore.module.impl.WatermarkModule
import dev.wyz.clientcore.module.impl.WaypointsModule
import dev.wyz.clientcore.module.impl.ZoomModule
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

object ModuleRegistry {
    private val modulesById = LinkedHashMap<String, Module>()

    /** Registration only happens in [bootstrap], so the list is built once and reused
     *  (the old getter allocated a fresh copy on every access, every frame). */
    private var cachedAll: List<Module> = emptyList()

    val all: List<Module>
        get() = cachedAll

    fun bootstrap() {
        if (modulesById.isNotEmpty()) return

        // HUD
        register(FpsModule())
        register(CpsModule())
        register(PingModule())
        register(PingGraphModule())
        register(NowPlayingModule())
        register(CoordinatesModule())
        register(DirectionModule())
        register(KeystrokesModule())
        register(ArmorHudModule())
        register(PotionHudModule())
        register(InventoryHudModule())
        register(SpeedModule())
        register(DurabilityModule())
        register(ClockModule())
        register(SessionTimeModule())
        register(MemoryModule())
        register(EnabledModulesModule())
        // Performance + Perf Stats removed: the default-on entity occlusion culling ran a
        // per-tick raycast over every entity on the render thread, which tanked FPS and
        // added input lag on weaker CPUs. Unregistering (rather than deleting the classes)
        // makes every perf mixin's ModuleRegistry.get("performance") null-check revert to
        // vanilla, with no risk of a mixin/startup break.
        register(CountersModule())
        register(ReachDisplayModule())

        // Visual
        register(CrosshairDotModule())
        register(ZoomModule())
        register(FullbrightModule())
        register(HitboxesModule())
        register(ChunkBordersModule())
        register(CombatVisualsModule())
        register(ScoreboardModule())

        // Gameplay
        register(ToggleSprintModule())
        register(FreelookModule())
        register(PerspectiveModule())
        register(NoHurtCamModule())
        register(NoViewBobModule())
        register(StaticFovModule())
        register(ViewModelModule())

        // Utility
        register(ScreenshotModule())
        register(WaypointsModule())
        register(ChatTweaksModule())
        register(CoordsCopyModule())
        register(ClearChatModule())
        register(QuickDisconnectModule())
        register(TimeChangerModule())
        register(AutoReconnectModule())

        // Cosmetics
        register(NameTagModule())
        register(WatermarkModule())
        register(CapeModule())

        cachedAll = modulesById.values.toList()
    }

    fun byCategory(category: ModuleCategory): List<Module> = all.filter { it.category == category && it.showInMenu }

    fun search(query: String): List<Module> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all.filter { it.showInMenu }
        return all.filter {
            it.showInMenu && (
                it.displayName.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.id.replace('_', ' ').contains(q)
                )
        }
    }

    fun tick(client: Minecraft) {
        val modules = cachedAll
        for (i in modules.indices) {
            val module = modules[i]
            if (module.active) {
                module.tick(client)
            }
        }
        // These keep vanilla state consistent, so they run even while inactive.
        get<FullbrightModule>("fullbright")?.sync(client)
        get<HitboxesModule>("hitboxes")?.sync(client)
        get<ChunkBordersModule>("chunk_borders")?.sync(client)
        get<ToggleSprintModule>("toggle_sprint")?.sync(client)
        get<FreelookModule>("freelook")?.sync(client)
    }

    fun render(client: Minecraft, graphics: GuiGraphics, tickDelta: Float) {
        // Indexed loop with the scale transform inlined: no iterator or closure
        // allocations in the per-frame HUD path.
        val modules = cachedAll
        for (i in modules.indices) {
            val module = modules[i]
            if (!module.active) continue
            val scale = module.scaleFactor()
            if (scale == 1f) {
                module.render(client, graphics, tickDelta)
            } else {
                val pose = graphics.pose()
                pose.pushPose()
                pose.translate(module.x.toFloat(), module.y.toFloat(), 0f)
                pose.scale(scale, scale, 1f)
                pose.translate(-module.x.toFloat(), -module.y.toFloat(), 0f)
                module.render(client, graphics, tickDelta)
                pose.popPose()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Module> get(id: String): T? = modulesById[id] as? T

    private fun register(module: Module) {
        modulesById[module.id] = module
    }
}
