package com.model3d.loader.resource;

import com.model3d.loader.Model3D;
import com.model3d.loader.api.ModelHandle;
import com.model3d.loader.format.ModelFormat;
import com.model3d.loader.format.ModelFormatRegistry;
import com.model3d.loader.format.ModelParseException;
import com.model3d.loader.format.ModelSource;
import com.model3d.loader.scene.ModelScene;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The one place a model is turned into a {@link ModelHandle}.
 *
 * <p>Resolution order for a model id {@code <namespace>:<name>}:
 * <ol>
 *   <li>{@code <gamedir>/model3d/<namespace>/<name>/} on disk. First, so an unpacked model can
 *       be iterated on without rebuilding, and so a user can override a shipped model.</li>
 *   <li>{@code assets/<namespace>/model3d/<name>/} from the loaded resource packs.</li>
 * </ol>
 *
 * <p>Cache semantics: parsed {@link ModelScene}s are shared and reference-counted through
 * {@link ModelHandle}, because two hundred entities flying the same aircraft must not hold two
 * hundred copies of a 200k-triangle mesh. {@link #acquire} adds a reference, {@link #release}
 * drops one and evicts on the last.
 *
 * <p>This class runs on both sides. Parsing is the same work either way; the difference is that
 * only the client goes on to build GPU resources from the resulting handle.
 */
public final class ModelLoadService {

    public static final ModelLoadService INSTANCE = new ModelLoadService();

    /** Candidate model file names to auto-detect, in preference order (see ModelDescriptor). */
    private static final String[] AUTO_DETECT_ORDER = { "model.glb", "model.gltf", "model.obj" };

    private final Map<String, ModelHandle> clientCache = new ConcurrentHashMap<>();
    private final Map<String, ModelHandle> serverCache = new ConcurrentHashMap<>();

    /** Names known to be loadable on the server, for command validation; null until scanned. */
    private volatile Set<String> serverKnownNames;

    private ModelLoadService() {
    }

    // ------------------------------------------------------------------
    // Client side
    // ------------------------------------------------------------------

    /**
     * Loads or reuses a model on the client.
     *
     * @return the handle (already referenced, do not release unless you acquired it), or null
     *         when the model is not present or failed to parse - the caller decides what to
     *         render instead
     */
    public ModelHandle acquireClient(ResourceManager resourceManager, ResourceLocation modelId) {
        return acquire(clientCache, resourceManager, modelId, true);
    }

    /** Drops one client reference, evicting the model when it was the last. */
    public void releaseClient(ResourceLocation modelId) {
        release(clientCache, modelId);
    }

    /** Client handle already loaded, or null; does not load. */
    public ModelHandle peekClient(ResourceLocation modelId) {
        ModelHandle handle = clientCache.get(key(modelId));
        return handle == null || handle.referenceCount() <= 0 ? null : handle;
    }

    /** Evicts every client model; used on resource reload and level unload. */
    public List<ModelHandle> clearClient() {
        List<ModelHandle> evicted = new ArrayList<>(clientCache.values());
        clientCache.clear();
        return evicted;
    }

    // ------------------------------------------------------------------
    // Server side
    // ------------------------------------------------------------------

    /**
     * Loads a model on the server, for validation and bounding-box checks.
     *
     * <p>A dedicated server has no renderer, but it does have the resource packs, so the parse
     * is the same one the client will do. The handle exists so a command can answer "does this
     * model exist, and is it sane" without inventing a second, weaker validation path.
     */
    public ModelHandle acquireServer(ResourceManager resourceManager, ResourceLocation modelId) {
        return acquire(serverCache, resourceManager, modelId, false);
    }

    public void releaseServer(ResourceLocation modelId) {
        release(serverCache, modelId);
    }

    public List<ModelHandle> clearServer() {
        List<ModelHandle> evicted = new ArrayList<>(serverCache.values());
        serverCache.clear();
        serverKnownNames = null;
        return evicted;
    }

    /**
     * Every model name that exists, for command suggestions and validation.
     *
     * <p>Scans both the external directory and the pack tree. Result is cached until
     * {@link #clearServer()} or {@link #invalidateNameIndex()} - a resource reload changes the
     * pack tree, and a stale suggestion list that offers a model which no longer loads is worse
     * than no suggestions.
     */
    public Set<String> knownServerModelNames(ResourceManager resourceManager) {
        Set<String> names = serverKnownNames;
        if (names != null) {
            return names;
        }
        Set<String> found = new TreeSet<>();
        // Folders a player drops models into, in priority order. `config/3dmodels` first so the
        // documented answer to "where do I put a model" always wins; the legacy namespaced folder
        // second so models placed before that folder existed keep working.
        for (ModelLibrary library : libraries()) {
            for (Map.Entry<String, Path> entry : library.discover().entrySet()) {
                // The library yields bare names, and they live in this mod's namespace.
                found.add(Model3D.MOD_ID + ":" + entry.getKey());
            }
        }
        // Legacy layout, which is namespaced: <gamedir>/model3d/<namespace>/<name>/
        Path legacyRoot = ExternalModelPaths.legacyModels();
        if (legacyRoot != null && Files.isDirectory(legacyRoot)) {
            try (var stream = Files.list(legacyRoot)) {
                stream.filter(Files::isDirectory).forEach(dir -> {
                    String namespace = dir.getFileName().toString();
                    try (var inner = Files.list(dir)) {
                        inner.filter(Files::isDirectory).forEach(model ->
                                found.add(namespace + ":" + model.getFileName()));
                    } catch (IOException e) {
                        Model3D.LOGGER.warn("Model3D: cannot list external model namespace {}",
                                dir, e);
                    }
                });
            } catch (IOException e) {
                Model3D.LOGGER.warn("Model3D: cannot list external model root {}", legacyRoot, e);
            }
        }
        // Pack tree, under both model directories: `model3d` is this mod's convention, `model` is
        // accepted so a model can sit where a mod author would naturally put it.
        //
        // Everything is collected in one scan per directory rather than one scan per extension:
        // listResources walks every pack's index, and a dedicated server pays that cost at boot.
        //
        // There is deliberately no loop over pack ROOTS here. A ResourceManager instance belongs to
        // exactly one PackType, so its listings already reflect whichever tree it serves: a
        // server's manager yields `data/` entries, a client's yields both, and asking a server for
        // an `assets/...` path simply returns nothing. The root matters when CONSTRUCTING a path
        // for getResource or listResources - see ModelLocation - because a resource-manager path
        // never carries the root name, only the namespace.
        for (String root : new String[] { ModelLocation.MODEL3D_ROOT,
                ModelLocation.MODEL_ROOT_ALTERNATIVE }) {
            Map<ResourceLocation, Resource> entries = resourceManager.listResources(root, path -> true);
            for (ResourceLocation location : entries.keySet()) {
                String path = location.getPath();
                String prefix = root + "/";
                if (!path.startsWith(prefix) || path.length() <= prefix.length()) {
                    continue;
                }
                if (path.endsWith("/" + ModelDescriptor.FILE_NAME)) {
                    String name = path.substring(prefix.length(),
                            path.length() - ModelDescriptor.FILE_NAME.length() - 1);
                    if (!name.isEmpty()) {
                        found.add(location.getNamespace() + ":" + name);
                    }
                    continue;
                }
                // A directory with no descriptor still counts when it holds a recognisable model
                // file; this is what makes a bare .glb drop-in work with no model.json at all.
                if (ModelFormat.byPath(path) != null) {
                    int lastSlash = path.lastIndexOf('/');
                    if (lastSlash > prefix.length()) {
                        found.add(location.getNamespace() + ":"
                                + path.substring(prefix.length(), lastSlash));
                    }
                }
            }
        }
        serverKnownNames = found;
        Model3D.LOGGER.info("Model3D: {} model(s) available: {}", found.size(), found);
        return found;
    }

    public void invalidateNameIndex() {
        serverKnownNames = null;
    }

    // ------------------------------------------------------------------
    // Player-facing model folders, and hot reload
    // ------------------------------------------------------------------

    /**
     * The dropped-in-model folders, in priority order. {@code config/3dmodels} first: it is the
     * folder the mod creates, documents and tells the player about, so a model placed there must win
     * over the same name anywhere else.
     *
     * <p><b>Built once and reused.</b> A {@link ModelLibrary}'s signature cache lives in the
     * instance, so constructing a fresh one per call - and this is called on every server tick and
     * every client frame - made {@link ModelLibrary#signature()} recompute a full directory walk
     * every time, the opposite of what {@link #checkForChanges} documents. The result is not cached
     * while the game directories are unresolved (see {@link ExternalModelPaths}): "not resolved yet"
     * must stay distinguishable from "resolved, and empty".
     */
    public static List<ModelLibrary> libraries() {
        List<ModelLibrary> cached = cachedLibraries;
        if (cached != null) {
            return cached;
        }
        Path configModels = ExternalModelPaths.configModels();
        if (configModels == null) {
            return List.of();
        }
        List<ModelLibrary> built = List.of(new ModelLibrary(configModels));
        cachedLibraries = built;
        return built;
    }

    /** Creates the model folder and its README. Called once at startup. */
    public void prepareModelFolders() {
        List<ModelLibrary> libraries = libraries();
        if (libraries.isEmpty()) {
            return;
        }
        // The same instance the poll uses, so the baseline it records is the one later compared.
        ModelLibrary library = libraries.get(0);
        library.prepare();
        Map<String, Path> found = library.discover();
        Model3D.LOGGER.info("Model3D: drop models in {} (see the README.txt in that folder); "
                        + "they are picked up automatically{}",
                library.root(), found.isEmpty() ? "" : " - found " + found.size() + " already");
    }

    /**
     * Whether anything in the model folders changed since the last call, and whether that matters.
     *
     * <p>Called every tick by the server and by the client; the underlying check is cached inside
     * {@link ModelLibrary#signature()} so this is a long comparison most of the time. On a change it
     * invalidates the name index and - on the client - marks the GPU cache stale, which is what makes
     * a dropped file appear without a restart.
     *
     * @param clientSide true on the physical client, where the GPU cache also has to be dropped
     * @return true when a change was detected and handled
     */
    public boolean checkForChanges(boolean clientSide) {
        List<ModelLibrary> libraries = libraries();
        if (libraries.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (ModelLibrary library : libraries) {
            long signature = library.signature();
            Long previous = librarySignatures.get(library.root());
            if (previous == null) {
                // First observation is the baseline, not a change: reporting it would make every
                // startup look like someone had just edited a file.
                librarySignatures.put(library.root(), signature);
                continue;
            }
            if (previous != signature) {
                librarySignatures.put(library.root(), signature);
                changed = true;
                Model3D.LOGGER.info("Model3D: change detected in {}", library.root());
            }
        }
        if (!changed) {
            return false;
        }
        invalidateNameIndex();
        clearServer();
        if (clientSide) {
            onModelFoldersChanged.run();
        }
        // The name index was just invalidated, so there is no count to report here: the index is
        // rebuilt from the new file set on the next request, and that request logs the count. Asking
        // for it now would mean a directory scan from a per-tick poll for the sake of one log line.
        Model3D.LOGGER.info("Model3D: model list reloaded - the name index will be rebuilt on the "
                + "next request");
        return true;
    }

    /** Last signature per folder; see {@link #checkForChanges}. */
    private final Map<Path, Long> librarySignatures = new ConcurrentHashMap<>();

    /**
     * The model folders' libraries, built once: see {@link #libraries()}. Volatile because the
     * first call can come from either the server thread or the client's render thread.
     */
    private static volatile List<ModelLibrary> cachedLibraries;

    /**
     * Hook the client installs so a detected change also drops the GPU meshes.
     *
     * <p>A {@code Runnable} rather than a direct call because this class is side-agnostic: it runs on
     * a dedicated server too, where there is no GPU cache and no client class to name. Set once at
     * client startup; a no-op on the server.
     */
    private volatile Runnable onModelFoldersChanged = () -> {
    };

    public void setOnModelFoldersChanged(Runnable hook) {
        this.onModelFoldersChanged = hook == null ? () -> {
        } : hook;
    }

    /**
     * Rescans immediately, without waiting for the poll.
     *
     * <p>Backs {@code /testmodel reload}. Detection is automatic, so this exists for the case where
     * waiting a second is not the point: a user who has just dropped a file and wants confirmation
     * now, from the same command they already know.
     */
    public void forceRescan(boolean clientSide) {
        for (ModelLibrary library : libraries()) {
            library.invalidateSignature();
            librarySignatures.remove(library.root());
        }
        invalidateNameIndex();
        clearServer();
        if (clientSide) {
            onModelFoldersChanged.run();
        }
    }

    // ------------------------------------------------------------------
    // Shared
    // ------------------------------------------------------------------

    private ModelHandle acquire(Map<String, ModelHandle> cache, ResourceManager resourceManager,
                               ResourceLocation modelId, boolean clientSide) {
        String key = key(modelId);
        ModelHandle existing = cache.get(key);
        if (existing != null) {
            return existing.acquire();
        }
        ModelHandle loaded = load(resourceManager, modelId, clientSide);
        if (loaded == null) {
            return null;
        }
        ModelHandle raced = cache.putIfAbsent(key, loaded);
        if (raced != null) {
            // Another thread won the race; drop ours rather than hand out two handles for one
            // model, which would double the GPU buffers and halve the cache's usefulness.
            loaded.release();
            return raced.acquire();
        }
        return loaded;
    }

    private void release(Map<String, ModelHandle> cache, ResourceLocation modelId) {
        String key = key(modelId);
        ModelHandle handle = cache.get(key);
        if (handle == null) {
            return;
        }
        if (handle.release()) {
            cache.remove(key, handle);
        }
    }

    /**
     * Finds a model's directory in whichever pack root holds it, and picks its main file.
     *
     * <p>Two roots are tried because they are not interchangeable: a dedicated server's resource
     * manager is built with {@code PackType.SERVER_DATA}, so it serves {@code data/} and never
     * {@code assets/}. See {@link ModelLocation}'s class comment for the measurement.
     *
     * <h2>Why this probes by name instead of listing</h2>
     * The obvious implementation lists the directory and picks from what it finds. That does not
     * work reliably through a {@code ResourceManager}: measured on a dev dedicated server,
     * {@code listResources("model3d")} returned the model file while
     * {@code listResources("model3d/model3d/animated_test")} and a direct {@code getResource} for
     * the same file both came back empty, so a listing-based loader concluded "no model directory"
     * for a model the manager could enumerate one level up. Probing the descriptor and the
     * conventional file names asks the manager only questions it answers consistently.
     *
     * <p>The consequence is a real limitation, stated rather than hidden: a pack-hosted model whose
     * file is named unconventionally must name it in {@code model.json}. A directory of loose,
     * arbitrarily-named files is discoverable only through the external
     * {@code <gamedir>/model3d/} path, which is a real directory and lists perfectly.
     */
    private PackLookup packDescriptor(ResourceManager resourceManager, ModelLocation location) {
        List<String> tried = new ArrayList<>(ModelLocation.roots().length);
        for (String root : ModelLocation.roots()) {
            PackModelSource probe = new PackModelSource(resourceManager, location, root, "");

            // The descriptor first: it is the only way to name an unconventionally-named file, and
            // it is a single probe on a fixed path.
            ModelDescriptor descriptor = ModelDescriptor.defaults();
            if (probe.exists(ModelDescriptor.FILE_NAME)) {
                descriptor = DescriptorLoader.read(probe, location);
            }

            List<String> candidates = new ArrayList<>(4);
            if (descriptor.modelFile() != null) {
                candidates.add(descriptor.modelFile());
            }
            candidates.addAll(List.of(AUTO_DETECT_ORDER));

            List<String> present = new ArrayList<>(candidates.size());
            for (String candidate : candidates) {
                if (probe.exists(candidate)) {
                    present.add(candidate);
                }
            }
            if (present.isEmpty()) {
                tried.add(root);
                continue;
            }
            Model3D.LOGGER.debug("Model3D: {} resolved under the '{}' root (found {})",
                    location.modelId(), root, present);
            // chooseMainFile applies the same preference order to both the explicit descriptor
            // choice and the conventional names, so a descriptor naming a file that is absent still
            // produces a useful error rather than a silent fallback.
            return new PackLookup(descriptor, present, true, tried, false);
        }
        return new PackLookup(ModelDescriptor.defaults(), List.of(), false, tried, false);
    }

    /**
     * Second attempt at a pack-hosted model, reading this mod's own classpath.
     *
     * <p>Only tried after the resource manager has failed, because the manager is the only reader
     * that can also serve models contributed by <b>other</b> packs; this covers this mod's own
     * resources when the manager will not open them.
     */
    private PackLookup classpathDescriptor(ModelLocation location) {
        List<String> tried = new ArrayList<>(ModelLocation.roots().length);
        for (String root : ModelLocation.roots()) {
            ClasspathModelSource probe = new ClasspathModelSource(location, root, "");
            tried.add(probe.resourcePrefix());
            List<String> files = probe.listFilesOrEmpty();
            if (files.isEmpty()) {
                continue;
            }
            ModelDescriptor descriptor = DescriptorLoader.read(probe, location);
            Model3D.LOGGER.debug("Model3D: {} resolved from the classpath at '{}' (found {})",
                    location.modelId(), probe.resourcePrefix(), files);
            return new PackLookup(descriptor, files, true, tried, true);
        }
        return new PackLookup(ModelDescriptor.defaults(), List.of(), false, tried, false);
    }

    /**
     * One lookup result: the descriptor, the files found, and which reader produced them.
     *
     * @param fromClasspath true when the classpath reader produced this result, which decides which
     *                      {@link ModelSource} the caller constructs to actually read the bytes
     */
    private record PackLookup(ModelDescriptor descriptor, List<String> files, boolean found,
                              List<String> rootsTried, boolean fromClasspath) {
    }

    /**
     * Resolves, reads, parses and describes one model. Returns null (with a reason logged) for
     * every failure; there is no exception path here on purpose, because "this entity's model is
     * broken" must not take down the frame that draws it.
     */
    private ModelHandle load(ResourceManager resourceManager, ResourceLocation modelId,
                             boolean clientSide) {
        ModelLocation location = ModelLocation.of(modelId);
        String side = clientSide ? "client" : "server";

        ModelSource source = null;
        try {
            Path external = externalModelDirectory(location);
            String mainRelative;
            ModelDescriptor descriptor;
            List<String> available;
            if (Files.isDirectory(external)) {
                DirectoryModelSource directorySource = new DirectoryModelSource(external,
                        location.namespace(), "");
                descriptor = DescriptorLoader.read(directorySource, location);
                available = directorySource.listFiles();
                // A loose dropped-in file shares its folder with every other loose file, so the name
                // the user typed has to select the file. Without this, "su30" would load whichever
                // model file happened to sort first in config/3dmodels.
                String dropped = mainFileFromDroppedFile(location, available);
                mainRelative = dropped != null ? dropped
                        : chooseMainFile(descriptor, available, location);
                source = new DirectoryModelSource(external, location.namespace(), mainRelative);
            } else {
                PackLookup lookup = packDescriptor(resourceManager, location);
                if (!lookup.found()) {
                    // The resource manager could not serve the directory. Try this mod's own
                    // classpath before giving up: a ForgeGradle development server has been observed
                    // to enumerate a file it then refuses to open, so the resource manager is not the
                    // only possible reader of the mod's own resources. See ClasspathModelSource.
                    PackLookup classpath = classpathDescriptor(location);
                    if (!classpath.found()) {
                        throw ModelParseException.at(location.modelRootPath(),
                                "no model directory found via the resource manager (roots tried "
                                        + lookup.rootsTried() + ") or via this mod's classpath "
                                        + "(prefixes tried " + classpath.rootsTried() + ") for "
                                        + "namespace '" + location.namespace() + "'");
                    }
                    lookup = classpath;
                }
                descriptor = lookup.descriptor();
                available = lookup.files();
                mainRelative = chooseMainFile(descriptor, available, location);
                source = lookup.fromClasspath()
                        ? new ClasspathModelSource(location, mainRelative)
                        : new PackModelSource(resourceManager, location, mainRelative);
            }

            ModelScene scene = ModelFormatRegistry.parse(source, modelId.toString());
            ModelHandle handle = new ModelHandle(modelId.toString(), scene, source.mainPath(),
                    descriptor);
            Model3D.LOGGER.info("Model3D[{}]: loaded {} from {} - nodes={} meshes={} materials={} "
                            + "skins={} animations={} bounds=[{}, {}, {} .. {}, {}, {}] "
                            + "descriptor={}",
                    side, modelId, source.mainPath(), scene.nodeCount(), scene.meshes().length,
                    scene.materials().length, scene.skins().length, scene.animations().size(),
                    fmt(scene.bounds()[0]), fmt(scene.bounds()[1]), fmt(scene.bounds()[2]),
                    fmt(scene.bounds()[3]), fmt(scene.bounds()[4]), fmt(scene.bounds()[5]),
                    descriptor);
            for (var animation : scene.animations()) {
                Model3D.LOGGER.info("Model3D[{}]:   animation '{}' tracks={} duration={}s",
                        side, animation.name(), animation.trackCount(), fmt(animation.duration()));
            }
            if (!scene.embeddedImages().isEmpty()) {
                Model3D.LOGGER.info("Model3D[{}]: {} embedded image(s), {} bytes total: {}",
                        side, scene.embeddedImages().size(),
                        scene.embeddedImages().stream().mapToInt(
                                com.model3d.loader.scene.ModelImage::byteSize).sum(),
                        scene.embeddedImages());
            }
            if (scene.meshes().length == 0) {
                Model3D.LOGGER.warn("Model3D[{}]: {} parsed but contains no meshes; nothing will "
                        + "be drawn", side, modelId);
            }
            return handle;
        } catch (ModelParseException e) {
            Model3D.LOGGER.error("Model3D[{}]: failed to parse {}: {}", side, modelId, e.getMessage());
            return null;
        } catch (IOException e) {
            Model3D.LOGGER.error("Model3D[{}]: I/O error reading {}: {}", side, modelId,
                    e.getMessage());
            return null;
        } catch (RuntimeException e) {
            // A parser bug on an untrusted file must degrade to "no model", not to a crash loop
            // on every frame that tries to draw it.
            Model3D.LOGGER.error("Model3D[{}]: unexpected failure loading {}", side, modelId, e);
            return null;
        } finally {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException e) {
                    Model3D.LOGGER.debug("Model3D: closing {} failed: {}", modelId, e.getMessage());
                }
            }
        }
    }

    /**
     * Picks the file to parse: the descriptor's explicit choice when given, otherwise the first
     * of {@link #AUTO_DETECT_ORDER} that the directory actually holds.
     *
     * <p>The preference order is not arbitrary. A GLB is self-contained - geometry, skinning,
     * animation and often textures in one file - while its sibling {@code .gltf} export needs
     * external {@code .bin} and image files that are frequently not shipped. When both exist,
     * GLB is the one that will actually load.
     */
    private String chooseMainFile(ModelDescriptor descriptor, List<String> available,
                                  ModelLocation location) throws ModelParseException {
        String explicit = descriptor.modelFile();
        if (explicit != null) {
            for (String file : available) {
                if (file.equalsIgnoreCase(explicit)) {
                    return file;
                }
            }
            throw ModelParseException.at(location.modelRootPath(),
                    "model.json names '" + explicit + "' but the directory holds " + available);
        }
        for (String preferred : AUTO_DETECT_ORDER) {
            for (String file : available) {
                if (file.equalsIgnoreCase(preferred)) {
                    return file;
                }
            }
        }
        // No conventional name: fall back to any recognised extension, shallowest first, so a
        // model directory that just holds `Su30.glb` works without a descriptor.
        List<String> candidates = new ArrayList<>();
        for (String file : available) {
            if (ModelFormat.byPath(file) != null) {
                candidates.add(file);
            }
        }
        if (candidates.isEmpty()) {
            throw ModelParseException.at(location.modelRootPath(),
                    "no model file found; expected one of " + List.of(AUTO_DETECT_ORDER)
                            + " or any " + ModelFormatRegistry.supportedExtensions()
                            + " file. Directory holds: " + available);
        }
        candidates.sort(Comparator.comparingInt((String path) -> path.split("/").length)
                .thenComparing(Comparator.naturalOrder()));
        if (candidates.size() > 1) {
            Model3D.LOGGER.info("Model3D: {} holds {} candidate model files {}; using '{}'. "
                            + "Name one in model.json to remove the ambiguity.",
                    location.modelRootPath(), candidates.size(), candidates, candidates.get(0));
        }
        return candidates.get(0);
    }

    /**
     * The folder on disk that provides {@code location}, or null when no dropped-in folder has it.
     *
     * <h2>Three layouts, and the loose file is the important one</h2>
     * <ol>
     *   <li><b>{@code config/3dmodels/<name>.glb}</b> - a bare file. The model is the file, and its
     *       "<i>directory</i>" is the folder holding it, so the loader's existing directory logic
     *       works unchanged and the file itself becomes the main file. This is the layout a user gets
     *       by dragging a download into the folder, which is exactly why it has to work.</li>
     *   <li><b>{@code config/3dmodels/<name>/}</b> - a folder, with the model and its textures
     *       inside. Conventional file names are looked for within it.</li>
     *   <li><b>{@code <gamedir>/model3d/<namespace>/<name>/}</b> - the original namespaced layout,
     *       still supported so earlier models keep working.</li>
     * </ol>
     *
     * <p>Only the namespace this mod owns is served from the dropped-in folders: a folder there has
     * no way to express a namespace, so {@code somepack:thing} cannot come from it and is left to the
     * pack path. That is why the legacy layout is still consulted - it is the one that can carry a
     * namespace.
     */
    private Path externalModelDirectory(ModelLocation location) {
        if (location.namespace().equals(Model3D.MOD_ID)) {
            String name = location.name();
            for (ModelLibrary library : libraries()) {
                Path found = library.discover().get(name);
                if (found != null) {
                    // A loose file's parent is the folder to load from, and the file is the main
                    // file; a folder is used as-is. Returning the file's parent in both cases keeps
                    // one code path downstream.
                    return Files.isDirectory(found) ? found : found.getParent();
                }
            }
        }
        Path root = ExternalModelPaths.legacyModels();
        if (root == null) {
            return null;
        }
        // Names may contain '/', which must expand to nested directories rather than being
        // treated as a single name on Windows.
        Path resolved = root.resolve(location.namespace()).resolve(location.name()).normalize();
        Path namespaceRoot = root.resolve(location.namespace()).normalize();
        if (!resolved.startsWith(namespaceRoot)) {
            Model3D.LOGGER.warn("Model3D: refusing model name '{}' that escapes {}", location.name(),
                    namespaceRoot);
            return null;
        }
        return resolved;
    }

    /**
     * The file to read for a model that shares its folder with other models, or null to auto-detect.
     *
     * <p>Package-private rather than private so {@code ModelLoadServiceTest} can pin it: this is the
     * mechanism that makes several loose files in one folder each loadable by their own name, and
     * without it every name would resolve to whichever file happened to sort first.
     */
    String mainFileFromDroppedFile(ModelLocation location, List<String> available) {        if (!location.namespace().equals(Model3D.MOD_ID) || available.size() <= 1) {
            return null;
        }
        String name = location.name();
        // The model name has no extension, so match against each candidate's stem, through the same
        // normalisation the discovery step used.
        for (String candidate : available) {
            String withoutExtension = candidate;
            int dot = withoutExtension.lastIndexOf('.');
            if (dot > 0) {
                withoutExtension = withoutExtension.substring(0, dot);
            }
            if (ModelLibrary.normalise(withoutExtension).equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    private static String key(ResourceLocation modelId) {
        return modelId.toString().toLowerCase(Locale.ROOT);
    }

    private static String fmt(float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    /**
     * Reads the descriptor if present. A broken descriptor is reported and then ignored, so the
     * model still loads from auto-detection instead of becoming unloadable because of one bad
     * character in a side file.
     */
    private static final class DescriptorLoader {

        private DescriptorLoader() {
        }

        static ModelDescriptor read(AbstractModelSource source, ModelLocation location) {
            ModelSource.Reference reference;
            try {
                reference = source.open(ModelDescriptor.FILE_NAME);
            } catch (IOException e) {
                Model3D.LOGGER.warn("Model3D: cannot read {} for {}: {}", ModelDescriptor.FILE_NAME,
                        location.modelId(), e.getMessage());
                return ModelDescriptor.defaults();
            }
            if (!reference.isPresent()) {
                return ModelDescriptor.defaults();
            }
            try (InputStream stream = reference.data()) {
                String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                return ModelDescriptor.parse(json);
            } catch (IOException | ModelDescriptor.ModelDescriptorException e) {
                Model3D.LOGGER.warn("Model3D: ignoring {} for {}: {}", ModelDescriptor.FILE_NAME,
                        location.modelId(), e.getMessage());
                return ModelDescriptor.defaults();
            }
        }
    }
}
