package com.model3d.loader.client.render;

import com.model3d.loader.Model3D;
import com.model3d.loader.util.Ids;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Registers a model's embedded images as textures Minecraft can reference by name.
 *
 * <h2>The problem</h2>
 * A glTF or GLB carries its textures <i>inside</i> the model file. The old renderer decoded those bytes
 * and uploaded them straight to GL, which worked because it owned the sampler: it bound its own texture
 * and told its own shader to read it. Minecraft's render pipeline cannot do that. A {@code RenderType}
 * refers to a texture by {@link ResourceLocation} and asks the {@code TextureManager} for it, and a
 * location that is not registered resolves to the magenta-and-black "missing texture" pattern - which is
 * exactly what a model with embedded textures looked like the first time it was drawn this way: correct
 * geometry, every polygon visible, wearing the missing-texture checkerboard.
 *
 * <p>So the bytes are handed to the texture manager instead. {@code TextureManager.register} puts a
 * runtime-created texture in its map, and {@code getTexture} returns a registered entry without
 * consulting the resource pack at all - which is what makes a texture that exists only as bytes inside a
 * model file referenceable by name.
 *
 * <h2>Naming</h2>
 * Registered under this mod's namespace, with the model's name and the image's own name in the path:
 * {@code model3d:embedded/<model>/<image>}. The model name is part of it so two models carrying an
 * image called {@code image0} - which is the default name in every Blender export - cannot collide.
 *
 * <h2>Ownership</h2>
 * Registered textures must be released when their model is, or the texture manager holds the GL object
 * forever. {@link #release(String)} does that, and the caller is expected to run it whenever a model's
 * GPU resources are dropped.
 */
final class EmbeddedTextures {

    /** Namespace path segment for every texture this class registers. */
    private static final String PREFIX = "embedded/";

    /** Model identifier to the locations registered for it. */
    private final Map<String, Map<String, ResourceLocation>> byModel = new HashMap<>();

    /** The 1x1 white texture's location, registered on first use. */
    private ResourceLocation whiteLocation;

    /**
     * A 1x1 opaque white texture, registered so the draw can bind something for a material that
     * names no image.
     *
     * <h2>Why this is not vanilla's "intentional missing" texture</h2>
     * A material with no texture must still have a texture bound: binding texture object 0 leaves the
     * sampler incomplete, which returns black, and the model draws as a silhouette while the log says
     * it is drawn white. The obvious stand-in - {@code TextureManager.INTENTIONAL_MISSING_TEXTURE} - is
     * the wrong choice for the opposite reason: that is the magenta-and-black checkerboard, so a model
     * whose materials name no image at all renders as enormous checkerboard geometry and every observer
     * reads that as "the mesh failed to load" when nothing is wrong except the fallback.
     *
     * <p>White is the neutral multiplier: with it the material shows the lighting of where it stands,
     * not a colour it does not have. It is <b>not</b> the material's {@code baseColorFactor} - that is
     * parsed and carried on {@code ModelMaterial} but is not applied by the live draw yet.
     */
    ResourceLocation white() {
        if (whiteLocation == null) {
            NativeImage image = new NativeImage(NativeImage.Format.RGBA, 1, 1, false);
            image.setPixelRGBA(0, 0, 0xFFFFFFFF);
            whiteLocation = Ids.of(Model3D.MOD_ID, "embedded/_white");
            Minecraft.getInstance().getTextureManager().register(whiteLocation,
                    new DynamicTexture(image));
            Model3D.LOGGER.info("Model3D: registered the 1x1 white fallback at {}", whiteLocation);
        }
        return whiteLocation;
    }

    /**
     * Returns a registered location for {@code image}'s bytes, registering it on first use.
     *
     * @param modelKey the model's id as a string, used to keep models' image names apart
     * @param image    the image name inside the model file, e.g. {@code image0}
     * @param data     the encoded image bytes
     * @return the location to give a {@code RenderType}, or null when the bytes cannot be decoded
     */
    ResourceLocation locationFor(String modelKey, String image, byte[] data) {
        Map<String, ResourceLocation> forModel = byModel.computeIfAbsent(modelKey, key -> new HashMap<>());
        ResourceLocation existing = forModel.get(image);
        if (existing != null) {
            return existing;
        }
        if (data == null || data.length == 0) {
            return null;
        }
        NativeImage decoded = null;
        try {
            // Decoding goes through the same ImageDecode the old path used, so JPEG, GIF and BMP work
            // here for the same reason they work there - and so a decode bug is fixed once.
            decoded = ImageDecode.toNativeImage(data);
            if (decoded == null) {
                Model3D.LOGGER.warn("Model3D: embedded image '{}' of {} could not be decoded; the "
                        + "material using it falls back to the 1x1 white texture", image, modelKey);
                return null;
            }
            ResourceLocation location = buildLocation(modelKey, image);
            DynamicTexture texture = new DynamicTexture(decoded);
            // DynamicTexture keeps the image it was given and uploads it; the caller keeps ownership of
            // the NativeImage, so closing it here after registration is correct and avoids holding a
            // second copy of a texture that can be megabytes.
            decoded = null;
            Minecraft.getInstance().getTextureManager().register(location, texture);
            forModel.put(image, location);
            Model3D.LOGGER.info("Model3D: registered embedded texture {} for {}", location, modelKey);
            return location;
        } catch (Throwable t) {
            Model3D.LOGGER.warn("Model3D: registering embedded image '{}' of {} failed: {}",
                    image, modelKey, t.toString());
            return null;
        } finally {
            if (decoded != null) {
                decoded.close();
            }
        }
    }

    /**
     * Frees every texture registered for a model. Idempotent.
     *
     * <p>Called when a model's GPU resources are released. A texture left registered would keep its GL
     * object alive for the rest of the session, and a reload-heavy session would accumulate them.
     */
    void release(String modelKey) {
        Map<String, ResourceLocation> forModel = byModel.remove(modelKey);
        if (forModel == null) {
            return;
        }
        for (ResourceLocation location : forModel.values()) {
            try {
                AbstractTexture texture = Minecraft.getInstance().getTextureManager()
                        .getTexture(location);
                if (texture != null) {
                    texture.close();
                }
                Minecraft.getInstance().getTextureManager().release(location);
            } catch (Throwable t) {
                Model3D.LOGGER.debug("Model3D: releasing embedded texture {} failed: {}",
                        location, t.toString());
            }
        }
    }

    /** Drops every registration, for a full level unload. */
    void releaseAll() {
        for (String modelKey : new java.util.ArrayList<>(byModel.keySet())) {
            release(modelKey);
        }
    }

    /**
     * Builds the location an image is registered under.
     *
     * <p>Sanitised rather than used raw: a model name or image name may contain characters that are legal
     * in a file name and illegal in a resource path, and {@code ResourceLocation}'s constructor throws on
     * those. Normalising here means a model called "Su-30 Flanker.glb" still gets a usable location
     * instead of failing to render its textures.
     */
    private static ResourceLocation buildLocation(String modelKey, String image) {
        String path = PREFIX + sanitise(modelKey) + "/" + sanitise(image);
        return com.model3d.loader.util.Ids.of(Model3D.MOD_ID, path);
    }

    /** Lower-cases and replaces anything outside {@code [a-z0-9/._-]} with an underscore. */
    private static String sanitise(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = Character.toLowerCase(raw.charAt(i));
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/';
            out.append(allowed ? c : '_');
        }
        String collapsed = out.toString().replaceAll("_{2,}", "_");
        return collapsed.isEmpty() ? "unnamed" : collapsed;
    }

}
