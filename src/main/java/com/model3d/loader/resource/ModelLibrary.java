package com.model3d.loader.resource;

import com.model3d.loader.Model3D;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A directory of model files the player can drop things into, with change detection for hot reload.
 *
 * <h2>The problem this solves</h2>
 * The mod originally required a precise layout - {@code <gamedir>/model3d/<namespace>/<name>/} - and
 * nothing told the user that. The result was predictable: the mod worked, and the user had no model
 * because they had nowhere obvious to put one. A file-based API whose "where do I put the file" is
 * undocumented is not usable, however well it renders.
 *
 * <p>So the supported layout is maximally forgiving, and everything in this directory is a model:
 *
 * <pre>
 *   config/3dmodels/su30.glb              -> model3d:su30
 *   config/3dmodels/My Plane.glb          -> model3d:my_plane          (name is normalised)
 *   config/3dmodels/su30/model.glb        -> model3d:su30             (folder, any file name)
 *   config/3dmodels/su30/model.json       -> settings for the folder
 *   config/3dmodels/su30/textures/*.png   -> textures beside the model
 * </pre>
 *
 * <h2>The rule, in one sentence</h2>
 * <b>A folder that contains a model file is one model</b> (named after the folder); <b>a model file
 * that no enclosing candidate folder claims is one model</b> (named after the file). Everything below
 * a model folder belongs to it.
 *
 * <p>That rule is what makes all of these do the obvious thing, and it took a failing test to reach
 * it - the first implementation registered {@code jets/} and {@code jets/su30.glb} as two separate
 * models, so dropping a file into a sub-folder silently produced a duplicate entry:
 *
 * <pre>
 *   config/3dmodels/su30.glb              -> model3d:su30          (a loose file at the root)
 *   config/3dmodels/jets/su30.glb         -> model3d:jets          (its enclosing folder)
 *   config/3dmodels/tanks/m1a2/model.obj  -> model3d:tanks/m1a2    (nothing encloses it but the root)
 * </pre>
 *
 * <p>The name is therefore the <b>outermost</b> enclosing candidate folder, or the file's own path
 * when no folder encloses it. {@code ModelLibraryTest} asserts exactly these examples, so the rule
 * and the documentation cannot drift apart.
 *
 * <h2>Hot reload</h2>
 * {@link #signature()} is a cheap fingerprint of the whole directory - every file's path, size and
 * modification time. Comparing it against the previous value is what lets a dropped file appear
 * without restarting the game, and it correctly catches edits, additions and deletions. It is
 * recomputed at most once per {@link #POLL_MILLIS} so a per-tick check costs almost nothing.
 *
 * <p>Modification time alone is deliberately not enough: a file copied into place can carry an older
 * timestamp than the one it replaces, and a model directory can change without its own mtime moving.
 * Path, size and mtime together change for every edit a player can make.
 */
public final class ModelLibrary {

    /** The directory, relative to the game's {@code config} directory. */
    public static final String DIRECTORY_NAME = "3dmodels";

    /** How often {@link #signature()} recomputes, in milliseconds. */
    private static final long POLL_MILLIS = 1000L;

    /** Extensions a dropped file may have; anything else is ignored rather than treated as a model. */
    private static final String[] MODEL_EXTENSIONS = { ".glb", ".gltf", ".obj" };

    private final Path root;

    /** Last computed fingerprint, and when it was computed. Guarded by {@code this}. */
    private long cachedSignature;
    private long cachedAt;
    private boolean signatureValid;

