package com.model3d.loader.format;

import java.util.Locale;

/**
 * The interchange formats this mod can read, and the detection rules for them.
 *
 * <p>Registration order in {@link ModelFormatRegistry} decides precedence when more than one
 * format claims a file; the order here is the order they are tried.
 */
public enum ModelFormat {

    /** Binary glTF 2.0: a 12-byte header, a JSON chunk and a BIN chunk in one file. */
    GLB("glb", true),

    /** Text glTF 2.0: JSON plus external {@code .bin} and image references. */
    GLTF("gltf", true),

    /** Wavefront OBJ, with materials in a sibling {@code .mtl}. Carries no animation data. */
    OBJ("obj", false);

    private final String extension;
    private final boolean animated;

    ModelFormat(String extension, boolean animated) {
        this.extension = extension;
        this.animated = animated;
    }

    public String extension() {
        return extension;
    }

    /** True when the format itself can carry animation (OBJ cannot, at any version). */
    public boolean isAnimated() {
        return animated;
    }

    public String fileName() {
        return "model." + extension;
    }

    /**
     * Detects the format of a file path by extension, or null when unrecognised.
     * Case-insensitive: exporters emit {@code .GLB} often enough to matter.
     */
    public static ModelFormat byPath(String path) {
        if (path == null) {
            return null;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String extension = lower.substring(dot + 1);
        for (ModelFormat format : values()) {
            if (format.extension.equals(extension)) {
                return format;
            }
        }
        return null;
    }
}
