package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ChoiceSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
import net.minecraft.world.level.Level
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Things in the night sky: aurora curtains and the odd shooting star. Both are drawn as soft
 * translucent shapes by SkyEffectsRenderer, so no shader is involved and it behaves the same on
 * every game version. They fade in as it gets dark and out again at dawn, stay away in rain
 * unless asked, and only ever appear in the overworld. Client-side and purely visual.
 */
class SkyEffectsModule : Module("sky_effects", "Sky Effects", "Aurora and shooting stars at night", ModuleCategory.VISUAL, "orbit", 8, 0, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    /** A shooting star: where it starts (a point on the sky, in blocks from the camera) and which way it goes. */
    class Star(
        val startX: Float, val startY: Float, val startZ: Float,
        val dirX: Float, val dirY: Float, val dirZ: Float,
        val bornMs: Long, val lifeMs: Long
    )

    companion object {
        val PALETTES = listOf("Classic", "Arctic", "Violet", "Rose", "Shifting", "Custom")
        const val CUSTOM = 5
        const val SHIFTING = 4
        private val SIZES = listOf("Small", "Wide", "Whole sky")
        private val MOVEMENT = listOf("Calm", "Normal", "Lively")
        private val TRAILS = listOf("Short", "Medium", "Long")
        private const val SKY_RADIUS = 100f
        private const val MAX_STARS = 4
        private const val TICKS_PER_MINUTE = 1200f
    }

    private val stars = ArrayList<Star>()
    private var smoothed = 0f
    private var demoUntil = 0L
    private var lastDemoStar = 0L

    // ------------------------------------------------------------------ settings

    fun aurora(): Boolean = flag("sky_aurora", true)
    fun shootingStars(): Boolean = flag("sky_stars", true)
    private fun onlyAtNight(): Boolean = flag("sky_night", true)
    private fun inRain(): Boolean = flag("sky_rain", false)
    private fun brightness(): Int = scalar("sky_bright", 70).coerceIn(20, 100)
    private fun rate(): Int = scalar("sky_rate", 4).coerceIn(1, 10)

    /** Index into [PALETTES]. */
    fun palette(): Int = scalar("sky_palette", 0).coerceIn(0, PALETTES.size - 1)
    fun customBottom(): Int = scalar("sky_c_bottom", 0x2EF08A) and 0xFFFFFF
    fun customTop(): Int = scalar("sky_c_top", 0x9B4DFF) and 0xFFFFFF
    /** 1 to 3 curtains. */
    fun curtains(): Int = scalar("sky_size", 1).coerceIn(0, 2) + 1
    /** How fast the curtains fold and drift: 0.5, 1 or 1.8. */
    fun speed(): Float = when (scalar("sky_speed", 1).coerceIn(0, 2)) { 0 -> 0.5f; 2 -> 1.8f; else -> 1f }
    fun rays(): Boolean = flag("sky_rays", true)
    fun starColor(): Int = scalar("sky_star_color", 0xFFFFFF) and 0xFFFFFF
    /** Streak length multiplier: 0.6, 1 or 1.6. */
    fun trail(): Float = when (scalar("sky_trail", 1).coerceIn(0, 2)) { 0 -> 0.6f; 2 -> 1.6f; else -> 1f }

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Aurora"))
        list.add(BoolSetting("Aurora", { aurora() }, { setFlag("sky_aurora", it) }))
        if (aurora()) {
            list.add(ChoiceSetting("Colors", PALETTES, { PALETTES[palette()] }, { setScalar("sky_palette", PALETTES.indexOf(it).coerceAtLeast(0)) }))
            if (palette() == CUSTOM) {
                list.add(ColorSetting("Bottom color", false, { customBottom() or 0xFF000000.toInt() }, { setScalar("sky_c_bottom", it and 0xFFFFFF) }))
                list.add(ColorSetting("Top color", false, { customTop() or 0xFF000000.toInt() }, { setScalar("sky_c_top", it and 0xFFFFFF) }))
            }
            list.add(IntSetting("Brightness", 20, 100, { brightness() }, { setScalar("sky_bright", it) }, "%"))
            list.add(ChoiceSetting("Size", SIZES, { SIZES[curtains() - 1] }, { setScalar("sky_size", SIZES.indexOf(it).coerceAtLeast(0)) }))
            list.add(ChoiceSetting("Movement", MOVEMENT, { MOVEMENT[scalar("sky_speed", 1).coerceIn(0, 2)] }, { setScalar("sky_speed", MOVEMENT.indexOf(it).coerceAtLeast(0)) }))
            list.add(BoolSetting("Light rays", { rays() }, { setFlag("sky_rays", it) }))
        }
        list.add(SectionSetting("Shooting stars"))
        list.add(BoolSetting("Shooting stars", { shootingStars() }, { setFlag("sky_stars", it) }))
        if (shootingStars()) {
            list.add(IntSetting("How often", 1, 10, { rate() }, { setScalar("sky_rate", it) }))
            list.add(ChoiceSetting("Trail", TRAILS, { TRAILS[scalar("sky_trail", 1).coerceIn(0, 2)] }, { setScalar("sky_trail", TRAILS.indexOf(it).coerceAtLeast(0)) }))
            list.add(ColorSetting("Star color", false, { starColor() or 0xFF000000.toInt() }, { setScalar("sky_star_color", it and 0xFFFFFF) }))
        }
        list.add(SectionSetting("When"))
        list.add(BoolSetting("Only at night", { onlyAtNight() }, { setFlag("sky_night", it) }))
        list.add(BoolSetting("Show in rain", { inRain() }, { setFlag("sky_rain", it) }))
    }

    // ------------------------------------------------------------------ state

    /** 0 to 1: how dark it is (and clear), eased so dusk fades the aurora in instead of switching it on. */
    private fun target(level: Level): Float {
        if (level.dimension() != Level.OVERWORLD) return 0f
        val clear = if (inRain()) 1f else 1f - level.getRainLevel(1f)
        if (!onlyAtNight()) return clear
        // skyDarken is 0 in daylight and climbs to 11 at night; rain pushes it up a little by day.
        val dark = ((level.skyDarken - 4) / 7f).coerceIn(0f, 1f)
        return dark * clear
    }

    /** How strongly the aurora should draw right now, 0 when it should not draw at all. */
    fun auroraStrength(): Float = if (active && aurora()) smoothed * brightness() / 100f else 0f

    override fun tick(client: Minecraft) {
        val level = client.level ?: return
        val now = dev.wyz.clientcore.dev.DevClock.millis()
        val inDemo = now < demoUntil
        smoothed += ((if (inDemo) 1f else target(level)) - smoothed) * 0.08f

        stars.removeAll { now - it.bornMs > it.lifeMs }
        if (!shootingStars() || stars.size >= MAX_STARS) return
        if (inDemo) {
            if (now - lastDemoStar > 450L) {
                lastDemoStar = now
                val look = client.player?.getViewVector(1f)
                spawnStar(now, look?.let { Math.atan2(it.z, it.x) })
            }
        } else if (smoothed > 0.5f && Random.nextFloat() < rate() * 2f / TICKS_PER_MINUTE) {
            spawnStar(now)
        }
    }

    /** [aim] puts the star in front of a direction (an angle around the camera); only the demo uses it. */
    private fun spawnStar(now: Long, aim: Double? = null) {
        val azimuth = if (aim != null) aim + (Random.nextDouble() - 0.5) * 0.9 else Random.nextDouble() * Math.PI * 2
        val elevation = if (aim != null) 0.45 + Random.nextDouble() * 0.3 else 0.3 + Random.nextDouble() * 0.7
        val heading = Random.nextDouble() * Math.PI * 2
        val flat = cos(elevation)
        val dx = cos(heading).toFloat()
        val dz = sin(heading).toFloat()
        val length = Math.sqrt((dx * dx + 0.35 * 0.35 + dz * dz).toDouble()).toFloat()
        stars += Star(
            (flat * cos(azimuth)).toFloat() * SKY_RADIUS, sin(elevation).toFloat() * SKY_RADIUS, (flat * sin(azimuth)).toFloat() * SKY_RADIUS,
            dx / length, -0.35f / length, dz / length,
            now, 900L + Random.nextLong(500L)
        )
    }

    /** The stars to draw this frame. */
    fun stars(): List<Star> = if (active) stars else emptyList()

    /** The sky effects forced on for a few seconds, for the hidden screenshot runs. Never used in a normal game. */
    fun startDemo() {
        enabled = true
        setScalar("sky_bright", 100)
        setScalar("sky_size", 2)
        demoUntil = dev.wyz.clientcore.dev.DevClock.millis() + 60_000L
    }
}
