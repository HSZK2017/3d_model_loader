package com.model3d.loader.format.json;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A JSON object, with member lookup that fails loudly and usefully.
 *
 * <p>{@link #get(String)} returns null for an absent member while every <i>typed</i> getter reports
 * the full path of the member it could not read. That split is deliberate: "the member is not
 * there" is a normal branch a glTF reader takes (the spec defaults almost everything), while "the
 * member is there but is a string where a number belongs" is a broken file the caller must hear
 * about rather than paper over with a default.
 *
 * <p>An explicit JSON {@code null} counts as absent for the defaulting getters
 * ({@code getFloat}, {@code getInt}, {@code getBoolean}, {@code getFloatArray}, ...). Exporters
 * that round-trip through a dynamically typed language write {@code null} for "not set", and
 * refusing an entire model over that is worse than applying the spec default. {@link #require} on
 * such a member still returns the {@link JsonNull} and fails with its own path when read as a
 * number, so nothing is silently swallowed where the value is genuinely required.
 *
 * <p>Insertion order is preserved, so diagnostics and any inventory listing follow document order
 * and two runs over the same file print the same thing.
 */
public final class JsonObject extends JsonValue {

    private final Map<String, JsonValue> members;

    JsonObject(JsonValue parent, String memberPath, int elementIndex, Map<String, JsonValue> members) {
        super(parent, memberPath, elementIndex);
        this.members = members;
    }

    @Override
    public JsonType type() {
        return JsonType.OBJECT;
    }

    @Override
    public JsonObject asObject() {
        return this;
    }

    public int size() {
        return members.size();
    }

    public boolean isEmpty() {
        return members.isEmpty();
    }

    public boolean has(String key) {
        return members.containsKey(key);
    }

    /** Member names in document order. */
    public Set<String> keySet() {
        return Collections.unmodifiableSet(members.keySet());
    }

    /** The member, or null when absent. A present {@code null} member is {@link JsonNull#INSTANCE}. */
    public JsonValue get(String key) {
        return members.get(key);
    }

    /** The member, failing when absent. */
    public JsonValue require(String key) throws JsonParseException {
        JsonValue value = members.get(key);
        if (value == null) {
            throw new JsonParseException(path() + ": missing required member '" + key + "'");
        }
        return value;
    }

    public JsonObject getObject(String key) throws JsonParseException {
        JsonValue value = members.get(key);
        return value == null ? null : value.asObject();
    }

    public JsonObject requireObject(String key) throws JsonParseException {
        return require(key).asObject();
    }

    public JsonArray getArray(String key) throws JsonParseException {
        JsonValue value = members.get(key);
        return value == null ? null : value.asArray();
    }

    public JsonArray requireArray(String key) throws JsonParseException {
        return require(key).asArray();
    }

    /** String member, or null when absent. */
    public String getString(String key) throws JsonParseException {
        JsonValue value = members.get(key);
        return value == null || value.isNull() ? null : value.asString();
    }

    public String requireString(String key) throws JsonParseException {
        return require(key).asString();
    }

    /** Number member as float, or {@code fallback} when absent. */
    public float getFloat(String key, float fallback) throws JsonParseException {
        JsonValue value = members.get(key);
        return value == null || value.isNull() ? fallback : value.asFloat();
    }

    /** Integer member, or {@code fallback} when absent. */
    public int getInt(String key, int fallback) throws JsonParseException {
        JsonValue value = members.get(key);
        return value == null || value.isNull() ? fallback : value.asInt();
    }

    /** Integer member that the schema requires. */
    public int requireInt(String key) throws JsonParseException {
        return require(key).asInt();
    }

    /** Boolean member, or {@code fallback} when absent. */
    public boolean getBoolean(String key, boolean fallback) throws JsonParseException {
        JsonValue value = members.get(key);
        return value == null || value.isNull() ? fallback : value.asBoolean();
    }

    /**
     * Number array member as a flat {@code float[]}, or null when absent. Used for
     * {@code translation}/{@code rotation}/{@code scale}/{@code matrix} and the colour factors.
     */
    public float[] getFloatArray(String key) throws JsonParseException {
        JsonValue value = members.get(key);
        return value == null || value.isNull() ? null : value.asArray().toFloatArray();
    }

    /** Integer array member as {@code int[]}, or null when absent. */
    public int[] getIntArray(String key) throws JsonParseException {
        JsonValue value = members.get(key);
        return value == null || value.isNull() ? null : value.asArray().toIntArray();
    }

    @Override
    public String toString() {
        return "JsonObject" + path() + "(" + members.size() + " members)";
    }
}
