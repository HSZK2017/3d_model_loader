package com.model3d.loader.format;

/**
 * Raised when a model file cannot be turned into a scene.
 *
 * <p>Checked, and deliberately so: "this model is broken" is a normal, expected outcome of
 * loading untrusted third-party files, and callers are forced to decide what to show instead.
 * The message must name the offending element - the accessor index, the node name, the byte
 * offset - because the alternative is a user staring at an invisible entity.
 */
public class ModelParseException extends Exception {

    private static final long serialVersionUID = 1L;

    public ModelParseException(String message) {
        super(message);
    }

    public ModelParseException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Convenience: {@code format} failed at {@code where}, for {@code reason}. */
    public static ModelParseException at(String where, String reason) {
        return new ModelParseException(where + ": " + reason);
    }

    public static ModelParseException at(String where, String reason, Throwable cause) {
        return new ModelParseException(where + ": " + reason, cause);
    }
}
