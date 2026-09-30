package com.model3d.loader.client.render;

import com.model3d.loader.Model3D;
import com.model3d.loader.api.ModelInstance;
import com.model3d.loader.resource.ModelLoadService;
import com.model3d.loader.resource.ModelLocation;
import com.model3d.loader.scene.ModelImage;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.scene.ModelNode;
import com.model3d.loader.scene.ModelScene;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ResourceManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;

/**
 * Resolves a model instance's materials and hands the draw to the CPU-skinning render path.
 *
 * <h2>What this class is, and what it is not</h2>
 * This is the entry point the entity renderer calls. It answers the questions that need Minecraft
 * state - which texture each material resolves to, whether an image is embedded - and delegates the
 * geometry to {@link ModelCpuRenderPath}, which skins on the CPU, streams one dynamic VBO and draws
 * with this mod's own {@code model_cpu} shader pair. It used to be described as drawing "through
 * Minecraft's own vertex pipeline"; that was the second of three renderers this project built, and
 * the description survived the replacement. What is true now: the CPU path owns the program and the
 * draw, and the GL state it touches is saved and restored by {@code GlStateGuard}.
 *
 * <h2>Why the skinning is on the CPU</h2>
 * The first renderer uploaded {@code uJointMatrices} and let the vertex shader skin. That needs a
 * custom program plus a mesh per skinning variant, and it is why three of the delivered fixtures
 * needed a second copy of every mesh. Skinning per vertex instead costs linear time once per
 * primitive per frame and removes the variant entirely.
 *
 * <h2>What is not applied where</h2>
 * The instance's root transform - scale, yaw offset, pivot - is <b>not</b> baked into positions here.
 * The caller applies it through {@code InstanceTransform} inside the CPU path, and the entity's own
 * placement is on the {@link PoseStack}, so applying either twice would double it. Node transforms
 * for rigid primitives are applied there too, from {@link ModelNode#globalTransform()}.
 */
public final class VanillaModelRenderer {

    /** Whether to report per-model draw decisions; opt-in. */
    private static final boolean TRACE = Boolean.getBoolean("model3d.traceDraw");

    private final ClientTextureResolver textureResolver;

    /** Registers a model's embedded images so the draw can bind them by name. */
    private final EmbeddedTextures embedded = new EmbeddedTextures();

    public VanillaModelRenderer(ClientTextureResolver textureResolver) {
        this.textureResolver = textureResolver;
    }

    /**
     * Frees every GPU resource the live path owns: one VAO+VBO per model id, the compiled program,
     * and every embedded texture registered with the texture manager.
     *
     * <p>The single entry point the client's reload/unload teardown calls, because everything it
     * frees is behind this class: a registered {@code DynamicTexture} keeps its GL object alive
     * until it is released, the meshes live in a static map keyed by model id, and the program is
     * rebuilt on the next draw when it is deleted here.
     */
    public void releaseGpuResources() {
        ModelCpuRenderPath.disposeAll();
        embedded.releaseAll();
    }

    /**
     * Frees the GPU resources of <b>one</b> model: its VAO and stream buffer.
     *
     * <p>Bulk teardown is not enough on its own. The mesh map is keyed by model id and lives until a
     * resource reload or a level unload, so a client that sees many models - the model folder is a
     * drop-in directory, and the viewer can spend an hour cycling through it - keeps one VAO and VBO
     * per model it has ever drawn. Nothing in the scene knows how many entities still use a model, so
     * the release has to come from whoever owns that decision: the client's model cache calls this
     * when it evicts a model's last instance.
     *
     * <p>Safe to call for a model that was never drawn: the map simply has no entry.
     */
    public void releaseModel(ResourceLocation modelId) {
        if (modelId != null) {
            ModelCpuRenderPath.dispose(modelId.toString());
        }
    }

