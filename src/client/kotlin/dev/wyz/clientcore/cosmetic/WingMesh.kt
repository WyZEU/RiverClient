package dev.wyz.clientcore.cosmetic

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.texture.OverlayTexture
import java.io.DataInputStream

/** Quad poses sampled from the Blockbench animation at 20 frames per second. */
class WingMesh private constructor(
    private val faces: Int,
    private val frames: Int,
    private val duration: Int,
    private val atlasFrameMs: Int,
    private val atlasFrames: Int,
    private val uv: FloatArray,
    private val poses: ShortArray
) {
    fun render(pose: PoseStack.Pose, out: VertexConsumer, light: Int, time: Long) {
        val frame = ((time % duration) * frames / duration).toInt()
        val atlasOffset = (time / atlasFrameMs % atlasFrames).toFloat() / atlasFrames
        var position = frame * faces * VALUES_PER_FACE
        var texture = 0
        repeat(faces) {
            val nx = poses[position++] / 32767f
            val ny = poses[position++] / 32767f
            val nz = poses[position++] / 32767f
            repeat(4) {
                val x = poses[position++] / 4096f
                val y = poses[position++] / 4096f
                val z = poses[position++] / 4096f
                out.addVertex(pose, x, y, z)
                    .setColor(-1)
                    .setUv(uv[texture++], uv[texture++] + atlasOffset)
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(pose, nx, ny, nz)
            }
        }
    }

    companion object {
        const val DIRECTORY = "textures/cosmetics/wings"
        private const val VALUES_PER_FACE = 15

        fun load(id: String): WingMesh {
            val source = checkNotNull(javaClass.getResourceAsStream("/assets/clientcore/$DIRECTORY/$id.bin"))
            return DataInputStream(source.buffered()).use { input ->
                require(input.readInt() == 0x52574E31)
                val faces = input.readUnsignedShort()
                val frames = input.readUnsignedShort()
                val duration = input.readUnsignedShort()
                val width = input.readUnsignedShort()
                val height = input.readUnsignedShort()
                val frameHeight = input.readUnsignedShort()
                val frameMs = input.readUnsignedShort()
                require(faces in 1..128 && frames in 1..300 && duration > 0)
                require(width > 0 && frameHeight > 0 && height >= frameHeight && height % frameHeight == 0 && frameMs > 0)
                val uv = FloatArray(faces * 8) { input.readFloat() }
                val poses = ShortArray(faces * frames * VALUES_PER_FACE) { input.readShort() }
                WingMesh(faces, frames, duration, frameMs, height / frameHeight, uv, poses)
            }
        }
    }
}
