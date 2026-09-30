package com.model3d.loader.resource;

import com.model3d.loader.Model3D;
import com.model3d.loader.format.ModelSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared path logic for every {@link ModelSource}: normalization, escape rejection,
 * case-insensitive fallback and unique-basename fallback.
 *
 * <p>Subclasses supply only "list every file under the model root" and "open this exact
 * relative path, or return null". Everything else - and everything that is easy to get subtly
 * wrong - lives here once.
 *
 * <h2>Resolution order</h2>
 * <ol>
 *   <li>Exact path, as written in the model file.</li>
 *   <li>Same path, lower-cased. Minecraft's own resource paths are lower-case, so a
 *       {@code Textures/X.png} reference into a pack can only resolve this way.</li>
 *   <li>Unique basename match anywhere under the model root. This is the last resort for the
 *       very common "exporter dropped the folder" case, e.g. a glTF naming
 *       {@code textures/foo.png} when the pack has {@code foo.png} at the top. It is only taken
 *       when exactly one candidate matches, because silently picking one of several same-named
 *       files is how a model ends up wearing the wrong livery.</li>
 * </ol>
 *
 * <p>The file index needed by steps 2 and 3 is built lazily, once, and cached - listing a large
 * pack on every texture lookup would dominate load time.
 */
public abstract class AbstractModelSource implements ModelSource {

    private final String namespace;
    private final String modelRoot;
    private final String mainRelativePath;

    /** Lower-cased relative path -> real relative path. Built lazily; see the class comment. */
    private Map<String, String> fileIndex;
    /** Lower-cased file name -> real relative paths carrying it. Built lazily. */
    private Map<String, List<String>> basenameIndex;

    protected AbstractModelSource(String namespace, String modelRoot, String mainRelativePath) {
        this.namespace = namespace;
        this.modelRoot = modelRoot;
        this.mainRelativePath = mainRelativePath;
    }

    /** Lists every file under the model root as {@code /}-separated paths relative to it. */
    protected abstract List<String> listRelativeFiles() throws IOException;

    /**
     * Opens one exact, already-normalized relative path.
     *
     * @return the stream, or null when nothing is stored at that path
     */
    protected abstract InputStream openExact(String relativePath) throws IOException;

    @Override
    public String mainPath() {
        return modelRoot + "/" + mainRelativePath;
    }

    @Override
    public String namespace() {
        return namespace;
    }

    /** The model directory's path inside the pack, e.g. {@code assets/model3d/model3d/su30}. */
    public String modelRoot() {
        return modelRoot;
    }

    @Override
    public InputStream openMain() throws IOException {
        Reference reference = open(mainRelativePath);
        if (!reference.isPresent()) {
            Model3D.LOGGER.warn("Model3D: {} - {}", mainPath(), reference.note());
        }
        return reference.data();
    }

    @Override
    public Reference open(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty()) {
            return Reference.missing("empty reference in " + mainPath());
        }

        String normalized = normalize(relativePath);
        if (normalized == null) {
            return Reference.missing("reference '" + relativePath
                    + "' escapes its model directory and was refused");
        }

