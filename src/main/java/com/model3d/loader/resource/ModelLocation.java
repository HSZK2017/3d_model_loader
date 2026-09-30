package com.model3d.loader.resource;

import com.model3d.loader.util.Ids;

import net.minecraft.resources.ResourceLocation;

/**
 * Where a model's textures and files live, and where a pack can find them.
 *
 * <h2>Two pack roots, and the order is not a preference</h2>
 * A model is looked for under <b>{@value #DATA_ROOT}</b> first and <b>{@value #ASSETS_ROOT}</b>
 * second, in both cases under {@code <namespace>/model3d/<name>/}:
 *
 * <pre>
 *   data/&lt;namespace&gt;/model3d/&lt;name&gt;/     <- first, and the only one a dedicated server can see
 *   assets/&lt;namespace&gt;/model3d/&lt;name&gt;/   <- fallback, the client-side convention
 * </pre>
 *
 * <p>This was originally {@code assets/} only, on the reasoning that the {@code assets} tree is
 * "the tree a dedicated server also loads, so the same path resolves on both sides". <b>That
 * reasoning is wrong</b>, and a running dedicated server proved it. {@code MinecraftServer}'s
 * resource manager is built with {@code PackType.SERVER_DATA}, so it indexes {@code data/} and
 * nothing else. Measured through a live {@code ResourceManager} on a dev server:
 * {@code listResources("loot_tables")} = 1091 entries, {@code listResources("recipes")} = 1174,
 * while {@code listResources("models")} - the {@code assets} tree - = <b>0</b>, and a direct
 * {@code getResource} for a mod asset was absent. A model stored under {@code assets/} therefore
 * loads on the client and is invisible to the server, which breaks exactly the property the design
 * wanted: the server validating {@code /testmodel loader <name>} against the real file set.
 *
 * <p>{@code assets/} is still tried second, so a client-only model keeps working and a resource
 * pack may legitimately place one there. The cost is one extra map miss.
 *
 * <h2>Texture root</h2>
 * A material's texture path resolves relative to the model directory first, then against
 * {@code assets/<namespace>/textures/}. Textures are a client concept, so that fallback root is an
 * {@code assets} path whichever root the model itself came from.
 *
 * <h2>Case and escapes</h2>
 * Lookup is case-insensitive at the second attempt: Minecraft resource paths are lower-case only,
 * while real exporters write {@code Textures/Glass_Cockpit.jpeg}, and refusing those would make the
 * mod unusable on assets it otherwise parses correctly. A miss then retries by unique file name
 * within the model directory. Every fallback taken is reported in
 * {@link com.model3d.loader.format.ModelSource.Reference#note}, so the behaviour is visible in a
 * log rather than mysterious.
 */
public final class ModelLocation {

    /** {@code data/<ns>/model3d/<name>/} - the root a dedicated server's resource manager serves. */
    public static final String DATA_ROOT = "data";

    /** {@code assets/<ns>/model3d/<name>/} - the client-side tree, tried as a fallback. */
    public static final String ASSETS_ROOT = "assets";

    /** The directory under a namespace that holds models. */
    public static final String MODEL3D_ROOT = "model3d";

    /** The vanilla texture directory of a namespace, used as the fallback resolve root. */
    public static final String TEXTURE_ROOT = "textures";

    /** Accepted alternative to {@link #MODEL3D_ROOT}, where a mod author would naturally put one. */
    public static final String MODEL_ROOT_ALTERNATIVE = "model";

    private final ResourceLocation modelId;
    private final String namespace;
    private final String name;

    private ModelLocation(ResourceLocation modelId, String namespace, String name) {
        this.modelId = modelId;
        this.namespace = namespace;
        this.name = name;
    }

    /**
     * @param modelId identifies the model, {@code <namespace>:<name>} where {@code name} may
     *                contain {@code /} for a nested model directory
     */
    public static ModelLocation of(ResourceLocation modelId) {
        return new ModelLocation(modelId, modelId.getNamespace(), modelId.getPath());
    }

    public static ModelLocation of(String namespace, String name) {
        return new ModelLocation(Ids.of(namespace, name), namespace, name);
    }

    public ResourceLocation modelId() {
        return modelId;
    }

    public String namespace() {
        return namespace;
    }

    /** Model name within the namespace, possibly with {@code /} separators. */
    public String name() {
        return name;
    }

    /**
     * Pack roots to try, in order: {@code data} before {@code assets}. See the class comment for
     * the measurement that fixes this order.
     */
    public static String[] roots() {
        return new String[] { DATA_ROOT, ASSETS_ROOT };
    }

    /** {@code <root>/<ns>/model3d/<name>}, with no trailing slash. */
    public String rootPath(String root) {
        return root + "/" + namespace + "/" + MODEL3D_ROOT + "/" + name;
    }

    /**
     * The {@code ResourceManager}-relative path for this model: {@code <ns>/model3d/<name>}.
     *
     * <p>A resource manager path is relative to a pack root and carries the namespace as its first
     * segment, so the leading {@code data/} or {@code assets/} is dropped.
     */
    public String resourcePath() {
        return namespace + "/" + MODEL3D_ROOT + "/" + name;
    }

    /**
     * The canonical model root path, {@code data/<ns>/model3d/<name>}.
     *
     * <p>Kept as a single-argument accessor for diagnostics and messages; resolution itself walks
     * {@link #roots()}.
     */
    public String modelRootPath() {
        return rootPath(DATA_ROOT);
    }

    /** {@code assets/<ns>/textures}, the fallback root for a material's texture path. */
    public String textureRootPath() {
        return ASSETS_ROOT + "/" + namespace + "/" + TEXTURE_ROOT;
    }


    /**
     * The {@link ResourceLocation} for a model-relative path, or null instead of throwing when the
     * path cannot address a resource at all.
     *
     * <p>The throwing form is what a renderer must not call: {@code relativePath} comes out of a
     * model file, so it is untrusted input, and ordinary exporter output is exactly what
     * {@code ResourceLocation} rejects - {@code Textures/Glass_Cockpit.jpeg} has capitals,
     * {@code textures/my plane.png} has a space. Because the throw happened while <i>building the
     * argument</i> to the lookup, the case-insensitive retry inside it could never run, and the
     * exception left the entity renderer (which catches nothing) and killed the client.
     *
     * <p>So the path is lowered first, and the result is validated by {@code Ids.parse} - the same
     * validation the game itself applies - rather than by a second copy of the character rule. A
     * name that is still not addressable yields null, and the caller reports it as the miss it is.
     */
    public ResourceLocation inModelRootOrNull(String relativePath) {
        return safeLocation(MODEL3D_ROOT + "/" + name + "/" + relativePath);
    }

    /**
     * The {@link ResourceLocation} for a path under the namespace's texture root, or null when it
     * cannot be addressed - the same null-safe contract as {@link #inModelRootOrNull}.
     */
    public ResourceLocation inTextureRootOrNull(String relativePath) {
        return safeLocation(TEXTURE_ROOT + "/" + relativePath);
    }

    /** The location for a manager-relative path, lowered once, or null when it cannot exist. */
    private ResourceLocation safeLocation(String path) {
        ResourceLocation exact = Ids.parse(namespace + ":" + path);
        return exact != null
                ? exact
                : Ids.parse(namespace + ":" + path.toLowerCase(java.util.Locale.ROOT));
    }


    @Override
    public String toString() {
        return modelId.toString();
    }
}
