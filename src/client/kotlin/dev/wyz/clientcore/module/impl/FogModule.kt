package dev.wyz.clientcore.module.impl

import dev.wyz.clientcore.compat.McId
import dev.wyz.clientcore.mixin.GameRendererPostInvoker
import dev.wyz.clientcore.module.Module
import dev.wyz.clientcore.module.ModuleCategory
import dev.wyz.clientcore.module.ModuleEditorProfile
import dev.wyz.clientcore.module.settings.BoolSetting
import dev.wyz.clientcore.module.settings.IntSetting
import dev.wyz.clientcore.module.settings.SectionSetting
import dev.wyz.clientcore.module.settings.Setting
import net.minecraft.client.Minecraft
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.material.FogType
import kotlin.math.roundToInt
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * How the world looks from a distance: the fog at the edge of render distance, fog in
 * water and lava, and how vivid the colours are.
 *
 * Fog goes through FogMixin. Blindness and Darkness are never touched: seeing through
 * those is not a visual preference, it is a gameplay effect other players rely on.
 *
 * Saturation is vanilla's colour-matrix post shader with the matrix set to the chosen
 * value; the effects in assets/clientcore/post_effect come in 10% steps, finer than
 * anyone can tell apart. Only on 1.21.6 and later, where that shader takes its matrix
 * as a uniform. Vanilla's own post effects (the creeper and spider views in spectator)
 * always win: while one is showing, saturation waits and comes back after.
 */
class FogModule : Module("fog", "Fog & Colour", "Clear fog, richer or softer colours", ModuleCategory.VISUAL, "palette", 0, 0, false) {

    companion object {
        /** Far enough that nothing in render distance is ever inside it. */
        private const val NO_FOG = 1.0e7f
    }

    override val editorProfile: ModuleEditorProfile = ModuleEditorProfile.MINIMAL
    override fun acceptsDraggablePosition(): Boolean = false
    override fun showPositionControlsInEditor(): Boolean = false

    private fun removeDistanceFog() = flag("distance", true)
    private fun liquidClarity() = scalar("liquid", 50).coerceIn(0, 100)
    private fun saturation() = scalar("saturation", 100).coerceIn(0, 200)

    override fun addModuleSettings(list: MutableList<Setting>) {
        list.add(SectionSetting("Fog"))
        list.add(BoolSetting("Remove distance fog", { flag("distance", true) }, { setFlag("distance", it) }))
        list.add(IntSetting("Clearer water and lava", 0, 100, { liquidClarity() }, { setScalar("liquid", it) }, "%"))
//? if >=1.21.6 {
        list.add(SectionSetting("Colour"))
        list.add(IntSetting("Saturation", 0, 200, { saturation() }, { setScalar("saturation", it) }, "%"))
//?}
    }

    /**
     * New start and end for one fog pair, or null to keep vanilla's. [environmental] is the
     * pair that carries water, lava and weather fog; the other is the render-distance edge,
     * which is its own pair from 1.21.6 on.
     */
    fun adjust(start: Float, end: Float, renderDistance: Float, environmental: Boolean): FloatArray? {
        val player = Minecraft.getInstance().player ?: return null
        if (player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS)) return null
        return when (fluidInCamera()) {
            FogType.NONE -> if (removeDistanceFog()) floatArrayOf(NO_FOG, NO_FOG) else null
            FogType.WATER, FogType.LAVA -> {
                val clarity = liquidClarity() / 100f
                if (!environmental || clarity <= 0f || end <= 0f) return null
                val newEnd = end + (maxOf(renderDistance, end) - end) * clarity
                floatArrayOf(start * (newEnd / end), newEnd)
            }
            else -> null
        }
    }

    /** True while the camera is in water or lava. */
    fun inFluid(): Boolean = fluidInCamera().let { it == FogType.WATER || it == FogType.LAVA }

    private fun fluidInCamera(): FogType {
        val client = Minecraft.getInstance()
//? if >=26.2 {
/*        return client.gameRenderer.mainCamera().fluidInCamera
*///?} else {
        return client.gameRenderer.mainCamera.fluidInCamera
//?}
    }

    private fun saturationEffect(percent: Int): McId? {
        val step = ((percent / 10f).roundToInt() * 10).coerceIn(0, 200)
        if (step == 100) return null
//? if >=1.21.9 {
        val name = "saturation_$step"
//?} else {
/*        val name = "saturation_blit_$step"
*///?}
        return McId.fromNamespaceAndPath("clientcore", name)
    }

    private fun isOurs(id: McId?): Boolean =
        id != null && id.namespace == "clientcore" && id.path.startsWith("saturation_")

    /** Runs every client tick, on or off, so switching off takes the effect away. */
    fun syncSaturation(client: Minecraft) {
//? if >=1.21.6 {
        val renderer = client.gameRenderer ?: return
        val current = renderer.currentPostEffect()
        val wanted = if (active && client.level != null) saturationEffect(saturation()) else null
        if (wanted == null) {
            if (isOurs(current)) renderer.clearPostEffect()
            return
        }
        // Someone else's effect is on screen: leave it alone.
        if (current != null && !isOurs(current)) return
        if (current != wanted) (renderer as GameRendererPostInvoker).`clientcore$setPostEffect`(wanted)
//?}
    }
}
