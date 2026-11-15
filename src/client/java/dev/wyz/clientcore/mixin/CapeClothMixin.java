package dev.wyz.clientcore.mixin;

import net.minecraft.client.renderer.entity.layers.CapeLayer;
import org.spongepowered.asm.mixin.Mixin;
//? if >=26.1 {
/**///?} elif >=1.21.11 {
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.wyz.clientcore.cosmetic.ClothCape;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.EquipmentClientInfo;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

/**
 * Cloth capes (River Plus): for a Plus player, the cape is drawn as the simulated sheet from
 * ClothCape instead of vanilla's rigid plate. Same texture, same anchor on the back, same
 * elytra and chestplate rules as vanilla; everyone else keeps the vanilla cape.
 *
 * 1.21.11 only for now: the render submission API is different on every side of it.
 */
@Mixin(CapeLayer.class)
public abstract class CapeClothMixin {
//? if >=26.1 {
/**///?} elif >=1.21.11 {
    @Shadow
    private boolean hasLayer(ItemStack stack, EquipmentClientInfo.LayerType type) {
        throw new AssertionError();
    }

    @Inject(
        method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/AvatarRenderState;FF)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void clientcore$cloth(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float limbSwing, float limbSwingAmount, CallbackInfo ci) {
        if (state.isInvisible || !state.showCape || state.skin.cape() == null) return;
        if (hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.WINGS)) return;
        if (!ClothCape.isCloth(state.id)) return;
        ci.cancel();

        ClothCape.Sim sim = ClothCape.update(state.id, state.x, state.y, state.z, state.bodyRot, state.isCrouching);
        // Copied now: the geometry is drawn later in the frame, after the next entity may have stepped.
        float[] x = sim.getX().clone();
        float[] y = sim.getY().clone();
        float[] z = sim.getZ().clone();

