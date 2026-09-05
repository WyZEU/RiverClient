package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.ActionSetting
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.CloudStatus
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
//? if >=1.21.2 {
import net.minecraft.server.level.ParticleStatus
//?} else {
/*import net.minecraft.client.ParticleStatus
*///?}

/**
 * River's built-in performance options (no external mods, all conflict-safe @Inject
 * hooks). Every toggle here reduces measurable work; use the Perf Stats HUD to compare
 * before/after. Settings are stored on this module's scalar/flag map so they persist
 * per profile.
 *
 *  - Cull hidden entities: [dev.wyz.clientcore.perf.EntityCuller] (occlusion)
 *  - Entity distance:      [dev.wyz.clientcore.mixin.EntityRenderDispatcherMixin]
 *  - Entity shadows:       [dev.wyz.clientcore.mixin.EntityShadowMixin]
 *  - Particle distance:    [dev.wyz.clientcore.mixin.ParticleEngineMixin]
 *  - HUD update rate:      [dev.wyz.clientcore.hud.HudRenderer] throttle
 *  - Unfocused/menu FPS:   [dev.wyz.clientcore.mixin.FramerateLimitTrackerMixin]
 *  - Rain & snow toggle:   [dev.wyz.clientcore.mixin.WeatherEffectRendererMixin]
 */
class PerformanceModule : Module("performance", "Performance", "River's built-in performance options", ModuleCategory.UTILITY, "gauge", 0, 0) {

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    // Entities
    fun entityCullEnabled(): Boolean = flag("entity_cull", true)
    fun blockEntityCullEnabled(): Boolean = flag("block_entity_cull", true)
    /** Max entity render distance in blocks; 0 = use vanilla (no extra limit). */
    fun entityDistance(): Int = scalar("entity_distance", 0).coerceIn(0, 256)
    /** Max block-entity render distance (chests, signs, banners...); 0 = no extra limit. */
    fun blockEntityDistance(): Int = scalar("be_distance", 0).coerceIn(0, 128)
    fun entityShadows(): Boolean = flag("entity_shadows", true)

    // Particles
    /** Skip creating particles farther than this many blocks from the camera; 0 = off. */
    fun particleDistance(): Int = scalar("particle_distance", 0).coerceIn(0, 128)
    /** Cap on particle spawns per tick (tames explosion/farm bursts); 0 = off. */
    fun particleBudget(): Int = scalar("particle_budget", 0).coerceIn(0, 2000)

    // HUD
    /** How many times per second throttled HUD readouts recompute their text. */
    fun hudRateHz(): Int = scalar("hud_rate", 20).coerceIn(5, 60)

    // Unfocused window
    fun unfocusedCapEnabled(): Boolean = flag("unfocused_cap", true)
    fun unfocusedFps(): Int = scalar("unfocused_fps", 15).coerceIn(5, 60)

    // Menus (inventory, chests, pause) - the world keeps rendering behind an open
    // screen at full cost, and a menu gains nothing from hundreds of FPS.
    fun menuCapEnabled(): Boolean = flag("menu_cap", false)
    fun menuFps(): Int = scalar("menu_fps", 60).coerceIn(30, 144)

    // Screen overlays, ported from Prism's NoRender. These are translucent
    // full-screen draws, so hiding them is both a clarity and a fill-rate win.
    fun waterOverlayEnabled(): Boolean = flag("water_overlay", true)
    fun wallOverlayEnabled(): Boolean = flag("wall_overlay", true)
    fun bossBarsEnabled(): Boolean = flag("boss_bars", true)
    fun pumpkinOverlayEnabled(): Boolean = flag("pumpkin_overlay", true)

    // Weather columns are whole-screen translucent overdraw during storms.
    fun weatherRenderEnabled(): Boolean = flag("weather_render", true)

