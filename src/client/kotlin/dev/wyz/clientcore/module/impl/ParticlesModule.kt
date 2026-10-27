package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.ModuleRegistry
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.ColorSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import dev.wyz.clientcore.module.settings.hint
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import java.util.concurrent.ConcurrentHashMap

/**
 * Particle Effects: more particles when you hit something, a custom crit colour, and a switch
 * for each kind of particle. Visual only - it changes what you see, never what happens. The
 * particle mixin (ParticleEngineMixin) asks this module about each particle as it is made.
 */
class ParticlesModule : Module("particles", "Particle Effects", "Hit particles and particle filters", ModuleCategory.VISUAL, "item:minecraft:blaze_powder", 8, 320, false) {
    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    /** Kinds of particle with a switch each, by vanilla particle type id. */
    enum class Kind(val label: String, vararg val ids: String) {
        RAIN("Rain splashes", "rain", "dripping_water", "falling_water"),
        CRITICALS("Criticals", "crit"),
        ENCHANTED("Enchanted hits", "enchanted_hit"),
        EXPLOSION("Explosions", "explosion", "explosion_emitter", "poof"),
        POTION("Potion effects", "effect", "entity_effect", "instant_effect", "witch"),
        FIREWORK("Fireworks", "firework", "flash"),
        TOTEM("Totem", "totem_of_undying"),
        SMOKE("Smoke", "smoke", "large_smoke", "white_smoke", "campfire_cosy_smoke", "campfire_signal_smoke"),
        FLAME("Flames", "flame", "small_flame", "soul_fire_flame"),
        SPLASH("Water splashes", "splash", "bubble", "bubble_pop", "fishing")
    }

    fun multiplier(): Int = scalar("multiplier", 1).coerceIn(1, 5)
    fun customCrit(): Boolean = flag("crit_color_on", false)
    fun critColor(): Int = scalar("crit_color", 0xFF55FF55.toInt())
    fun shows(kind: Kind): Boolean = flag("show_${kind.name.lowercase()}", true)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Hits"))
        list.add(IntSetting("Hit particles", 1, 5, { multiplier() }, { setScalar("multiplier", it) }, "x")
            .hint("More crit and enchanted particles when you hit something."))
        list.add(BoolSetting("Custom crit color", { customCrit() }, { setFlag("crit_color_on", it) }))
        list.add(ColorSetting("Crit color", false, { critColor() }, { setScalar("crit_color", it) }))
        list.add(SectionSetting("Show"))
        Kind.entries.forEach { kind ->
            list.add(BoolSetting(kind.label, { shows(kind) }, { setFlag("show_${kind.name.lowercase()}", it) }))
        }
    }

    companion object {
        private val byId: Map<String, Kind> = Kind.entries.flatMap { k -> k.ids.map { it to k } }.toMap()

        /** Particle type to its kind, looked up once per type. A type with no kind maps to NONE. */
        private val kinds = ConcurrentHashMap<Any, Any>()
        private val NONE = Any()

        private fun module(): ParticlesModule? = ModuleRegistry.get<ParticlesModule>("particles")?.takeIf { it.active }

        @JvmStatic
        fun kindOf(options: ParticleOptions): Kind? {
            val cached = kinds.getOrPut(options.type) {
                val id = BuiltInRegistries.PARTICLE_TYPE.getKey(options.type)?.path
                byId[id] ?: NONE
            }
            return cached as? Kind
        }

        /** False when this particle's kind is switched off. */
        @JvmStatic
        fun allows(options: ParticleOptions): Boolean {
            val module = module() ?: return true
            val kind = kindOf(options) ?: return true
            return module.shows(kind)
        }

        /** How many extra hit emitters to start for this crit or enchanted hit (0 for none). */
        @JvmStatic
        fun extraHits(options: ParticleOptions): Int {
            val module = module() ?: return 0
            val kind = kindOf(options)
            if (kind != Kind.CRITICALS && kind != Kind.ENCHANTED) return 0
            if (!module.shows(kind)) return 0
            return module.multiplier() - 1
        }

        /** The colour to paint a crit particle (ARGB), or null to leave it as it is. */
        @JvmStatic
        fun critTint(options: ParticleOptions): Int? {
            val module = module() ?: return null
            if (!module.customCrit() || kindOf(options) != Kind.CRITICALS) return null
            return module.critColor()
        }
    }
}
