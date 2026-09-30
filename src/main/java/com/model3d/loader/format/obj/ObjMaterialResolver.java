package com.model3d.loader.format.obj;

import com.model3d.loader.format.ModelSource;
import com.model3d.loader.scene.ModelMaterial;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One OBJ load's material table: loads the {@code mtllib} files an OBJ names, and hands out
 * {@link ModelMaterial}s with their texture paths already resolved through the model source.
 *
 * <p>Resolution happens here rather than in the resource layer for one reason: only the parser knows
 * which MTL a texture path was written in, and therefore which directory it is relative to. Handing
 * the resource layer an unresolved path would make it guess a second time, and the guess would be
 * wrong exactly when the MTL is not a sibling of the OBJ.
 *
 * <p>One instance per {@code parse()} call, so nothing here is shared between concurrent loads.
 */
final class ObjMaterialResolver {

    private final ModelSource source;
    private final ParseLog log;

    private final Map<String, ObjMaterialDefinition> definitions = new LinkedHashMap<>();
    private final Set<String> attemptedLibraries = new LinkedHashSet<>();

    private final List<ModelMaterial> materials = new ArrayList<>();
    private final Map<String, Integer> indexByName = new HashMap<>();
    private int defaultMaterialIndex = -1;

    /** "directory|requested path" -> resolved path, so a shared texture is opened once. */
    private final Map<String, String> resolvedTextures = new HashMap<>();
    private final Set<String> unresolvedTextures = new HashSet<>();
    private final Set<String> unknownMaterialNames = new HashSet<>();
    private final Set<String> redefinedMaterials = new HashSet<>();
    private final Set<String> reportedSlots = new HashSet<>();

    ObjMaterialResolver(ModelSource source, ParseLog log) {
        this.source = source;
        this.log = log;
    }

    /**
     * Loads one file named by {@code mtllib}.
     *
     * <p>A miss is a warning: the geometry does not depend on the materials, and refusing to load a
     * model because an exporter wrote a stale MTL path would make the loader useless on real files.
     */
    void loadMtllib(String token) {
        String path = ObjPaths.normalize(token);
        if (path.isEmpty() || !attemptedLibraries.add(path)) {
            return;
        }
        ModelSource.Reference reference;
        try {
            reference = source.open(path);
        } catch (IOException e) {
            log.warn("OBJ: cannot open mtllib '" + path + "': " + e);
            return;
        }
        if (!reference.isPresent()) {
            log.warn("OBJ: mtllib '" + path + "' was not found (" + reference.note()
                    + "); the geometry loads with the default material");
            return;
        }
        String text;
        try (InputStream stream = reference.data()) {
            text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("OBJ: cannot read mtllib '" + path + "': " + e);
            return;
        }
        for (Map.Entry<String, ObjMaterialDefinition> entry : MtlParser.parse(text, path, log).entrySet()) {
            ObjMaterialDefinition previous = definitions.put(entry.getKey(), entry.getValue());
            if (previous != null && !previous.mtlPath.equals(path) && redefinedMaterials.add(entry.getKey())) {
                log.warn("OBJ: material '" + entry.getKey() + "' is defined in both '" + previous.mtlPath
                        + "' and '" + path + "'; the definition from '" + path + "' is used");
            }
        }
        log.debug("OBJ: loaded " + path + " (" + reference.resolvedAs() + ")");
    }

    /**
     * The scene material index for a {@code usemtl} name.
     *
     * <p>A name that no loaded MTL defines falls back to the default material with one warning per
     * distinct name - a model exported against a material library that was not shipped is a normal
     * accident, and one line per name is enough to diagnose it without a wall of text.
     */
    int indexOf(String requestedName) {
        if (requestedName == null || requestedName.isEmpty()) {
            return defaultMaterialIndex();
        }
        ObjMaterialDefinition definition = definitions.get(requestedName);
        if (definition == null) {
            if (unknownMaterialNames.add(requestedName)) {
                log.warn("OBJ: usemtl '" + requestedName + "' matches no material from "
                        + (attemptedLibraries.isEmpty() ? "any mtllib (this OBJ names none)"
                        : attemptedLibraries.toString())
                        + "; the default material is used instead");
            }
            return defaultMaterialIndex();
        }
        Integer existing = indexByName.get(requestedName);
        if (existing != null) {
            return existing;
        }
        int index = materials.size();
        materials.add(build(definition));
        indexByName.put(requestedName, index);
        return index;
    }

    /** The materials referenced so far, in first-use order. */
    List<ModelMaterial> materials() {
        return materials;
    }

    private int defaultMaterialIndex() {
        if (defaultMaterialIndex < 0) {
            defaultMaterialIndex = materials.size();
            materials.add(ModelMaterial.defaultMaterial());
        }
        return defaultMaterialIndex;
    }

