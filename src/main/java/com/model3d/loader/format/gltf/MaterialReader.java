package com.model3d.loader.format.gltf;

import com.model3d.loader.Model3D;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.json.JsonArray;
import com.model3d.loader.format.json.JsonObject;
import com.model3d.loader.scene.ModelMaterial;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads {@code materials[]}, and the {@code textures[] -> images[]} chain behind each texture slot.
 *
 * <p>The chain matters because a material only ever names a <b>texture</b> index. Resolving
 * {@code textures[t].source -> images[i]} through {@link ImageTable} is what turns a material into
 * something the renderer can use, and each link can be broken in a different way: a texture index
 * can be out of range, a texture can have no {@code source} (it is supplied by an extension such as
 * {@code KHR_texture_basisu}), and the image behind it can be embedded in the file rather than
 * referenced. The first two fail or warn here; the third is a first-class outcome - an embedded image
 * resolves to its reserved {@code embedded/<name>} path and is carried on the scene as bytes.
 *
 * <p>Materials that a primitive does not name get {@link ModelMaterial#defaultMaterial()} appended
 * to the array, so {@code ModelPrimitive.materialIndex} can stay non-negative while the file's own
 * material indices keep their meaning.
 */
final class MaterialReader {

    private final JsonArray materials;
    private final JsonArray textures;
    private final ImageTable images;

    /**
     * Non-zero {@code texCoord} sets already reported. The warning names the material that first
     * asked for a set, so a 21-material file warns once per set instead of once per material.
     */
    private final Set<Integer> reportedTexCoords = new LinkedHashSet<>();

    MaterialReader(JsonObject root, ImageTable images) throws ModelParseException {
        this.materials = root.getArray("materials");
        this.textures = root.getArray("textures");
        this.images = images;
    }

    /** How many materials the file itself declares. */
    int declaredCount() {
        return materials == null ? 0 : materials.size();
    }

    /**
     * Reads every declared material, in file order.
     *
     * @param appendDefault true when some primitive names no material; the default is then appended
     *                      at index {@link #declaredCount()} so file indices are unchanged
     */
    ModelMaterial[] read(boolean appendDefault) throws ModelParseException {
        int declared = declaredCount();
        ModelMaterial[] out = new ModelMaterial[declared + (appendDefault ? 1 : 0)];
        for (int i = 0; i < declared; i++) {
            out[i] = readMaterial(i);
        }
        if (appendDefault) {
            out[declared] = ModelMaterial.defaultMaterial();
        }
        return out;
    }

    private ModelMaterial readMaterial(int index) throws ModelParseException {
        String element = "materials[" + index + "]";
        JsonObject material = materials.requireObject(index);
        String name = material.getString("name");
        ModelMaterial.Builder builder = ModelMaterial.builder(name == null
                ? "material[" + index + "]" : name);

        JsonObject pbr = material.getObject("pbrMetallicRoughness");
        if (pbr != null) {
            float[] baseColor = pbr.getFloatArray("baseColorFactor");
            if (baseColor != null) {
                requireComponents(baseColor, 4, element, "pbrMetallicRoughness.baseColorFactor");
                builder.baseColorFactor(baseColor[0], baseColor[1], baseColor[2], baseColor[3]);
            }
            builder.metallicFactor(pbr.getFloat("metallicFactor", 1.0f));
            builder.roughnessFactor(pbr.getFloat("roughnessFactor", 1.0f));
            builder.baseColorTexture(texturePath(pbr.getObject("baseColorTexture"), "baseColorTexture",
                    element));
            builder.metallicRoughnessTexture(texturePath(pbr.getObject("metallicRoughnessTexture"),
                    "metallicRoughnessTexture", element));
        }

        JsonObject normal = material.getObject("normalTexture");
        if (normal != null) {
            builder.normalTexture(texturePath(normal, "normalTexture", element),
                    normal.getFloat("scale", 1.0f));
        }
        JsonObject occlusion = material.getObject("occlusionTexture");
        if (occlusion != null) {
            builder.occlusionTexture(texturePath(occlusion, "occlusionTexture", element),
                    occlusion.getFloat("strength", 1.0f));
        }
        JsonObject emissive = material.getObject("emissiveTexture");
        if (emissive != null) {
            builder.emissiveTexture(texturePath(emissive, "emissiveTexture", element));
        }
        float[] emissiveFactor = material.getFloatArray("emissiveFactor");
        if (emissiveFactor != null) {
            requireComponents(emissiveFactor, 3, element, "emissiveFactor");
            builder.emissiveFactor(emissiveFactor[0], emissiveFactor[1], emissiveFactor[2]);
        }

        String alphaMode = material.getString("alphaMode");
        if (alphaMode != null) {
            switch (alphaMode) {
                case "OPAQUE":
                    builder.alphaMode(ModelMaterial.AlphaMode.OPAQUE);
                    break;
                case "MASK":
                    builder.alphaMode(ModelMaterial.AlphaMode.MASK);
                    break;
                case "BLEND":
                    builder.alphaMode(ModelMaterial.AlphaMode.BLEND);
                    break;
                default:
                    throw ModelParseException.at(element, "alphaMode '" + alphaMode
                            + "' is not one of OPAQUE, MASK or BLEND");
            }
        }
        builder.alphaCutoff(material.getFloat("alphaCutoff", 0.5f));
        builder.doubleSided(material.getBoolean("doubleSided", false));
        return builder.build();
    }

    /**
     * Resolves a {@code textureInfo} object to a model-relative image path, or null when the slot is
     * absent or unusable. An unusable slot is always reported: silently leaving a surface untextured
     * is the failure mode this loader exists to avoid.
     */
    /**
     * Resolves a {@code textureInfo} object to a material texture path, or null when the slot is
     * absent or unusable.
     *
     * <p>An unusable slot is always reported. That is the difference between "this surface has no
     * texture, by design" and "this surface lost its texture while loading": the first is silent, the
     * second names the material, the slot and the reason.
     */
    private String texturePath(JsonObject info, String slot, String element)
            throws ModelParseException {
        if (info == null) {
            return null;
        }
        int texCoord = info.getInt("texCoord", 0);
        if (texCoord != 0 && reportedTexCoords.add(texCoord)) {
            // Once per distinct set: the model carries a single UV set per vertex, so whatever the
            // file asked for, the texture samples TEXCOORD_0 and can land in the wrong place.
            Model3D.LOGGER.warn("{}: {} asks for TEXCOORD_{}, but this loader carries a single UV set "
                    + "per vertex and always samples TEXCOORD_0; that texture may be mapped with the "
                    + "wrong coordinates", element, slot, texCoord);
        }
        if (textures == null) {
            throw ModelParseException.at(element, slot + ".index " + info.getInt("index", 0)
                    + " out of range (the file declares no textures)");
        }
        int textureIndex = info.requireInt("index");
        if (textureIndex < 0 || textureIndex >= textures.size()) {
            throw ModelParseException.at(element, slot + ".index " + textureIndex
                    + " out of range (textures: " + textures.size() + ")");
        }
        JsonObject texture = textures.requireObject(textureIndex);
        if (!texture.has("source")) {
            Model3D.LOGGER.warn("{}: texture[{}] used by {} has no image source (an extension such as "
                    + "KHR_texture_basisu?); leaving that slot unassigned", element, textureIndex, slot);
            return null;
        }
        int imageIndex = texture.requireInt("source");
        if (imageIndex < 0 || imageIndex >= images.count()) {
            throw ModelParseException.at(element, slot + " -> texture[" + textureIndex + "].source "
                    + imageIndex + " out of range (images: " + images.count() + ")");
        }
        String path = images.pathOf(imageIndex);
        if (path == null) {
            // ImageTable already reported the reason once for the whole model; this says which
            // surface is affected, which is what a "why is this part grey" question needs.
            Model3D.LOGGER.warn("{}: {} -> image[{}] could not be read; leaving that slot unassigned",
                    element, slot, imageIndex);
            return null;
        }
        return path;
    }

    private static void requireComponents(float[] values, int expected, String element, String what)
            throws ModelParseException {
        if (values.length != expected) {
            throw ModelParseException.at(element, what + " has " + values.length
                    + " components; the spec requires " + expected);
        }
    }
}
