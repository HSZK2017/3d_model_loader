package com.model3d.loader.client.render;

import com.model3d.loader.Model3D;
import com.model3d.loader.resource.ModelLocation;
import com.model3d.loader.scene.ModelMaterial;
import com.model3d.loader.util.Ids;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.Locale;

/**
 * Resolves a material's texture path to a {@link ResourceLocation} the {@code ResourceManager} can
 * answer for.
 *
 * <h2>What this used to be</h2>
 * This class was a GPU cache: it compiled a program, uploaded a VAO/VBO per primitive and
 * reference-counted uploaded textures, for the custom-GL renderer that was replaced by the
 * CPU-skinning path. That renderer is gone and its classes are deleted; what remains is the part the
 * live renderer still calls - {@link #resolveBaseColorId} - with the naming to match.
 *
 * <p>Resolution order follows {@link ModelLocation}: the model's own directory first, so a glTF's
 * {@code ./textures/foo.png} beside the model wins, then the namespace's normal texture root, so an
 * MTL naming {@code textures/glass.png} still finds it. Then, if neither hits, a case-insensitive
 * retry and finally a unique-file-name search upward from the requested directory - both because
 * real exporters write {@code Textures/Glass_Cockpit.jpeg} while Minecraft's lookup is lower-case by
 * construction.
 *
 * <p>Pure lookups: no GL, so it may be called from wherever the renderer resolves materials.
 */
public final class ClientTextureResolver {

    /**
     * Resolves a material's base-colour texture to a {@link ResourceLocation}, or null when it names
     * none or nothing resolves.
     */
    public ResourceLocation resolveBaseColorId(ModelMaterial material, ModelLocation location,
                                                ResourceManager resourceManager) {
        String path = material.baseColorTexture();
        if (path == null || path.isEmpty() || resourceManager == null) {
            return null;
        }
        String relative = normalise(path);
        if (relative.isEmpty()) {
            return null;
        }
        // Null-safe lookups: a texture name out of a model file is untrusted input, and one that
        // cannot be a resource path (capitals are ordinary exporter output, spaces happen) must be a
        // reported miss. Building the location directly is what threw out of the entity renderer and
        // crashed the client - see ModelLocation#inModelRootOrNull.
        ResourceLocation inModel = location.inModelRootOrNull(relative);
        ResourceLocation found = inModel == null ? null : find(resourceManager, inModel);
        if (found == null) {
            ResourceLocation inTextures = location.inTextureRootOrNull(relative);
            if (inTextures != null) {
                found = find(resourceManager, inTextures);
            }
        }
        if (found == null) {
            Model3D.LOGGER.warn("Model3D: material '{}' names texture '{}' which does not resolve "
                            + "under {} or {}; drawing it white",
                    material.name(), path, location.modelRootPath(), location.textureRootPath());
        }
        return found;
    }

    /** Strips a leading {@code ./} and any leading slash: both appear in exported model files. */
    private static String normalise(String path) {
        String cleaned = path.replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        // A reference may climb out of the model directory in a malformed or hostile file.
        // ResourceLocation rejects such a path outright, so neutralise it here and let the normal
        // miss path report it, rather than throwing out of a render call.
        while (cleaned.contains("..")) {
            cleaned = cleaned.replace("..", "__");
        }
        return cleaned;
    }

    /**
     * Finds a resource exactly, then case-insensitively, then by unique file name.
     *
     * <p>The case-insensitive pass exists because real exporters emit {@code Textures/Glass.png}
     * while Minecraft's lookup is case-sensitive by construction. {@link ModelLocation} documents
     * the same fallback for the parsers, so a model that loads at all keeps its textures.
     */
    private static ResourceLocation find(ResourceManager resourceManager, ResourceLocation wanted) {
        if (resourceManager.getResource(wanted).isPresent()) {
            return wanted;
        }
        String lowerPath = wanted.getPath().toLowerCase(Locale.ROOT);
        if (!lowerPath.equals(wanted.getPath())) {
            ResourceLocation lowered = Ids.of(wanted.getNamespace(), lowerPath);
            if (resourceManager.getResource(lowered).isPresent()) {
                return lowered;
            }
        }
        // Last resort: the same file name in an ancestor of the requested directory, bounded to
        // the same namespace so a miss cannot silently pick up another mod's texture.
        int lastSlash = lowerPath.lastIndexOf('/');
        if (lastSlash < 0) {
            return null;
        }
        String fileName = lowerPath.substring(lastSlash + 1);
        String current = lowerPath.substring(0, lastSlash);
        while (current.contains("/")) {
            current = current.substring(0, current.lastIndexOf('/'));
            ResourceLocation candidate = Ids.of(wanted.getNamespace(), current + "/" + fileName);
            if (resourceManager.getResource(candidate).isPresent()) {
                Model3D.LOGGER.info("Model3D: texture {} not found; using {} by file name",
                        wanted, candidate);
                return candidate;
            }
        }
        return null;
    }
}