    /**
     * Draws {@code instance} with the placement already on {@code poseStack}.
     *
     * @return true when at least one primitive was submitted
     */
    public boolean draw(ModelInstance instance, PoseStack poseStack, ResourceLocation modelId,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (instance == null || instance.scene() == null || poseStack == null || modelId == null
                || bufferSource == null) {
            return false;
        }
        var handle = ModelLoadService.INSTANCE.peekClient(modelId);
        if (handle == null) {
            return false;
        }
        ModelScene scene = instance.scene();
        ModelDrawList drawList = ModelDrawList.build(scene);
        if (drawList.drawableCount() == 0) {
            return false;
        }

        // Textures are resolved to locations here, not to uploaded GL names: the CPU path binds
        // whatever name the texture manager holds, and the shared lookup keeps one resolution order
        // and one warning per miss.
        //
        // An embedded image has no file to resolve to - "embedded/image0" is a reserved scheme, not a
        // path - so those bytes are registered with the texture manager instead. That is what makes a
        // texture living inside a .glb bindable at all.
        ModelLocation location = ModelLocation.of(modelId);
        ResourceManager resourceManager = Minecraft.getInstance().getResourceManager();
        ModelMaterial[] materials = scene.materials();
        ResourceLocation[] resolved = new ResourceLocation[materials.length];
        String modelKey = modelId.toString();
        ResourceLocation fallbackTexture = embedded.white();
        for (int i = 0; i < resolved.length; i++) {
            ModelMaterial entry = material(scene, i);
            String path = entry.baseColorTexture();
            // The descriptor's texture aliases, applied here because this is the only place that
            // knows the path "as written in the model file" - the key the alias map is documented
            // against. Until now it was parsed, documented in the README, and read by nothing.
            String aliased = handle.descriptor().textureAlias(path);
            if (aliased != null && !aliased.equals(path) && TRACE) {
                Model3D.LOGGER.info("Model3D trace: {} material[{}] '{}' texture alias '{}' -> '{}'",
                        modelKey, i, entry.name(), path, aliased);
            }
            path = aliased;
            boolean isEmbedded = ModelImage.isEmbedded(path);
            if (isEmbedded) {
                ModelImage image = scene.embeddedImage(path);
                resolved[i] = image == null ? null
                        : embedded.locationFor(modelKey, image.name(), image.data());
            } else {
                resolved[i] = textureResolver.resolveBaseColorId(entry, location, resourceManager);
            }
            if (resolved[i] == null && path != null && !path.isEmpty()) {
                // A material that names a texture which does not resolve is drawn white - correct-looking
                // and completely misleading, because the model appears untextured rather than broken.
                // Reported at WARN so it is visible without debug logging enabled, which is how a whole
                // investigation was once misled by DEBUG lines that were never written.
                Model3D.LOGGER.warn("Model3D: {} material[{}] '{}' names texture '{}' which resolved to"
                                + " nothing; drawing it white (embedded={}, scene holds {} image(s))",
                        modelKey, i, entry.name(), path, isEmbedded, scene.embeddedImages().size());
            } else if (TRACE) {
                Model3D.LOGGER.info("Model3D trace: {} material[{}] '{}' path='{}' embedded={}"
                                + " sceneImages={} -> resolved={}",
                        modelKey, i, entry.name(), path, isEmbedded, scene.embeddedImages().size(),
                        resolved[i]);
            }
        }

        PoseStack.Pose pose = poseStack.last();
        // The instance's scale, yaw offset and pivot. The custom shader applied this through
        // uModelView; with the vanilla pipeline it has to be applied to the vertices instead - see
        // InstanceTransform for what dropping it looked like on screen.
        InstanceTransform instanceTransform = InstanceTransform.of(instance.rootTransform(),
                handle.descriptor().mirror());
        float[] jointMatrices = instance.jointMatrices();
        int jointCount = scene.isSkinned() && scene.skins().length > 0
                ? scene.skins()[0].jointCount() : 0;

        // The draw list built above is passed down rather than rebuilt inside the CPU path: it is
        // derived from the scene alone, and building it twice per entity per frame walked the node
        // tree twice for the same answer.
        //
        // fallbackTexture is the registered 1x1 white texture, bound for a material that names no
        // image at all - without it the draw binds GL texture 0, which samples black, and the model
        // is invisible however correct its geometry is.
        boolean drew = ModelCpuRenderPath.draw(instance, poseStack, modelId, drawList, resolved,
                fallbackTexture, packedLight, packedOverlay, instanceTransform, jointMatrices,
                jointCount);
        if (TRACE) {
            Model3D.LOGGER.info("Model3D: {} -> CPU path drew={}", modelId, drew);
        }
        return drew;
    }

    /** The material at {@code materialIndex}, or the default material for a missing/out-of-range one. */
    private static ModelMaterial material(ModelScene scene, int materialIndex) {
        ModelMaterial[] materials = scene.materials();
        if (materialIndex >= 0 && materialIndex < materials.length && materials[materialIndex] != null) {
            return materials[materialIndex];
        }
        return ModelMaterial.defaultMaterial();
    }
}