        InputStream exact = openExact(normalized);
        if (exact != null) {
            return new Reference(exact, normalized, true, "exact");
        }

        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.equals(normalized)) {
            InputStream lowerCase = openExact(lower);
            if (lowerCase != null) {
                return new Reference(lowerCase, lower, false,
                        "case-insensitive match for '" + relativePath + "'");
            }
        }

        ensureIndex();
        String indexed = fileIndex.get(lower);
        if (indexed != null) {
            InputStream stream = openExact(indexed);
            if (stream != null) {
                return new Reference(stream, indexed, false,
                        "case-insensitive match for '" + relativePath + "' -> '" + indexed + "'");
            }
        }

        String baseName = basename(lower);
        List<String> candidates = basenameIndex.get(baseName);
        if (candidates != null && candidates.size() == 1) {
            String candidate = candidates.get(0);
            InputStream stream = openExact(candidate);
            if (stream != null) {
                return new Reference(stream, candidate, false,
                        "'" + relativePath + "' not found; using the only file named '"
                                + baseName + "' in this model: '" + candidate + "'");
            }
        }

        String detail = "'" + relativePath + "' not found under " + modelRoot;
        if (candidates != null && candidates.size() > 1) {
            detail += " (" + candidates.size() + " files share that name, so no unique fallback: "
                    + candidates + ")";
        } else if (fileIndex.isEmpty()) {
            detail += " (this model directory holds no visible files)";
        }
        return Reference.missing(detail);
    }

    @Override
    public List<String> listFiles() throws IOException {
        ensureIndex();
        List<String> files = new ArrayList<>(fileIndex.values());
        files.sort(String::compareTo);
        return files;
    }

    /**
     * Like {@link #listFiles()} but never throws: an unlistable source yields an empty list.
     *
     * <p>For callers that treat "cannot enumerate" and "directory is empty" the same way, which is
     * the right default for a loader that must keep going - and explicitly not the right default
     * for a diagnostic, which is why both exist.
     */
    public List<String> listFilesOrEmpty() {
        try {
            return listFiles();
        } catch (IOException e) {
            Model3D.LOGGER.warn("Model3D: cannot list {}: {}", mainPath(), e.getMessage());
            return List.of();
        }
    }

    /**
     * True when {@code relativePath} resolves to a file, going through the same
     * normalization-and-fallback path as {@link #open}.
     *
     * <p>This is the probe a caller uses when enumeration is untrustworthy or unavailable: a
     * resource-manager directory listing can be empty for a directory it can nonetheless serve
     * file-by-file, so "does this exact path exist" and "what is in this directory" are genuinely
     * independent questions.
     */
    public boolean exists(String relativePath) {
        try {
            Reference reference = open(relativePath);
            if (reference.isPresent()) {
                // open() hands back a live stream; a probe must not leak it.
                reference.data().close();
                return true;
            }
            return false;
        } catch (IOException e) {
            Model3D.LOGGER.debug("Model3D: probing {} in {} failed: {}", relativePath, mainPath(),
                    e.getMessage());
            return false;
        }
    }

    private void ensureIndex() throws IOException {
        if (fileIndex != null) {
            return;
        }
        Map<String, String> byPath = new HashMap<>();
        Map<String, List<String>> byName = new HashMap<>();
        for (String relative : listRelativeFiles()) {
            String normalized = normalize(relative);
            if (normalized == null) {
                continue;
            }
            String lower = normalized.toLowerCase(Locale.ROOT);
            byPath.putIfAbsent(lower, normalized);
            byName.computeIfAbsent(basename(lower), key -> new ArrayList<>(2)).add(normalized);
        }
        this.fileIndex = byPath;
        this.basenameIndex = byName;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Canonicalizes a reference taken from a model file: decodes percent escapes, converts
     * backslashes (Windows exporters write them), resolves {@code .} and {@code ..}.
     *
     * @return the relative path, or null when it escapes the model directory
     */
    static String normalize(String raw) {
        String path = raw.trim().replace('\\', '/');
        // glTF and MTL both allow percent-encoded URIs; "my%20texture.png" is a real reference
        // to "my texture.png" and is otherwise guaranteed to miss.
        if (path.indexOf('%') >= 0) {
            path = decodePercentEscapes(path);
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (path.startsWith("/")) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    // A model file is untrusted input: `../../../../etc/passwd` must not be
                    // followable, so an escaping reference is refused rather than clamped.
                    return null;
                }
                segments.remove(segments.size() - 1);
                continue;
            }
            segments.add(segment);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    private static String decodePercentEscapes(String path) {
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                int hi = Character.digit(path.charAt(i + 1), 16);
                int lo = Character.digit(path.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.append((char) ((hi << 4) | lo));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
