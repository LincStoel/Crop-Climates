package com.worldtraveler.cropclimates.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.worldtraveler.cropclimates.CropClimates;
import com.worldtraveler.cropclimates.entity.HygrometerEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws the hygrometer the way {@code ItemFrameRenderer} draws its frame: a
 * standalone block model against the wall, plus a needle model rotated about
 * the dial's centre. The needle sweeps 120 degrees, dry on the left and wet on
 * the right, and eases toward the synced humidity rather than snapping.
 */
public class HygrometerRenderer extends EntityRenderer<HygrometerEntity> {

    public static final ModelResourceLocation FACE_MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "entity/hygrometer_face"));
    public static final ModelResourceLocation NEEDLE_MODEL = ModelResourceLocation.standalone(
            ResourceLocation.fromNamespaceAndPath(CropClimates.MOD_ID, "entity/hygrometer_needle"));

    /** Dial centre in model space (block pixels / 16). */
    private static final float PIVOT = 7.625F / 16.0F;
    private static final float SWEEP_DEGREES = 120.0F;

    private final BlockRenderDispatcher blockRenderer;
    private final Map<HygrometerEntity, Float> shownAngle = new WeakHashMap<>();

    public HygrometerRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.blockRenderer = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(HygrometerEntity entity, float entityYaw, float partialTicks, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight) {
        super.render(entity, entityYaw, partialTicks, poseStack, buffer, packedLight);
        if (entity.isInvisible()) {
            return;
        }
        poseStack.pushPose();
        Direction direction = entity.getDirection();
        poseStack.translate(direction.getStepX() * 0.46875, 0.0, direction.getStepZ() * 0.46875);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entity.getYRot()));
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        var models = blockRenderer.getBlockModelShaper().getModelManager();
        renderModel(poseStack, buffer, models.getModel(FACE_MODEL), packedLight);

        float target = (Mth.clamp(entity.humidity(), 0.0F, 1.0F) - 0.5F) * SWEEP_DEGREES;
        float angle = shownAngle.getOrDefault(entity, target);
        angle += (target - angle) * 0.1F;
        shownAngle.put(entity, angle);

        poseStack.translate(PIVOT, PIVOT, 0.0F);
        poseStack.mulPose(Axis.ZP.rotationDegrees(angle));
        poseStack.translate(-PIVOT, -PIVOT, 0.0F);
        renderModel(poseStack, buffer, models.getModel(NEEDLE_MODEL), packedLight);
        poseStack.popPose();
    }

    private void renderModel(PoseStack poseStack, MultiBufferSource buffer, BakedModel model, int packedLight) {
        blockRenderer.getModelRenderer().renderModel(poseStack.last(), buffer.getBuffer(Sheets.cutoutBlockSheet()),
                null, model, 1.0F, 1.0F, 1.0F, packedLight, OverlayTexture.NO_OVERLAY);
    }

    @Override
    public ResourceLocation getTextureLocation(HygrometerEntity entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