    /**
     * Applies the known fast Minecraft settings in one shot. This is not a magic
     * optimization — it just sets the vanilla levers that actually move FPS (mainly
     * render + simulation distance) and turns on River's own perf toggles. It only
     * touches settings that keep the world looking normal (it leaves graphics mode and
     * smooth lighting alone); revert any of it in Options. Returns a summary line.
     */
    fun applyMaxFpsPreset(client: Minecraft): String {
        val o = client.options
        o.renderDistance().set(12)       // biggest FPS lever; use Voxy/LOD for the far view
        o.simulationDistance().set(8)    // CPU relief, no visual change
        o.entityDistanceScaling().set(0.5)
        o.entityShadows().set(false)
        o.particles().set(ParticleStatus.MINIMAL)
        o.mipmapLevels().set(4)          // distant faces sample tiny mips (the "far = low-res" look)
        o.cloudStatus().set(CloudStatus.OFF)
        o.save()

        // River's own conflict-safe toggles.
        setFlag("entity_cull", true)
        setFlag("entity_shadows", false)
        setScalar("entity_distance", 96)
        setScalar("be_distance", 64)
        setScalar("particle_distance", 48)
        setScalar("particle_budget", 400)

//? if >=26.2 {
/*        runCatching { client.levelExtractor.allChanged() }
*///?} else {
        runCatching { client.levelRenderer.allChanged() }
//?}
        return "River Max FPS: render 12, sim 8, entities 50%, particles minimal, clouds off, mipmap 4. Mipmap fully applies after a resource reload (F3+T). Change any of it in Options."
    }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Preset"))
        list.add(ActionSetting("Max FPS preset", "Apply") {
            val client = Minecraft.getInstance()
            val summary = applyMaxFpsPreset(client)
//? if >=26.1 {
/*            client.player?.sendSystemMessage(Component.literal(summary))
*///?} else {
            client.player?.displayClientMessage(Component.literal(summary), false)
//?}
        })

        list.add(SectionSetting("Entities"))
        list.add(BoolSetting("Cull hidden entities", { flag("entity_cull", true) }, { setFlag("entity_cull", it) }))
        list.add(BoolSetting("Cull hidden block entities", { flag("block_entity_cull", true) }, { setFlag("block_entity_cull", it) }))
        list.add(IntSetting("Entity render distance", 0, 256, { scalar("entity_distance", 0) }, { setScalar("entity_distance", it) }, " blk"))
        list.add(IntSetting("Block entity distance", 0, 128, { scalar("be_distance", 0) }, { setScalar("be_distance", it) }, " blk"))
        list.add(BoolSetting("Entity shadows", { flag("entity_shadows", true) }, { setFlag("entity_shadows", it) }))

        list.add(SectionSetting("Particles"))
        list.add(IntSetting("Particle distance", 0, 128, { scalar("particle_distance", 0) }, { setScalar("particle_distance", it) }, " blk"))
        list.add(IntSetting("Particles per tick", 0, 2000, { scalar("particle_budget", 0) }, { setScalar("particle_budget", it) }))

        list.add(SectionSetting("HUD"))
        list.add(IntSetting("HUD update rate", 5, 60, { scalar("hud_rate", 20) }, { setScalar("hud_rate", it) }, " Hz"))

        list.add(SectionSetting("Overlays"))
        list.add(BoolSetting("Water overlay", { flag("water_overlay", true) }, { setFlag("water_overlay", it) }))
        list.add(BoolSetting("Suffocation overlay", { flag("wall_overlay", true) }, { setFlag("wall_overlay", it) }))
        list.add(BoolSetting("Boss bars", { flag("boss_bars", true) }, { setFlag("boss_bars", it) }))
        list.add(BoolSetting("Pumpkin overlay", { flag("pumpkin_overlay", true) }, { setFlag("pumpkin_overlay", it) }))
        list.add(SectionSetting("Weather"))
        list.add(BoolSetting("Render rain and snow", { flag("weather_render", true) }, { setFlag("weather_render", it) }))

        list.add(SectionSetting("FPS caps"))
        list.add(BoolSetting("Limit FPS when unfocused", { flag("unfocused_cap", true) }, { setFlag("unfocused_cap", it) }))
        list.add(IntSetting("Unfocused FPS limit", 5, 60, { scalar("unfocused_fps", 15) }, { setScalar("unfocused_fps", it) }))
        list.add(BoolSetting("Limit FPS in menus", { flag("menu_cap", false) }, { setFlag("menu_cap", it) }))
        list.add(IntSetting("Menu FPS limit", 30, 144, { scalar("menu_fps", 60) }, { setScalar("menu_fps", it) }))
    }
}
