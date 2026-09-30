package com.model3d.loader.format.json;

import java.util.Collections;
import java.util.List;

/**
 * A JSON array.
 *
 * <p>Element access is index-checked and type-checked against the element's own path, so a
 * malformed entry inside a 22-primitive glTF mesh reports
 * {@code $.meshes[3].primitives[0].attributes: ...} rather than "not an object".
 */
public final class JsonArray extends JsonValue {

    private final List<JsonValue> elements;

    JsonArray(JsonValue parent, String memberPath, int elementIndex, List<JsonValue> elements) {
        super(parent, memberPath, elementIndex);
        this.elements = elements;
    }

    @Override
    public JsonType type() {
        return JsonType.ARRAY;
    }

    @Override
    public JsonArray asArray() {
        return this;
    }

    public int size() {
        return elements.size();
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    public JsonValue get(int index) throws JsonParseException {
        if (index < 0 || index >= elements.size()) {
            throw new JsonParseException(path() + ": element index " + index
                    + " out of range (size " + elements.size() + ")");
        }
        return elements.get(index);
    }

    public JsonObject requireObject(int index) throws JsonParseException {
        return get(index).asObject();
    }

    public JsonArray requireArray(int index) throws JsonParseException {
        return get(index).asArray();
    }

    public String requireString(int index) throws JsonParseException {
        return get(index).asString();
    }

    /** Elements in document order; unmodifiable. */
    public List<JsonValue> elements() {
        return Collections.unmodifiableList(elements);
    }

    /** Flattens an array of numbers, naming the first offending element. */
    public float[] toFloatArray() throws JsonParseException {
        float[] out = new float[elements.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = get(i).asFloat();
        }
        return out;
    }

    /** Flattens an array of integers, naming the first offending element. */
    public int[] toIntArray() throws JsonParseException {
        int[] out = new int[elements.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = get(i).asInt();
        }
        return out;
    }

    public String[] toStringArray() throws JsonParseException {
        String[] out = new String[elements.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = get(i).asString();
        }
        return out;
    }

    @Override
    public String toString() {
        return "JsonArray" + path() + "(" + elements.size() + " elements)";
    }
}
