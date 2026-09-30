package com.model3d.loader.client.render;

import com.model3d.loader.Model3D;
import com.model3d.loader.client.ClientModelManager;
import com.model3d.loader.api.ModelInstance;
import com.model3d.loader.api.ModelScale;
import com.model3d.loader.common.entity.TestModelEntity;
import com.model3d.loader.resource.ModelLoadService;
import com.model3d.loader.util.Ids;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.PigModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Draws a {@link TestModelEntity}: the loaded 3D model when it has one, a vanilla pig when it
 * does not.
 *
 * <h2>Why not delegate to {@code PigRenderer}</h2>
 * The obvious implementation - hold a cached {@code PigRenderer} and call it for the fallback -
 * does not compile, and it is worth recording why so nobody tries it again.
 * {@code net.minecraft.client.renderer.entity.PigRenderer} is declared
 * {@code class PigRenderer extends MobRenderer<Pig, PigModel<Pig>>} (verified in
 * {@code forge-1.20.1-47.4.16_mapped_parchment_2023.09.03-1.20.1-sources.jar},
 * {@code PigRenderer.java} line 12), and its constructor is
 * {@code PigRenderer(EntityRendererProvider.Context)} (line 15). Its entity type parameter is
 * fixed to {@code Pig}, so it cannot render a {@link TestModelEntity} - which extends
 * {@code PathfinderMob}, not {@code Pig} - and Java's generics have no way to retarget it. Forcing
 * it with a raw type or an unchecked cast would compile and then fail at runtime on the first
 * {@code Pig}-specific access.
 *
 * <p>What <i>is</i> reusable is the part that carries the visual identity: the vanilla
 * {@link PigModel}, which is generic in {@code T extends Entity} (verified: {@code PigModel.java}
 * line 15, {@code public class PigModel<T extends Entity> extends QuadrupedModel<T>}), and the
 * vanilla pig texture. So this renderer is a {@code MobRenderer} of its own, using:
 * <ul>
 *   <li>{@code new PigModel<>(context.bakeLayer(ModelLayers.PIG))} - the exact expression
 *       {@code PigRenderer} line 16 uses, against the same {@code ModelLayers.PIG} layer, so the
 *       baked geometry is byte-for-byte vanilla's;</li>
 *   <li>{@code textures/entity/pig/pig.png} - {@code PigRenderer}'s own
 *       {@code PIG_LOCATION} constant (line 13).</li>
 * </ul>
 * That is why the fallback looks like a pig rather than merely being pig-shaped.
 *
 * <p>The saddle layer {@code PigRenderer} also adds is deliberately omitted: this entity is never
 * saddled, so the layer could never draw anything and would only cost a second model bake.
 *
 * <p>Render thread only, as all entity renderers are.
 */
public class RenderTestModelEntity extends MobRenderer<TestModelEntity, PigModel<TestModelEntity>> {

    /** Displayed when the entity has no model. Vanilla's own pig texture. */
    private static final ResourceLocation PIG_TEXTURE =
            Ids.parse("textures/entity/pig/pig.png");

    /**
     * Returned by {@link #getTextureLocation} while a model is drawn.
     *
     * <p>The model's real textures are bound by the CPU render path through this mod's own program, so
     * this value never reaches the GPU - but it must not be null, because
     * {@code EntityRenderDispatcher} and several layers dereference it unconditionally. Vanilla's
     * missing-texture atlas is the honest choice: if something ever does draw this renderer's
     * texture, it should look obviously wrong rather than quietly plausible.
     */
    private static final ResourceLocation STAND_IN_TEXTURE =
            TextureManager.INTENTIONAL_MISSING_TEXTURE;

    private static final float SHADOW_RADIUS = 0.7F;

    /** {@code -Dmodel3d.traceRender=true}: report every call into this renderer. */
    private static final boolean TRACE = Boolean.getBoolean("model3d.traceRender");

    public RenderTestModelEntity(EntityRendererProvider.Context context) {
        super(context, new PigModel<>(context.bakeLayer(ModelLayers.PIG)), SHADOW_RADIUS);
    }

    @Override
    public ResourceLocation getTextureLocation(TestModelEntity entity) {
        return entity.hasModel() ? STAND_IN_TEXTURE : PIG_TEXTURE;
    }