    private ModelMaterial build(ObjMaterialDefinition definition) {
        ModelMaterial.Builder builder = ModelMaterial.builder(definition.name);
        builder.baseColorFactor(definition.diffuse[0], definition.diffuse[1], definition.diffuse[2], definition.alpha);
        builder.roughnessFactor(definition.roughness);
        // MTL has no metal/roughness concept at all; a Phong surface is a dielectric. Leaving glTF's
        // default of 1.0 would turn every OBJ model into a mirror.
        builder.metallicFactor(0.0f);
        if (definition.alpha < 1.0f) {
            builder.alphaMode(ModelMaterial.AlphaMode.BLEND);
        }
        boolean emissiveSet = definition.hasEmissive;
        if (definition.hasEmissive) {
            builder.emissiveFactor(definition.emissive[0], definition.emissive[1], definition.emissive[2]);
        }
        if (definition.illumination == 0) {
            // illum 0 is a constant colour, i.e. unlit. The renderer has no unlit flag, and emitting
            // the diffuse colour is what "constant colour" means, so that is the honest mapping.
            builder.emissiveFactor(definition.diffuse[0], definition.diffuse[1], definition.diffuse[2]);
            emissiveSet = true;
            log.debug("OBJ: material '" + definition.name + "' uses illum 0 (unlit); approximated as"
                    + " emissive = Kd, which is the same thing for a constant-colour surface");
        } else if (definition.illumination == 1) {
            // illum 1 is diffuse only: lit, but with no specular lobe. Not unlit, so no emission.
            log.debug("OBJ: material '" + definition.name + "' uses illum 1 (diffuse only); no specular"
                    + " response is applied");
        }
        String directory = ObjPaths.parent(definition.mtlPath);
        if (definition.baseColorTexture != null) {
            builder.baseColorTexture(resolveTexture(directory, definition.baseColorTexture, definition.name, "map_Kd"));
        }
        if (definition.normalTexture != null) {
            builder.normalTexture(resolveTexture(directory, definition.normalTexture, definition.name, "map_Bump"), 1.0f);
        }
        if (definition.emissiveTexture != null) {
            builder.emissiveTexture(resolveTexture(directory, definition.emissiveTexture, definition.name, "map_Ke"));
            if (!emissiveSet) {
                // An emissive map is multiplied by the emissive factor, and glTF's default factor is
                // black - so an MTL with only a map_Ke would render dark unless the factor is raised
                // to white here. This is what an exporter that emits only the map means by it.
                builder.emissiveFactor(1.0f, 1.0f, 1.0f);
            }
        }
        if (definition.alphaTexture != null && reportedSlots.add("map_d:" + definition.name)) {
            log.warn("OBJ: material '" + definition.name + "' uses map_d ('" + definition.alphaTexture
                    + "'), which is unsupported: an alpha cutout belongs in the base colour texture's alpha"
                    + " channel and this loader will not tint the surface with it; the material stays opaque");
        }
        return builder.build();
    }

    /**
     * Resolves one texture path to the path that actually exists, or keeps the written path.
     *
     * <p>The resolved value is stored on the material so the resource layer resolves a real file
     * rather than repeating a search that has already failed once.
     */
    private String resolveTexture(String directory, String writtenPath, String materialName, String slot) {
        String path = ObjPaths.normalize(writtenPath);
        String cacheKey = directory + '|' + path;
        String cached = resolvedTextures.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        String resolved = null;
        String note = "no candidate path matched";
        for (String candidate : ObjPaths.candidates(directory, path)) {
            ModelSource.Reference reference;
            try {
                reference = source.open(candidate);
            } catch (IOException e) {
                note = e.toString();
                continue;
            }
            if (reference.isPresent()) {
                closeQuietly(reference.data());
                resolved = reference.resolvedAs() != null ? reference.resolvedAs() : candidate;
                if (!reference.exact()) {
                    log.debug("OBJ: texture '" + path + "' resolved to '" + resolved + "' (" + reference.note() + ")");
                }
                break;
            }
            note = reference.note();
        }
        if (resolved == null) {
            // Keep what the file asked for: the resource layer can report the same miss again, and a
            // cleared path would turn "texture not found" into "model has no texture at all".
            resolved = path;
            if (unresolvedTextures.add(cacheKey)) {
                log.warn("OBJ: material '" + materialName + "' " + slot + " texture '" + path
                        + "' was not found (" + note + "); the surface renders without it");
            }
        }
        resolvedTextures.put(cacheKey, resolved);
        return resolved;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing a stream we only probed for existence cannot affect the parse.
        }
    }
}
