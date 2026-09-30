package com.model3d.loader.format.json;

/**
 * JSON {@code null}.
 *
 * <p>One instance per occurrence rather than a shared singleton, because {@code null} still has to
 * be able to say <i>where</i> it was: a glTF field that is present but null where a number belongs
 * must report {@code $.materials[3].alphaCutoff: expected NUMBER but found NULL}, and a singleton
 * would answer {@code $}. Only the document root's null - which has no position - is shared.
 */
public final class JsonNull extends JsonValue {

    /** The null at the document root, which has no enclosing value to point at. */
    public static final JsonNull INSTANCE = new JsonNull(null, null, -1);

    JsonNull(JsonValue parent, String memberPath, int elementIndex) {
        super(parent, memberPath, elementIndex);
    }

    @Override
    public JsonType type() {
        return JsonType.NULL;
    }

    @Override
    public String toString() {
        return "null";
    }
}
