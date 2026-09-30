package com.model3d.loader.format.json;

import com.model3d.loader.format.ModelParseException;

/**
 * A malformed JSON document, or a value read as the wrong type.
 *
 * <p>Extends {@link ModelParseException} deliberately. A syntax error inside a {@code .gltf} is a
 * model-parse failure like any other, so it travels the same checked channel the loader already
 * handles; wrapping it at the parser boundary would add a layer where the offending JSON path can
 * be lost. The dependency is on this mod's own (plain Java) exception type, never on Minecraft, so
 * the reader stays usable from plain JUnit.
 */
public class JsonParseException extends ModelParseException {

    private static final long serialVersionUID = 1L;

    public JsonParseException(String message) {
        super(message);
    }

    public JsonParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
