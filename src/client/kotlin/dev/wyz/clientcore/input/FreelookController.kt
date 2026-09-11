package dev.wyz.clientcore.input

import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft

/**
 * Freelook state shared between the module, the mouse mixin and the camera mixin.
 * While active, mouse input rotates only the detached camera; the player's real
 * rotation (and everything sent to the server) is untouched.
 */
object FreelookController {
    @JvmStatic
    var active: Boolean = false
        private set

    @JvmStatic
    var cameraYaw: Float = 0f

    @JvmStatic
    var cameraPitch: Float = 0f

    private var previousPerspective: CameraType? = null

    fun begin(client: Minecraft) {
        val player = client.player ?: return
        if (active) return
        active = true
        cameraYaw = player.yRot
        cameraPitch = player.xRot
        previousPerspective = client.options.cameraType
        client.options.cameraType = CameraType.THIRD_PERSON_BACK
    }

    fun end(client: Minecraft) {
        if (!active) return
        active = false
        previousPerspective?.let { client.options.cameraType = it }
        previousPerspective = null
    }

    /** Called from the mouse mixin with sensitivity-scaled deltas. */
    @JvmStatic
    fun turn(deltaYaw: Double, deltaPitch: Double) {
        cameraYaw = (cameraYaw + deltaYaw.toFloat())
        cameraPitch = (cameraPitch + deltaPitch.toFloat()).coerceIn(-90f, 90f)
    }
}
