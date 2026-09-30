package com.model3d.loader.format.json;

/**
 * One node of a parsed JSON document.
 *
 * <p>Every value knows the document position it came from, so a wrong-type or missing-member error
 * can name the exact element - {@code $.meshes[3].primitives[0].attributes.POSITION} - instead of
 * "expected a number". The path is <b>built on demand</b> by walking parent links: a 40 MB document
 * must not pay one {@code String} per value just so a failure that never happens can be pretty.
 *
 * <p>Values are immutable once parsed, and a parsed tree is safe to share between threads. It is
 * also <b>detached</b> from the {@link java.io.Reader} it came from: nothing in the tree holds the
 * stream, so a caller cannot accidentally keep a 43 MB file mapped by holding one string.
 *
 * <p>The {@code asXxx} family is strict: asking an array for {@code asString} is a malformed file,
 * not something to coerce. Where real exporter output needs latitude - a float where an integer
 * field is declared - the leniency lives in the one place it is justified and is commented there.
 */
public abstract class JsonValue {

    /** Enclosing object, or null at the document root. */
    private final JsonValue parent;

    /** Path suffix inside {@link #parent} (".name" or "['odd name']"), or null for an element. */
    private final String memberPath;

    /** Element index inside {@link #parent}, or -1 when this is a member or the root. */
    private final int elementIndex;

    JsonValue(JsonValue parent, String memberPath, int elementIndex) {
        this.parent = parent;
        this.memberPath = memberPath;
        this.elementIndex = elementIndex;
    }

    /** Root value constructor. */
    JsonValue() {
        this(null, null, -1);
    }

    public abstract JsonType type();

    /** JSON path of this value, e.g. {@code $.nodes[3].children} or {@code $.asset.version}. */
    public final String path() {
        if (parent == null) {
            return "$";
        }
        if (memberPath != null) {
            return parent.path() + memberPath;
        }
        return parent.path() + "[" + elementIndex + "]";
    }

    public final boolean isNull() {
        return type() == JsonType.NULL;
    }

    public final boolean isObject() {
        return type() == JsonType.OBJECT;
    }

    public final boolean isArray() {
        return type() == JsonType.ARRAY;
    }

    public final boolean isString() {
        return type() == JsonType.STRING;
    }

    public final boolean isNumber() {
        return type() == JsonType.NUMBER;
    }

    public final boolean isBoolean() {
        return type() == JsonType.BOOLEAN;
    }

    public JsonObject asObject() throws JsonParseException {
        throw wrongType(JsonType.OBJECT);
    }

    public JsonArray asArray() throws JsonParseException {
        throw wrongType(JsonType.ARRAY);
    }

    public String asString() throws JsonParseException {
        throw wrongType(JsonType.STRING);
    }

    public double asDouble() throws JsonParseException {
        throw wrongType(JsonType.NUMBER);
    }

    /** {@link #asDouble()} narrowed to float, failing rather than silently becoming Infinity. */
    public float asFloat() throws JsonParseException {
        throw wrongType(JsonType.NUMBER);
    }

    /**
     * Integral value of a number, failing rather than truncating a fraction: a silent {@code 2.7 -> 2}
     * in a {@code byteOffset} produces a subtly wrong mesh, which is far harder to diagnose than a
     * rejected file.
     */
    public int asInt() throws JsonParseException {
        throw wrongType(JsonType.NUMBER);
    }

    public long asLong() throws JsonParseException {
        throw wrongType(JsonType.NUMBER);
    }

    public boolean asBoolean() throws JsonParseException {
        throw wrongType(JsonType.BOOLEAN);
    }

    protected final JsonParseException wrongType(JsonType expected) {
        return new JsonParseException(path() + ": expected " + expected + " but found " + type());
    }

    /**
     * Path suffix for an object member: {@code .key} for a bare identifier, {@code ['odd key']}
     * otherwise, so a path stays unambiguous when a file uses a name with dots or spaces in it.
     */
    static String memberPath(String key) {
        boolean plain = !key.isEmpty();
        for (int i = 0; i < key.length() && plain; i++) {
            char c = key.charAt(i);
            plain = c == '_' || c == '-' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (i > 0 && c >= '0' && c <= '9');
        }
        return plain ? "." + key : "['" + key + "']";
    }
}
