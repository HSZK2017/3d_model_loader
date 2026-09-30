package com.model3d.loader.format.obj;

import java.util.ArrayList;
import java.util.List;

/**
 * Path handling for OBJ/MTL references.
 *
 * <p>{@link com.model3d.loader.format.ModelSource} resolves one model-relative path per call and
 * owns case-insensitivity, percent-decoding and "must not escape the model root". What it cannot
 * know is the two things this class fixes up first, because they are properties of the OBJ/MTL
 * dialects rather than of a model source:
 *
 * <ul>
 *   <li>Exporters write Windows separators and quotes: {@code textures\Glass.jpeg},
 *       {@code "textures/Glass.jpeg"}.</li>
 *   <li>A path inside an {@code .mtl} is relative to <b>that MTL's own directory</b> per the spec,
 *       but most exporters write it relative to the OBJ root anyway. Both candidates are therefore
 *       offered, spec-first, and the first one the source can open wins.</li>
 * </ul>
 */
final class ObjPaths {

    private ObjPaths() {
    }

    /**
     * Normalises a reference written inside a model file into a {@code /}-separated, dot-free,
     * model-relative path.
     *
     * <p>Dot segments are collapsed here rather than left to the source, so {@code mtl/../tex/a.png}
     * arrives as {@code tex/a.png} while a genuinely root-escaping {@code ../../a.png} is passed
     * through unchanged for the source to reject - rejecting it is the source's policy decision and
     * silently rewriting it would read a file the model never named.
     */
    static String normalize(String raw) {
        String path = raw == null ? "" : raw.strip();
        if (path.length() >= 2
                && (path.startsWith("\"") && path.endsWith("\"")
                || path.startsWith("'") && path.endsWith("'"))) {
            path = path.substring(1, path.length() - 1).strip();
        }
        path = path.replace('\\', '/');
        // A leading slash is how exporters spell "relative to the model root"; there is no
        // filesystem root in a resource pack, so drop it instead of asking the source for "//...".
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (!segments.isEmpty() && !segments.get(segments.size() - 1).equals("..")) {
                    segments.remove(segments.size() - 1);
                } else {
                    segments.add("..");
                }
                continue;
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    /** Directory part of a normalised path, or "" when the path has no directory. */
    static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Paths to try for a reference found in {@code directory}: relative to that directory first
     * (the spec), then relative to the model root (what most exporters actually emit).
     */
    static List<String> candidates(String directory, String path) {
        List<String> candidates = new ArrayList<>(2);
        if (!directory.isEmpty()) {
            String relative = normalize(directory + "/" + path);
            if (!relative.isEmpty()) {
                candidates.add(relative);
            }
        }
        if (!candidates.contains(path)) {
            candidates.add(path);
        }
        return candidates;
    }
}