        pose.pushPose();
        if (hasLayer(state.chestEquipment, EquipmentClientInfo.LayerType.HUMANOID)) {
            pose.translate(0.0F, -0.053125F, 0.06875F);
        }
        @SuppressWarnings("unchecked")
        PlayerModel model = ((RenderLayer<AvatarRenderState, PlayerModel>) (Object) this).getParentModel();
        model.body.translateAndRotate(pose);
        collector.submitCustomGeometry(
            pose,
            RenderTypes.entityCutoutNoCull(state.skin.cape().texturePath()),
            (p, out) -> clientcore$emit(p, out, x, y, z, light)
        );
        pose.popPose();
    }

    @org.spongepowered.asm.mixin.Unique
    private static final float HALF = 0.5F; // half the cape's thickness, px

    // Both faces of the sheet plus its three free edges, in the cape texture's 64x32 layout:
    // outer face at (1,1) 10x16, inner face at (12,1), the edges taken from the outer face's rim.
    @org.spongepowered.asm.mixin.Unique
    private static void clientcore$emit(PoseStack.Pose p, VertexConsumer out, float[] x, float[] y, float[] z, int light) {
        int cols = ClothCape.COLS;
        int rows = ClothCape.ROWS;
        float[] nx = new float[x.length];
        float[] ny = new float[x.length];
        float[] nz = new float[x.length];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = r * cols + c;
                int l = r * cols + Math.max(c - 1, 0), rt = r * cols + Math.min(c + 1, cols - 1);
                int u = Math.max(r - 1, 0) * cols + c, d = Math.min(r + 1, rows - 1) * cols + c;
                float ax = x[rt] - x[l], ay = y[rt] - y[l], az = z[rt] - z[l];
                float bx = x[d] - x[u], by = y[d] - y[u], bz = z[d] - z[u];
                float cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
                float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (len < 1e-5F) { cx = 0; cy = 0; cz = 1; len = 1; }
                nx[i] = cx / len; ny[i] = cy / len; nz[i] = cz / len;
            }
        }
        for (int r = 0; r + 1 < rows; r++) {
            for (int c = 0; c + 1 < cols; c++) {
                int[] q = { r * cols + c, r * cols + c + 1, (r + 1) * cols + c + 1, (r + 1) * cols + c };
                for (int side = 0; side < 2; side++) {
                    float s = side == 0 ? HALF : -HALF;
                    for (int k = 0; k < 4; k++) {
                        int i = q[side == 0 ? k : 3 - k];
                        int cc = i % cols, rr = i / cols;
                        float uPx = side == 0 ? 11F - cc * 10F / (cols - 1) : 12F + cc * 10F / (cols - 1);
                        float vPx = 1F + rr * 16F / (rows - 1);
                        clientcore$vertex(out, p, x[i] + nx[i] * s, y[i] + ny[i] * s, z[i] + nz[i] * s,
                            uPx / 64F, vPx / 32F, light, nx[i] * Math.signum(s), ny[i] * Math.signum(s), nz[i] * Math.signum(s));
                    }
                }
            }
        }
        // Edges: left and right columns, and the bottom row, closing the gap between the faces.
        for (int r = 0; r + 1 < rows; r++) {
            clientcore$edge(out, p, x, y, z, nx, ny, nz, r * cols, (r + 1) * cols, 11.5F, 1F + r * 2F, 11.5F, 3F + r * 2F, light);
            clientcore$edge(out, p, x, y, z, nx, ny, nz, r * cols + cols - 1, (r + 1) * cols + cols - 1, 0.5F, 1F + r * 2F, 0.5F, 3F + r * 2F, light);
        }
        for (int c = 0; c + 1 < cols; c++) {
            int a = (rows - 1) * cols + c, b = a + 1;
            float u0 = 11F - c * 10F / (cols - 1), u1 = 11F - (c + 1) * 10F / (cols - 1);
            clientcore$edge(out, p, x, y, z, nx, ny, nz, a, b, u0, 16.5F, u1, 16.5F, light);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static void clientcore$edge(VertexConsumer out, PoseStack.Pose p, float[] x, float[] y, float[] z,
                                        float[] nx, float[] ny, float[] nz, int a, int b,
                                        float ua, float va, float ub, float vb, int light) {
        // The edge faces outward along the sheet; its normal is just "away from the middle".
        float ex = (x[a] + x[b]) * 0.5F, ey = (y[a] + y[b]) * 0.5F;
        float len = (float) Math.sqrt(ex * ex + ey * ey);
        float enx = len > 1e-5F ? ex / len : 0F, eny = len > 1e-5F ? ey / len : 1F;
        float[][] v = new float[4][];
        v[0] = new float[] { x[a] + nx[a] * HALF, y[a] + ny[a] * HALF, z[a] + nz[a] * HALF, ua, va };
        v[1] = new float[] { x[b] + nx[b] * HALF, y[b] + ny[b] * HALF, z[b] + nz[b] * HALF, ub, vb };
        v[2] = new float[] { x[b] - nx[b] * HALF, y[b] - ny[b] * HALF, z[b] - nz[b] * HALF, ub, vb };
        v[3] = new float[] { x[a] - nx[a] * HALF, y[a] - ny[a] * HALF, z[a] - nz[a] * HALF, ua, va };
        for (float[] vert : v) {
            clientcore$vertex(out, p, vert[0], vert[1], vert[2], vert[3] / 64F, vert[4] / 32F, light, enx, eny, 0F);
        }
    }

    @org.spongepowered.asm.mixin.Unique
    private static void clientcore$vertex(VertexConsumer out, PoseStack.Pose p, float px, float py, float pz,
                                          float u, float v, int light, float nx, float ny, float nz) {
        out.addVertex(p, px / 16F, py / 16F, pz / 16F)
            .setColor(-1)
            .setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY)
            .setLight(light)
            .setNormal(p, nx, ny, nz);
    }
//?}
}
