package com.model3d.loader.resource;

import com.model3d.loader.Model3D;
import com.model3d.loader.format.ModelSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A {@link ModelSource} that reads a model out of the mod's own classpath: the development
 * {@code resources} output, a mod jar, or an exploded pack directory.
 *
 * <h2>Why this exists alongside {@link PackModelSource}</h2>
 * A resource-manager lookup for a namespace that a pack declares does not always work in a
 * ForgeGradle development server. Measured on one, through a live {@code ResourceManager}:
 * <ul>
 *   <li>{@code listResources("model3d")} returned {@code model3d:model3d/animated_test/model.glb}
 *       - so the manager could enumerate the model file and knew the right namespace;</li>
 *   <li>{@code getResource("model3d:model3d/animated_test/model.glb")} for that exact location
 *       returned empty, as did {@code listResources} one directory deeper;</li>
 *   <li>the pack itself reported {@code SERVER_DATA ns=[model3d]}, i.e. it declared the namespace,
 *       and the file was present on disk in the resources output.</li>
 * </ul>
 * A manager that will enumerate a file it then refuses to open is not something a loader can be
 * built on, and the failure is invisible in the enumerate-only path that name discovery uses - which
 * is exactly how it survived to a running server before being caught.
 *
 * <p>Reading through the classloader sidesteps that: a mod's own resources are on its classpath in
 * both a dev environment and a production jar, and the lookup is a plain
 * {@code getResourceAsStream}. {@link PackModelSource} remains the first choice because it is the
 * only one that can also read models contributed by <b>other</b> packs; this is the fallback for
 * this mod's own resources when the manager cannot serve them.
 *
 * <p>Keyed by namespace so a model is only reachable through the mod id that actually ships it:
 * {@code ClassLoader.getResourceAsStream} searches the whole classpath, so an unqualified lookup
 * would happily read another mod's identically-named file.
 */
public final class ClasspathModelSource extends AbstractModelSource {

    /**
     * Build-generated sibling listing, one relative path per line.
     *
     * <p>Exists because a classloader cannot enumerate a directory: without a manifest of some kind
     * a classpath-backed source can only probe names it already knows. Produced by the
     * {@code generateModelIndex} Gradle task for every model directory under {@code src/main/resources}.
     */
    public static final String INDEX_FILE = "files.txt";

    private final String resourcePrefix;
    private final ClassLoader classLoader;

    /**
     * @param root         pack root directory name, one of {@link ModelLocation#roots()}
     * @param mainRelative model file path relative to the model directory, or "" to list only
     */
    public ClasspathModelSource(ModelLocation location, String root, String mainRelativePath) {
        super(location.namespace(), location.rootPath(root), mainRelativePath);
        this.resourcePrefix = root + "/" + location.namespace() + "/" + ModelLocation.MODEL3D_ROOT
                + "/" + location.name() + "/";
        // The mod's own classloader, not the thread context one: ModLauncher loads mod classes in a
        // transforming loader that sees the mod's resources, while the context loader may not.
        this.classLoader = ClasspathModelSource.class.getClassLoader();
    }

    /** Reads the canonical {@code data} root; used by diagnostics and tests. */
    public ClasspathModelSource(ModelLocation location, String mainRelativePath) {
        this(location, ModelLocation.DATA_ROOT, mainRelativePath);
    }

    @Override
    protected List<String> listRelativeFiles() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        collectFromIndex(names);
        if (names.isEmpty()) {
            // No index resource, so fall back to probing the conventional names. An exploded
            // resources directory in a dev environment exposes no index, and "one .glb named
            // model.glb" is the overwhelmingly common shape, so this covers it without a scan.
            for (String candidate : new String[] { ModelDescriptor.FILE_NAME, "model.glb",
                    "model.gltf", "model.obj" }) {
                if (classLoader.getResource(resourcePrefix + candidate) != null) {
                    names.add(candidate);
                }
            }
        }
        List<String> files = new ArrayList<>(names);
        files.sort(String::compareTo);
        return files;
    }

    /**
     * Reads the optional file index this mod publishes for exactly this purpose.
     *
     * <p>A classloader cannot enumerate a directory, and neither can every pack implementation, so
     * the list of a model's sibling files is generated at build time into
     * {@code <root>/<ns>/model3d/<name>/files.txt} (see the {@code generateModelIndex} task). One
     * line per file, {@code /}-separated, relative to the model directory. When it is missing - an
     * exploded directory, or a pack built before the task existed - listing degrades to the
     * conventional-name probe above rather than failing.
     *
     * <p>Alternative for a directory on disk: {@link DirectoryModelSource} walks the real filesystem
     * and needs no index at all, which is why the external {@code <gamedir>/model3d/} path is the
     * recommended way to develop a model.
     */
    private void collectFromIndex(Set<String> names) throws IOException {
        try (InputStream index = classLoader.getResourceAsStream(
                resourcePrefix + INDEX_FILE)) {
            if (index == null) {
                return;
            }
            for (String line : new String(index.readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).split("\r?\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    names.add(trimmed);
                }
            }
        }
    }

    @Override
    protected InputStream openExact(String relativePath) {
        // A classloader lookup is already case-sensitive-or-not depending on the filesystem under
        // it, so the case-insensitive retry is delegated to AbstractModelSource.open(), which
        // consults the listing. That listing is the index above.
        return classLoader.getResourceAsStream(resourcePrefix + relativePath);
    }

    /** The exact resource path this source reads from, for diagnostics. */
    public String resourcePrefix() {
        return resourcePrefix;
    }


    @Override
    public String toString() {
        return "ClasspathModelSource(" + resourcePrefix + ")";
    }
}
