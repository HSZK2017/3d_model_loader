package com.model3d.loader.format.json;

/**
 * The six JSON value kinds, as {@link JsonValue} reports them.
 *
 * <p>{@code NULL} is a kind of its own rather than "absent": glTF uses an explicit {@code null}
 * where a field is optional, and a caller that cannot tell it from a missing member ends up
 * applying a default over an explicit value.
 */
public enum JsonType {
    OBJECT,
    ARRAY,
    STRING,
    NUMBER,
    BOOLEAN,
    NULL
}