    @Override
    public void render(TestModelEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int packedLight) {
        // Opt-in trace, because "was my render method called at all" is the first question when an
        // entity is visible as a shadow and nothing else - and it is the one question that cannot be
        // answered from anywhere but inside this method. To stderr as well as the log: this is the
        // diagnostic for a case where the game may not survive to flush its log.
        if (TRACE) {
            String line = "Model3D trace: RenderTestModelEntity.render entity=" + entity.getId()
                    + " hasModel=" + entity.hasModel() + " modelId=" + entity.modelId()
                    + " hasInstance=" + (ClientModelManager.get().instanceFor(entity) != null)
                    + " yaw=" + entityYaw + " partial=" + partialTick
                    + " visible=" + entity.isInvisible() + " alive=" + entity.isAlive();
            System.err.println(line);
            Model3D.LOGGER.info(line);
        }
        if (!entity.hasModel()) {
            // No model: vanilla's pig path, unchanged - its own rotations, walk cycle, hurt flash
            // and layers, so the marker is indistinguishable from a real pig.
            super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
            return;
        }

        ModelInstance instance = ClientModelManager.get().instanceFor(entity);
        if (instance == null) {
            // The model is named but did not resolve. Falling back to the pig is the point of the
            // fallback: an entity whose aircraft failed to load is still visible and still marks
            // its position, instead of being an invisible hole in the world.
            super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
            return;
        }

        poseStack.pushPose();
        try {
            // Mirrors the placement part of LivingEntityRenderer#render: yaw about the entity's
            // body orientation, then Minecraft's model-space flip, then the entity's own scale
            // hook. The model's scale, pivot and yaw offset are NOT applied here - they live in
            // ModelInstance#rootTransform and are applied inside the shader, so the animation's
            // joint transforms and the placement cannot drift apart.
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - entityYaw));
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            scale(entity, poseStack, partialTick);

            // Minecraft's own vertex pipeline, through the buffer the entity dispatcher supplied.
            boolean drew = ClientModelManager.get().vanillaRenderer()
                    .draw(instance, poseStack, entity.modelId(), buffer, packedLight,
                            OverlayTexture.NO_OVERLAY);
            if (!drew) {
                // The instance exists but nothing was drawn - no geometry, or the mesh could not
                // be built. Report it once per occurrence at debug level rather than per frame;
                // the render path logs the real reason.
                com.model3d.loader.Model3D.LOGGER.debug(
                        "Model3D: entity {} has model {} but the render path drew nothing",
                        entity.getId(), instance.instanceName());
            }
        } finally {
            // In a finally-block because the render path asserts on GL and a failed shader compile
            // throws: an unbalanced pose stack would then corrupt every later entity in the frame,
            // which is a far worse symptom than the failure that caused it.
            poseStack.popPose();
        }
    }

    /**
     * Expands the culling box when a model is attached.
     *
     * <p>The entity's own bounding box is a mob-sized box; the model is scaled from its own units
     * into blocks by {@code ModelInstance#rootTransform} and can be several blocks across. Vanilla
     * culls on the entity's box, so a camera angle that puts the box off-screen would discard the
     * entity while the model was still filling the screen - a model that vanishes when you look at
     * it from the wrong side. The box is inflated by the model's own scaled extent rather than by a
     * guessed constant, and the shortest path is taken when inflation would overflow.
     */
    @Override
    public boolean shouldRender(TestModelEntity entity, Frustum camera, double camX, double camY,
                                double camZ) {
        if (!entity.hasModel() || !entity.shouldRender(camX, camY, camZ)) {
            // Either no model (vanilla's pig, vanilla's box) or vanilla's own reject.
            if (!entity.hasModel()) {
                return super.shouldRender(entity, camera, camX, camY, camZ);
            }
            return false;
        }
        AABB box = entity.getBoundingBoxForCulling();
        float inflate = modelRadiusBlocks(entity);
        if (Float.isFinite(inflate) && inflate > 0.0f && box.getSize() < 1.0E7D) {
            box = box.inflate(inflate);
        }
        return camera.isVisible(box);
    }

    /**
     * Roughly the model's half-extent in blocks, or 0 when it cannot be determined.
     *
     * <p>Half the scaled longest extent: the model is scaled so its longest axis measures
     * {@code scale * longestExtent} blocks, and half of that covers a pivot at the centre. The
     * handle is only consulted when it is already loaded ({@code peekClient}), because culling must
     * not trigger a model load - that would parse a file during a visibility test.
     */
    private static float modelRadiusBlocks(TestModelEntity entity) {
        ResourceLocation modelId = entity.modelId();
        if (modelId == null) {
            return 0.0f;
        }
        var handle = com.model3d.loader.resource.ModelLoadService.INSTANCE.peekClient(modelId);
        if (handle == null || handle.scene() == null) {
            return 0.0f;
        }
        float scale = entity.modelScale();
        if (!(scale > 0.0f)) {
            scale = com.model3d.loader.api.ModelScale.forHandle(handle);
        }
        return handle.scene().longestExtent() * scale * 0.5f;
    }
}