    public ModelLibrary(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public boolean exists() {
        return root != null && Files.isDirectory(root);
    }

    /** Creates the directory if absent, and writes a README explaining the layout on first run. */
    public void prepare() {
        if (root == null) {
            return;
        }
        try {
            Files.createDirectories(root);
            writeReadmeIfAbsent();
        } catch (IOException e) {
            Model3D.LOGGER.warn("Model3D: cannot create the model folder at {}: {}", root,
                    e.getMessage());
        }
    }

    /**
     * Writes {@code README.txt} beside the models when it is missing.
     *
     * <p>A README rather than only a log line, because the question it answers - "where does the file
     * go, and what do I type" - is asked while looking at the folder, not at the log. Recreated when
     * absent so a user who deletes it gets it back, and never overwritten so local notes survive.
     */
    private void writeReadmeIfAbsent() {
        Path readme = root.resolve("README.txt");
        if (Files.exists(readme)) {
            return;
        }
        String text = String.join(System.lineSeparator(),
                "Model3D Loader - put 3D models in this folder",
                "============================================",
                "",
                "Supported files: .glb, .gltf (with its .bin and textures beside it), .obj (+ .mtl).",
                "",
                "Drop a file straight in here:",
                "",
                "    su30.glb                 ->  model name \"su30\"",
                "    My Plane.glb             ->  model name \"my_plane\"",
                "",
                "Or put a model in its own folder when it has several files:",
                "",
                "    su30/model.glb           ->  model name \"su30\"",
                "    su30/model.json          ->  optional settings (scale, yaw offset, ...)",
                "    su30/textures/*.png      ->  textures referenced by the model",
                "",
                "The file name becomes the model name: lower-cased, spaces and other punctuation",
                "become underscores.",
                "",
                "Sub-folders are allowed; the outermost folder holding a model names it:",
                "",
                "    jets/su30.glb            ->  model name \"jets\"",
                "    tanks/m1a2/model.obj     ->  model name \"tanks/m1a2\"",
                "",
                "Changes are picked up automatically within about a second - no restart needed.",
                "",
                "This mod is the loader: it has no command of its own. A mod that uses it - the",
                "companion Model3D test mod, for instance - provides the one that spawns a model, and",
                "that command's help is the place to look for what to type.",
                "",
                "Models shipped inside a mod jar appear in a model list as <namespace>:<name>.",
                "");
        try {
            Files.writeString(readme, text);
        } catch (IOException e) {
            Model3D.LOGGER.debug("Model3D: cannot write the README at {}: {}", readme,
                    e.getMessage());
        }
    }

    /**
     * Every model this folder provides, keyed by the name a user types.
     *
     * <p>Implements the outermost-folder rule described in the class comment:
     * <ol>
     *   <li>collect every directory that <b>directly</b> holds a model file (a candidate);</li>
     *   <li>drop any candidate that lies under another candidate, so {@code jets/} absorbs
     *       {@code jets/sub/} instead of the two both becoming models;</li>
     *   <li>a model file is a model only when no surviving candidate encloses it.</li>
     * </ol>
     *
     * @return name (normalised, no namespace) to the file or directory that provides it
     */
    public Map<String, Path> discover() {
        Map<String, Path> found = new HashMap<>();
        if (!exists()) {
            return found;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> entries = new ArrayList<>();
            walk.forEach(entries::add);
            entries.sort(java.util.Comparator.comparing(Path::toString));

            List<Path> candidates = new ArrayList<>();
            for (Path entry : entries) {
                if (!entry.equals(root) && Files.isDirectory(entry)
                        && containsAModelFile(entry)) {
                    candidates.add(entry);
                }
            }
            List<Path> outermost = new ArrayList<>();
            for (Path candidate : candidates) {
                if (!enclosedByOther(candidate, candidates)) {
                    outermost.add(candidate);
                }
            }
            for (Path directory : outermost) {
                put(found, normalise(relativeName(directory)), directory);
            }
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry) || !isModelFile(entry)) {
                    continue;
                }
                if (enclosedBy(entry, outermost)) {
                    // A part of a model folder, not a model of its own.
                    continue;
                }
                put(found, normalise(stripExtension(relativeName(entry))), entry.getParent());
            }
        } catch (IOException e) {
            Model3D.LOGGER.warn("Model3D: cannot scan the model folder {}: {}", root, e.getMessage());
        }
        return found;
    }

    /**
     * True when any of {@code containers} equals or contains {@code path}.
     *
     * <p>Self-membership counts here, which is what the caller wants when asking "is this file part
     * of that model folder".
     */
    private static boolean enclosedBy(Path path, List<Path> containers) {
        for (Path container : containers) {
            if (path.equals(container) || path.startsWith(container)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when a container <b>other than {@code path} itself</b> encloses {@code path}.
     *
     * <p>The distinction is load-bearing and was a bug: filtering the candidate list with the
     * self-inclusive version dropped every candidate, because each one trivially encloses itself.
     * The result was that a model folder was never registered under its own name - a model in
     * {@code su30/model.glb} appeared as {@code su30/model} instead of {@code su30}, silently and in
     * every case. A one-line test found it; no amount of reading the loop had.
     */
    private static boolean enclosedByOther(Path path, List<Path> containers) {
        for (Path container : containers) {
            if (!path.equals(container) && path.startsWith(container)) {
                return true;
            }
        }
        return false;
    }

    private void put(Map<String, Path> found, String name, Path location) {
        Path previous = found.putIfAbsent(name, location);
        if (previous != null) {
            // Two files normalise to the same name - "My Plane.glb" and "my_plane.glb". Reported
            // rather than silently resolved, because the user would otherwise see one of their
            // models simply never load.
            Model3D.LOGGER.warn("Model3D: two entries in {} both mean the model '{}': {} and {}."
                            + " Rename one.", root, name, previous, location);
        }
    }

    private boolean containsAModelFile(Path directory) {
        try (Stream<Path> children = Files.list(directory)) {
            return children.anyMatch(path -> Files.isRegularFile(path) && isModelFile(path));
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isModelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String extension : MODEL_EXTENSIONS) {
            if (name.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private String relativeName(Path entry) {
        return root.relativize(entry).toString().replace('\\', '/');
    }

    /**
     * Turns a file or folder name into a usable model name: lower case, and anything that is not
     * {@code [a-z0-9/._-]} replaced by {@code _}.
     *
     * <p>Necessary because the name reaches a {@code ResourceLocation}, whose path rules are stricter
     * than any file system's - "My Plane.glb" is a perfectly good file name and an invalid id.
     * Normalising is better than rejecting: the user should be able to name their files whatever
     * their file manager allows and still load them.
     */
    public static String normalise(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '/';
            out.append(allowed ? c : '_');
        }
        // Collapse runs so "My  Plane" does not become "my__plane", and trim separator noise.
        String collapsed = out.toString().replaceAll("_{2,}", "_").replaceAll("^[/_]+", "")
                .replaceAll("[/_]+$", "");
        return collapsed.isEmpty() ? "model" : collapsed;
    }

    /**
     * A fingerprint of the whole folder, or 0 when it does not exist.
     *
     * <p>Cached for {@link #POLL_MILLIS}: a per-tick caller asks every tick, and walking the tree
     * sixty times a second to discover that nothing changed is pure waste. The cache is short enough
     * that a file dropped in is noticed within about a second, which is below the threshold where a
     * user would think about it.
     *
     * <p>Not thread-safe by intent - callers are the server and client tick, each on its own thread.
     * A torn read at worst recomputes a signature a moment early.
     */
    public long signature() {
        long now = System.currentTimeMillis();
        if (signatureValid && now - cachedAt < POLL_MILLIS) {
            return cachedSignature;
        }
        long signature = computeSignature();
        cachedSignature = signature;
        cachedAt = now;
        signatureValid = true;
        return signature;
    }

    private long computeSignature() {
        if (!exists()) {
            return 0L;
        }
        long hash = 1125899906842597L; // a prime, so short fingerprints still separate
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            // Sorted, so the fingerprint cannot depend on filesystem enumeration order - a directory
            // whose order changes between reads would otherwise look like an edit every poll.
            paths.sort(java.util.Comparator.comparing(Path::toString));
            for (Path path : paths) {
                if (!Files.isRegularFile(path) || isIgnored(path)) {
                    continue;
                }
                hash = hash * 31 + relativeName(path).hashCode();
                try {
                    hash = hash * 31 + Files.size(path);
                    hash = hash * 31 + Files.getLastModifiedTime(path).toMillis();
                } catch (IOException e) {
                    // A file that vanished between the walk and the stat is a change in itself.
                    hash = hash * 31 + 1;
                }
            }
        } catch (IOException e) {
            return 0L;
        }
        return hash;
    }

    /** Files that must not count as a change: our own README, and editor/OS droppings. */
    private static boolean isIgnored(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("readme.txt") || name.startsWith(".") || name.endsWith(".tmp")
                || name.endsWith(".bak") || name.endsWith("~");
    }

    /** Forces the next {@link #signature()} to recompute. Used after a manual rescan. */
    public void invalidateSignature() {
        signatureValid = false;
    }

    @Override
    public String toString() {
        return "ModelLibrary(" + root + ")";
    }
}
