package com.model3d.loader.format;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Read-only access to the files a model consists of.
 *
 * <p>A single {@code .glb} is one self-contained file, but a {@code .gltf} points at sibling
 * {@code .bin} and texture files, and an {@code .obj} points at an {@code .mtl} which in turn
 * points at images. A parser must therefore be able to ask for a sibling by the <b>relative
 * path exactly as written in the file</b>, and must not know whether that path resolves to a
 * jar entry, a zip entry, a datapack resource or a directory on disk. That is the whole job of
 * this interface, and it is what keeps every parser free of Minecraft and IO-policy types.
 *
 * <h2>Path semantics (implementations must honour these exactly)</h2>
 * <ul>
 *   <li>Paths are relative to the model's own directory, using {@code /} separators.</li>
 *   <li>Minecraft resource paths are <b>case-sensitive and lower-case only</b>, while OBJ/MTL
 *       files routinely reference {@code Textures/Glass_Cockpit.jpeg}. Implementations must
 *       therefore fall back to a case-insensitive match before reporting "missing", and must
 *       additionally strip any leading directories when the full path misses but a unique
 *       file-name match exists. See {@code resolution} in the returned {@link Reference}.</li>
 *   <li>{@code ../} segments are resolved, and a path escaping the model root is rejected
 *       (returns {@link Reference#missing}) rather than followed: a model file is untrusted
 *       input and must not be able to read outside its own pack.</li>
 *   <li>Percent-encoded characters ({@code %20}) are decoded.</li>
 * </ul>
 *
 * <p>Never returns null from {@link #open}. A miss is reported as
 * {@link Reference#missing(String)}, which carries the reason so a model that renders
 * untextured can say <i>which</i> path it failed to find instead of just looking wrong.
 */
public interface ModelSource extends Closeable {

    /**
     * The outcome of resolving one reference out of a model file.
     *
     * @param data        the stream, or null when unresolved; caller closes it
     * @param resolvedAs  the path that actually matched, or null when unresolved
     * @param exact       true when {@code resolvedAs} equals the requested path
     * @param note        human-readable explanation of a fallback or a miss; never null
     */
    record Reference(InputStream data, String resolvedAs, boolean exact, String note) {

        public boolean isPresent() {
            return data != null;
        }

        /**
         * Reports a miss together with why. {@code detail} should name the failing path so the
         * message is usable straight from a log line.
         */
        public static Reference missing(String detail) {
            return new Reference(null, null, false, detail);
        }
    }

    /**
     * Opens the model's main file.
     *
     * @return the stream, or null when the root itself is absent (the caller then fails the load)
     */
    InputStream openMain() throws IOException;

    /** Path of the main file, for log lines and for the {@code sourceDescription} field of a scene. */
    String mainPath();

    /** Resolves one reference from a model file; never null. */
    Reference open(String relativePath) throws IOException;

    /**
     * Lists every file under this model's root, as {@code /}-separated relative paths, lower-cased
     * implementation-defined order. Used to build the case-insensitive fallback index and to
     * report "the model directory contains these files" when a texture is missing.
     */
    List<String> listFiles() throws IOException;

    /**
     * The pack namespace this model lives in, for texture namespace resolution, or {@code null}
     * when the source is not pack-backed (a plain directory). Parsers never read this; the
     * resource layer does.
     */
    default String namespace() {
        return null;
    }

    @Override
    default void close() throws IOException {
    }
}
