package com.model3d.loader.format.json;

/**
 * A JSON string, number or boolean leaf.
 *
 * <p>A number is kept as a {@code double} plus, when the token was written without a fraction or
 * exponent, an exact {@code long}. Both are needed: glTF puts small (and occasionally large)
 * integers in {@code byteOffset}/{@code count}/{@code byteStride} where exactness matters, and
 * {@code double} alone would make {@code 9007199254740993} indistinguishable from its neighbour.
 * The raw token text is deliberately not retained - for a document with millions of numbers that is
 * one {@code String} per number for a message that is only ever read when parsing already failed.
 */
public final class JsonPrimitive extends JsonValue {

    private final JsonType type;
    private final String text;
    private final double number;
    private final long integer;
    private final boolean integralToken;
    private final boolean fitsLong;
    private final boolean bool;

    JsonPrimitive(JsonValue parent, String memberPath, int elementIndex, String text) {
        super(parent, memberPath, elementIndex);
        this.type = JsonType.STRING;
        this.text = text;
        this.number = 0;
        this.integer = 0;
        this.integralToken = false;
        this.fitsLong = false;
        this.bool = false;
    }

    JsonPrimitive(JsonValue parent, String memberPath, int elementIndex, double number,
                  boolean integralToken, boolean fitsLong, long integer) {
        super(parent, memberPath, elementIndex);
        this.type = JsonType.NUMBER;
        this.text = null;
        this.number = number;
        this.integralToken = integralToken;
        this.fitsLong = fitsLong;
        this.integer = integer;
        this.bool = false;
    }

    JsonPrimitive(JsonValue parent, String memberPath, int elementIndex, boolean bool) {
        super(parent, memberPath, elementIndex);
        this.type = JsonType.BOOLEAN;
        this.text = null;
        this.number = 0;
        this.integer = 0;
        this.integralToken = false;
        this.fitsLong = false;
        this.bool = bool;
    }

    @Override
    public JsonType type() {
        return type;
    }

    @Override
    public String asString() throws JsonParseException {
        if (type != JsonType.STRING) {
            throw wrongType(JsonType.STRING);
        }
        return text;
    }

    @Override
    public double asDouble() throws JsonParseException {
        if (type != JsonType.NUMBER) {
            throw wrongType(JsonType.NUMBER);
        }
        return number;
    }

    @Override
    public float asFloat() throws JsonParseException {
        double value = asDouble();
        float narrowed = (float) value;
        if (!Float.isFinite(narrowed)) {
            // Only reachable for tokens such as 1e39; the tokenizer already rejected Infinity.
            throw new JsonParseException(path() + ": number " + value
                    + " is outside the range of a float");
        }
        return narrowed;
    }

    @Override
    public long asLong() throws JsonParseException {
        if (type != JsonType.NUMBER) {
            throw wrongType(JsonType.NUMBER);
        }
        if (integralToken) {
            if (!fitsLong) {
                throw new JsonParseException(path() + ": integer " + number
                        + " does not fit in a 64-bit signed integer");
            }
            return integer;
        }
        // A fraction/exponent token is still accepted when its value happens to be integral:
        // exporters that round-trip through JSON in a dynamically typed language write 12.0 for a
        // field the schema declares as an integer, and rejecting that is pedantry, not safety.
        double value = number;
        if (value != Math.rint(value) || !Double.isFinite(value)) {
            throw new JsonParseException(path() + ": expected an integer but found " + value);
        }
        if (value < -9.223372036854776E18 || value > 9.223372036854776E18) {
            throw new JsonParseException(path() + ": integer " + value
                    + " does not fit in a 64-bit signed integer");
        }
        return (long) value;
    }

    @Override
    public int asInt() throws JsonParseException {
        long value = asLong();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new JsonParseException(path() + ": integer " + value + " does not fit in an int");
        }
        return (int) value;
    }

    @Override
    public boolean asBoolean() throws JsonParseException {
        if (type != JsonType.BOOLEAN) {
            throw wrongType(JsonType.BOOLEAN);
        }
        return bool;
    }

    @Override
    public String toString() {
        switch (type) {
            case STRING:
                return "\"" + text + "\"";
            case NUMBER:
                return integralToken && fitsLong ? Long.toString(integer) : Double.toString(number);
            default:
                return Boolean.toString(bool);
        }
    }
}
