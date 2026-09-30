package com.model3d.loader.format;

import com.model3d.loader.Model3D;
import com.model3d.loader.scene.ModelScene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The set of parsers the loader will try, in precedence order.
 *
 * <p>Single source of truth for "which formats does this build support", consulted by the
 * loader, by the command's suggestions and by the offline inspector - so a format that is
 * registered is a format the command offers, with no second list to drift out of sync.
 *
 * <p>Registration happens once, at class-init of this class, and is intentionally not publicly
 * mutable: a mod that wants a new format adds it here, not by mutating global state at an
 * arbitrary moment in the lifecycle.
 */
public final class ModelFormatRegistry {

    private static final List<ModelParser> PARSERS = new ArrayList<>();

    static {
        // GLB precedes GLTF: a .glb is self-contained, so it is the format the loader prefers
        // when a model directory contains more than one candidate file.
        register(new com.model3d.loader.format.gltf.GlbParser());
        register(new com.model3d.loader.format.gltf.GltfParser());
        register(new com.model3d.loader.format.obj.ObjParser());
    }

    private ModelFormatRegistry() {
    }

    private static void register(ModelParser parser) {
        PARSERS.add(parser);
    }

    /** Registered parsers in precedence order; unmodifiable. */
    public static List<ModelParser> parsers() {
        return Collections.unmodifiableList(PARSERS);
    }

    /** The parser for {@code format}, or null when no parser is registered for it. */
    public static ModelParser parserFor(ModelFormat format) {
        for (ModelParser parser : PARSERS) {
            if (parser.format() == format) {
                return parser;
            }
        }
        return null;
    }

    /** Extensions this build understands, lower-case, in precedence order. */
    public static List<String> supportedExtensions() {
        List<String> extensions = new ArrayList<>(PARSERS.size());
        for (ModelParser parser : PARSERS) {
            extensions.add(parser.format().extension());
        }
        return extensions;
    }

    /**
     * Parses using whichever registered parser claims the source's main file.
     *
     * @throws ModelParseException when the extension is unknown, or the owning parser fails
     */
    public static ModelScene parse(ModelSource source, String requestedName) throws ModelParseException {
        ModelFormat format = ModelFormat.byPath(source.mainPath());
        if (format == null) {
            throw ModelParseException.at(source.mainPath(),
                    "unrecognised extension; supported: " + supportedExtensions());
        }
        ModelParser parser = parserFor(format);
        if (parser == null) {
            throw ModelParseException.at(source.mainPath(),
                    "no parser registered for format " + format);
        }
        long startNanos = System.nanoTime();
        ModelScene scene = parser.parse(source, requestedName);
        long millis = (System.nanoTime() - startNanos) / 1_000_000L;
        Model3D.LOGGER.debug("Parsed {} as {} in {} ms -> {}", source.mainPath(), format, millis, scene);
        return scene;
    }
}
