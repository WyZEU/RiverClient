package dev.wyz.clientcore.ui.pause

import dev.wyz.clientcore.mixin.CameraInvoker
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.tan
//? if >=26.1 {
/*import dev.wyz.clientcore.compat.*
*///?}

/**
 * The camera behind River's pause menu: it swings out of your head and round to the front,
 * so you see your own player on the right of the screen beside the menu, and then circles you
 * slowly, like a turntable, for as long as the menu is open (not with animations off).
 *
 * It borrows two transient options for the time it is up, both put back exactly as they were:
 * the perspective (so your player is drawn at all) and hideGui (so the hotbar and HUD are out
 * of the way). Neither is saved to options.txt. It stays up through screens opened from the
 * pause menu (Options and the like), and ends the moment no screen is open or the world is gone.
 *
 * CameraMixin calls [place] every frame after vanilla has set the camera up.
 */
object PauseCamera {
    @JvmStatic
    var active = false
        private set

    /** Seconds the swing out takes. */
    private const val INTRO = 0.55
    /** How far away the camera settles, in blocks (less when a wall is in the way). */
    private const val DISTANCE = 3.3f
    /** How far round from straight in front the camera first settles, toward your left hand. */
    private const val SIDE = -28.0
    private const val PITCH = 10.0f
    /** Where across the screen your player ends up, leaving the left side to the menu. */
    private const val PLAYER_AT = 0.67
    /** The turntable: degrees a second the camera circles you once it has settled. */
    private const val ORBIT = 7.0

    private var startedAt = 0L
    private var savedType: CameraType? = null
    private var savedHideGui = false
    private var startDistance = 0f
    private var startTurn = 0.0

    /** Starts the view for [client]; does nothing without a player you are looking through. */
    @JvmStatic
    fun begin(client: Minecraft) {
        if (active || dev.wyz.clientcore.RiverRuntime.configOrNull()?.pauseCamera == false) return
        val player = client.player ?: return
        if (client.cameraEntity !== player) return
        val type = client.options.cameraType
        savedType = type
        savedHideGui = hudHidden(client)
        // Third person already: swing out from where that camera is, not from inside your head.
        startDistance = if (type.isFirstPerson) 0f else 4f
        startTurn = if (type == CameraType.THIRD_PERSON_FRONT) 180.0 else 0.0
        setHudHidden(client, true)
        startedAt = System.nanoTime()
        active = true
    }

    /** Puts the perspective and HUD back. Safe to call any time. */
    @JvmStatic
    fun end(client: Minecraft) {
        if (!active) return
        active = false
        savedType?.let { client.options.setCameraType(it) }
        savedType = null
        setHudHidden(client, savedHideGui)
    }

    // The HUD's hidden flag (F1): an option up to 26.1, the HUD's own toggle from 26.2.
    private fun hudHidden(client: Minecraft): Boolean {
//? if >=26.2 {
/*        return client.gui.hud.isHidden()
*///?} else {
        return client.options.hideGui
//?}
    }

    private fun setHudHidden(client: Minecraft, hidden: Boolean) {
//? if >=26.2 {
/*        if (client.gui.hud.isHidden() != hidden) client.gui.hud.toggle()
*///?} else {
        client.options.hideGui = hidden
//?}
    }

    /** Ends the view once you are back in the game, or the world has gone. Called every tick and frame. */
    @JvmStatic
    fun check(client: Minecraft) {
        if (active && (client.screen == null || client.level == null || client.player == null || client.cameraEntity !== client.player)) end(client)
    }

    /**
     * Sets this frame's camera. Returns false (and leaves the camera alone) when the view is
     * not up. Runs after vanilla's own setup, so everything here overrides it.
     */
    @JvmStatic
    fun place(camera: CameraInvoker, entity: Entity?, partialTick: Float): Boolean {
        val client = Minecraft.getInstance()
        check(client)
        if (!active || entity == null || entity !== client.player) return false

        val seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0
        val t = (seconds / INTRO).coerceIn(0.0, 1.0)
        val e = 1.0 - (1.0 - t) * (1.0 - t) * (1.0 - t)

        // Swings from behind (your own view) round past your left shoulder to in front of you,
        // then keeps circling. The circling eases in with the swing, so it never jerks.
        val animate = dev.wyz.clientcore.RiverRuntime.configOrNull()?.uiAnimations != false
        val orbit = if (animate) seconds * ORBIT * e else 0.0
        val yaw = entity.getViewYRot(partialTick).toDouble()
        val endTurn = 180.0 + SIDE
        val turn = startTurn + (endTurn - startTurn) * e + orbit
        val bob = if (animate) (sin(seconds * 0.21) * 1.2 * e).toFloat() else 0f
        val pitch = Mth.lerp(e.toFloat(), entity.getViewXRot(partialTick), PITCH + bob)
        val distance = Mth.lerp(e.toFloat(), startDistance, DISTANCE)

        // Out of your head far enough to see yourself: switch to third person so the model draws.
        if (distance > 0.7f && client.options.cameraType.isFirstPerson) client.options.setCameraType(CameraType.THIRD_PERSON_BACK)

        val cameraYaw = (yaw + turn).toFloat()
        camera.`river$setRotation`(cameraYaw, pitch)
        camera.`river$setPosition`(
            Mth.lerp(partialTick.toDouble(), entity.xo, entity.x),
            Mth.lerp(partialTick.toDouble(), entity.yo, entity.y) + entity.eyeHeight,
            Mth.lerp(partialTick.toDouble(), entity.zo, entity.z)
        )
        camera.`river$move`(-camera.`river$getMaxZoom`(distance), 0f, 0f)
        // Then look a little to the right of you, which puts you on the left of the screen.
        camera.`river$setRotation`(cameraYaw + (lookOffset(client) * e).toFloat(), pitch)
        return true
    }

    /** Degrees to turn the view so the player sits at [PLAYER_AT] across the screen. */
    private fun lookOffset(client: Minecraft): Double {
        val window = client.window
        val aspect = window.width.toDouble() / window.height.coerceAtLeast(1)
        val halfFov = Math.toRadians(client.options.fov().get().toDouble() / 2.0)
        val halfWide = tan(halfFov) * aspect
        return Math.toDegrees(atan((1.0 - 2.0 * PLAYER_AT) * halfWide))
    }
}
