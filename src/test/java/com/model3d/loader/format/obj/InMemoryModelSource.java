package com.model3d.loader.format.obj;

import com.model3d.loader.format.ModelSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link ModelSource} backed by a map, standing in for Minecraft's resource manager.
 *
 * <p>It implements the semantics the interface promises and the parser relies on, rather than
 * whatever is convenient: a miss is a {@code Reference.missing} and never null, a lookup falls back
 * to a case-insensitive match and reports the path that actually matched in {@code resolvedAs}, and a
 * path that escapes the model root is refused. Keys are the paths a model file may name - relative to
 * the model's own directory, {@code /}-separated - while {@code mainPath} is the full path of the OBJ.
 *
 * <p>It also records every path that was asked for, which is how the tests prove that an MTL option
 * flag was stripped instead of quietly eating the front of a filename.
 */
final class InMemoryModelSource implements ModelSource {

    private final String mainPath;
    private final Map<String, String> files = new LinkedHashMap<>();
    private final Map<String, String> lowerCaseIndex = new LinkedHashMap<>();
    private final List<String> requested = new ArrayList<>();
    private final List<String> failing = new ArrayList<>();

    private InMemoryModelSource(String mainPath) {
        this.mainPath = mainPath;
    }

    static Builder builder(String mainPath) {
        return new Builder(mainPath);
    }

    @Override
    public InputStream openMain() {
        String authored = files.containsKey(mainPath)
                ? mainPath
                : lowerCaseIndex.get(mainPath.toLowerCase(Locale.ROOT));
        return authored == null ? null : stream(authored);
    }

    @Override
    public String mainPath() {
        return mainPath;
    }

    @Override
    public Reference open(String relativePath) throws java.io.IOException {
        requested.add(relativePath);
        if (failing.contains(relativePath)) {
            // Models a pack whose entry cannot be read at all, which is not the same as one that is
            // absent: the parser must survive both without losing the geometry.
            throw new java.io.IOException("injected read failure for '" + relativePath + "'");
        }
        String path = normalize(relativePath);
        if (path == null) {
            return Reference.missing("'" + relativePath + "' escapes the model root");
        }
        String authored = files.containsKey(path) ? path : lowerCaseIndex.get(path.toLowerCase(Locale.ROOT));
        if (authored == null) {
            return Reference.missing("no file '" + path + "' in the model directory");
        }
        boolean exact = authored.equals(path);
        return new Reference(stream(authored), authored, exact,
                exact ? "exact match" : "case-insensitive match for '" + path + "'");
    }

    @Override
    public List<String> listFiles() {
        List<String> listed = new ArrayList<>(files.size());
        for (String path : files.keySet()) {
            listed.add(path.toLowerCase(Locale.ROOT));
        }
        listed.sort(String::compareTo);
        return listed;
    }

    /** Every path this source was asked to open, in order; used to assert what the parser requested. */
    List<String> requestedPaths() {
        return List.copyOf(requested);
    }

    private InputStream stream(String path) {
        return new ByteArrayInputStream(files.get(path).getBytes(StandardCharsets.UTF_8));
    }

    /** Dot-segment resolution; null when the result would climb above the model root. */
    private static String normalize(String path) {
        String cleaned = path.replace('\\', '/');
        List<String> segments = new ArrayList<>();
        for (String segment : cleaned.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    return null;
                }
                segments.remove(segments.size() - 1);
                continue;
            }
            segments.add(segment);
        }
        return String.join("/", segments);
    }

    static final class Builder {

        private final InMemoryModelSource source;

        private Builder(String mainPath) {
            this.source = new InMemoryModelSource(mainPath);
        }

        /** Adds a file under the path a model file would name for it; a repeated path replaces it. */
        Builder file(String path, String content) {
            source.files.put(path, content);
            source.lowerCaseIndex.put(path.toLowerCase(Locale.ROOT), path);
            return this;
        }

        InMemoryModelSource build() {
            return source;
        }

        /** Makes {@code open(path)} throw, standing in for a pack entry that cannot be read. */
        Builder unreadable(String path) {
            source.failing.add(path);
            return this;
        }
    }
}
