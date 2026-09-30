package com.model3d.loader.resource;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The optional {@code model.json} that sits beside a model's assets.
 *
 * <p>Every field is optional, and an absent descriptor is normal: a bare {@code .glb} dropped
 * into a model directory is a valid model, detected by extension. The descriptor exists for the
 * things a file cannot say about itself:
 *
 * <ul>
 *   <li>{@code model} - which file to load, when a directory holds more than one candidate
 *       (an exporter's {@code .gltf} and its {@code .glb} export side by side). Without it the
 *       loader prefers GLB, then glTF, then OBJ.</li>
 *   <li>{@code scale} - in <b>blocks per model unit</b>. A glTF aircraft is authored in metres
 *       or centimetres, so "how big is this in the world" is a property of the asset set, not of
 *       the renderer. Values {@code <= 0} are rejected at parse time.</li>
 *   <li>{@code autoAnimation} - the glTF animation to play on spawn. A model's first animation
 *       is often a static pose or a turntable spin, so picking by name beats picking by index.</li>
 *   <li>{@code yawOffsetDegrees} - a model authored facing +Z (glTF convention) needs 180 to
 *       face the way Minecraft entities are drawn.</li>
 *   <li>{@code textures} - aliases, so a descriptor can point a material's texture path at a
 *       different file without editing the model. Keyed by the path as written in the model
 *       file, valued by the replacement path; both relative to the model directory.</li>
 * </ul>
 *
 * <p>Parsing is fail-closed on malformed JSON but tolerant of unknown keys: a descriptor
 * written for a newer version of this mod should degrade, not refuse to load.
 */
public final class ModelDescriptor {

    /** File name a model directory is scanned for. */
    public static final String FILE_NAME = "model.json";

    private final String modelFile;
    private final float scale;
    private final float targetBlocks;
    private final float[] pivot;
    private final String autoAnimation;
    private final boolean autoAnimationLoop;
    private final float yawOffsetDegrees;
    /** Axis letters the renderer negates, or null when the model declares none. */
    private final String mirror;
    private final Map<String, String> textureAliases;

    private ModelDescriptor(String modelFile, float scale, float targetBlocks, float[] pivot,
                            String autoAnimation, boolean autoAnimationLoop, float yawOffsetDegrees,
                            String mirror, Map<String, String> textureAliases) {
        this.modelFile = modelFile;
        this.scale = scale;
        this.targetBlocks = targetBlocks;
        this.pivot = pivot;
        this.autoAnimation = autoAnimation;
        this.autoAnimationLoop = autoAnimationLoop;
        this.yawOffsetDegrees = yawOffsetDegrees;
        this.mirror = mirror;
        this.textureAliases = Collections.unmodifiableMap(textureAliases);
    }

    /** Sensible defaults: 1 block per model unit, no pivot, no auto animation. */
    public static ModelDescriptor defaults() {
        // targetBlocks 0 means "undeclared", which ModelScale reads as "use its default". Zero rather
        // than the default constant so this class keeps no opinion about size, and so the value cannot
        // drift out of step with the one place that decides scale.
        return new ModelDescriptor(null, 1.0f, 0.0f, new float[] { 0, 0, 0 }, null, true, 0.0f,
                null, Map.of());
    }

    /**
     * Parses a descriptor, or returns null when it holds no usable settings - a present but
     * content-free {@code model.json} means the same as no descriptor at all.
     *
     * @throws ModelDescriptorException when the JSON is malformed or a value is out of range
     */
    public static ModelDescriptor parse(String json) throws ModelDescriptorException {
        JsonObject root;
        try {
            // Gson 2.10 (the version Minecraft 1.20.1 ships) has no static JsonParser.parseString:
            // that arrived in 2.10.1. Parsing through a reader is the portable call.
            JsonElement element = JsonParser.parseReader(new java.io.StringReader(json));
            if (!element.isJsonObject()) {
                throw new ModelDescriptorException("model.json root is not an object");
            }
            root = element.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ModelDescriptorException("model.json is not valid JSON: " + e.getMessage(), e);
        }

        ModelDescriptor defaults = defaults();

        String modelFile = optString(root, "model", null);
        if (modelFile != null) {
            modelFile = modelFile.replace('\\', '/');
            if (modelFile.startsWith("/") || modelFile.contains("..")) {
                throw new ModelDescriptorException(
                        "model.json \"model\" must be a relative path inside the model directory, got: "
                                + modelFile);
            }
        }

        float scale = optFloat(root, "scale", defaults.scale);
        // Longest-axis size in blocks, or 0 for "use the loader's default". Declared per model because
        // the right size depends on the model: a 279-unit aircraft and a 2-unit prop should not share a
        // footprint, and only the author knows which they have.
        float targetBlocks = optFloat(root, "targetBlocks", defaults.targetBlocks);
        if (targetBlocks < 0.0f) {
            throw new ModelDescriptorException("targetBlocks is " + targetBlocks
                    + "; it is a size in blocks and must be positive, or omitted to use the default");
        }
        // Fail closed: a zero or negative scale collapses the model to a point, which renders as
        // "nothing happened" and gets misdiagnosed as a load failure.
        if (!(scale > 0.0f) || !Float.isFinite(scale)) {
            throw new ModelDescriptorException("model.json \"scale\" must be > 0, got: " + scale);
        }

        float[] pivot = defaults.pivot;
        if (root.has("pivot") && root.get("pivot").isJsonArray()) {
            var array = root.getAsJsonArray("pivot");
            if (array.size() != 3) {
                throw new ModelDescriptorException("model.json \"pivot\" needs 3 numbers, got "
                        + array.size());
            }
            pivot = new float[] { array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                    array.get(2).getAsFloat() };
        }

        String autoAnimation = optString(root, "autoAnimation", null);
        boolean autoAnimationLoop = optBoolean(root, "autoAnimationLoop", true);
        float yawOffsetDegrees = optFloat(root, "yawOffsetDegrees", 0.0f);
        String mirror = mirrorAxes(optString(root, "mirror", null));

        Map<String, String> aliases = new LinkedHashMap<>();
        if (root.has("textures") && root.get("textures").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("textures").entrySet()) {
                if (!entry.getValue().isJsonPrimitive()) {
                    throw new ModelDescriptorException("model.json textures[\"" + entry.getKey()
                            + "\"] must be a string path");
                }
                aliases.put(entry.getKey().replace('\\', '/'),
                        entry.getValue().getAsString().replace('\\', '/'));
            }
        }

        return new ModelDescriptor(modelFile, scale, targetBlocks, pivot, autoAnimation,
                autoAnimationLoop, yawOffsetDegrees, mirror, aliases);
    }

    /**
     * Validates a {@code "mirror"} value into the axis letters the renderer negates.
     *
     * <p>Accepted: any combination of {@code x}, {@code y} and {@code z}, plus {@code none} and the
     * empty string for "no axes". Case and separator noise is tolerated ({@code "XY"}, {@code "x,y"}).
     * Anything else throws, because a typo silently becoming "no mirror" is precisely the failure this
     * key exists to make visible.
     */
    private static String mirrorAxes(String declared) throws ModelDescriptorException {
        if (declared == null) {
            return null;
        }
        String cleaned = declared.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z]", "");
        if (cleaned.isEmpty() || cleaned.equals("none")) {
            return "";
        }
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c != 'x' && c != 'y' && c != 'z') {
                throw new ModelDescriptorException("model.json \"mirror\" must name axes from x, y, z "
                        + "or be \"none\", got: " + declared);
            }
        }
        return cleaned;
    }

    private static String optString(JsonObject root, String key, String fallback)
            throws ModelDescriptorException {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive()) {
            throw new ModelDescriptorException("model.json \"" + key + "\" must be a string");
        }
        return element.getAsString();
    }

    private static float optFloat(JsonObject root, String key, float fallback)
            throws ModelDescriptorException {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new ModelDescriptorException("model.json \"" + key + "\" must be a number");
        }
        return element.getAsFloat();
    }

    private static boolean optBoolean(JsonObject root, String key, boolean fallback)
            throws ModelDescriptorException {
        JsonElement element = root.get(key);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new ModelDescriptorException("model.json \"" + key + "\" must be a boolean");
        }
        return element.getAsBoolean();
    }

    /** Explicit model file name, or null to auto-detect by extension. */
    public String modelFile() {
        return modelFile;
    }

    /** Blocks per model unit. */
    public float scale() {
        return scale;
    }

    /**
     * Longest-axis size in blocks this model wants, or 0 when it does not say.
     *
     * <p>Zero means "undeclared" and delegates the decision to {@code ModelScale}, rather than naming a
     * default here: two places deciding a default size is two places to keep in step, and the one that
     * is read at render time would win silently.
     */
    public float targetBlocks() {
        return targetBlocks;
    }

    /** Model-space pivot offset in model units, {@code [x, y, z]}. */
    public float[] pivot() {
        return pivot.clone();
    }

    /** glTF animation name to start automatically, or null. */
    public String autoAnimation() {
        return autoAnimation;
    }

    public boolean autoAnimationLoop() {
        return autoAnimationLoop;
    }

    public float yawOffsetDegrees() {
        return yawOffsetDegrees;
    }

    /**
     * The axes this model asks the renderer to negate, or null when it declares none.
     *
     * <p>Null means "use the loader's default" rather than "no mirror": an empty string is the model
     * saying so explicitly. The renderer's {@code InstanceTransform} resolves the difference.
     */
    public String mirror() {
        return mirror;
    }

    /** Texture path replacements, keyed by the path as written in the model file. */
    public Map<String, String> textureAliases() {
        return textureAliases;
    }

    /**
     * The replacement for a texture reference, or the reference itself when no alias applies.
     *
     * <p>Keyed by the path as written in the model file - which is exactly the string a caller holds
     * at the point of resolution, and why this lookup lives here: someone has to apply the map, and
     * the parser is the only place that never sees the written path again.
     *
     * @param writtenPath a material's texture reference, or null
     * @return the replacement path, or {@code writtenPath} unchanged (including null)
     */
    public String textureAlias(String writtenPath) {
        if (writtenPath == null) {
            return null;
        }
        String replacement = textureAliases.get(writtenPath);
        return replacement == null ? writtenPath : replacement;
    }

    @Override
    public String toString() {
        return "ModelDescriptor(model=" + modelFile + " scale=" + scale
                + " autoAnimation=" + autoAnimation + " aliases=" + textureAliases.size() + ")";
    }

    /** Raised for a malformed or self-contradictory descriptor; the model can still load without it. */
    public static class ModelDescriptorException extends Exception {
        private static final long serialVersionUID = 1L;

        public ModelDescriptorException(String message) {
            super(message);
        }

        public ModelDescriptorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
