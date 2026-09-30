package com.model3d.loader.format.json;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The parser half of the JSON reader: a recursive-descent parser over {@link JsonTokenizer}.
 *
 * <p>Streaming in the sense that matters here: the document is pulled through an 8 KB character
 * buffer and never materialised as one big {@code String}, so a 43 MB {@code .glb} whose JSON chunk
 * is a few kilobytes costs a few kilobytes, and a text {@code .gltf} with a 30 MB base64 buffer uri
 * costs the string it must build anyway.
 *
 * <p>Two deliberate choices that show up in real exporter output:
 * <ul>
 *   <li><b>Depth is bounded</b> ({@link #MAX_DEPTH}). The input is untrusted, and a recursive
 *       parser's failure mode on 100 000 nested brackets is a {@code StackOverflowError} - an
 *       {@code Error} that escapes every {@code catch (ModelParseException)} and takes the server
 *       down with it. A depth of 1000 is ~100x deeper than any real glTF and still nowhere near
 *       the stack limit.</li>
 *   <li><b>Duplicate member names are last-wins</b>, matching every mainstream JSON reader. glTF
 *       requires uniqueness, but silently rejecting a file that every other tool accepts would
 *       make this loader the odd one out.</li>
 * </ul>
 */
public final class JsonParser {

    /**
     * Maximum object/array nesting. Real glTF nests six levels; the bound exists to turn a
     * {@code StackOverflowError} on hostile input into an ordinary parse failure.
     */
    public static final int MAX_DEPTH = 1000;

    private final JsonTokenizer tokenizer;
    private int depth;

    private JsonParser(Reader in, String source) {
        this.tokenizer = new JsonTokenizer(in, source);
    }

    /** Parses a whole document from characters. {@code source} is used only in error messages. */
    public static JsonValue parse(Reader in, String source) throws JsonParseException {
        return new JsonParser(in, source).parseDocument();
    }

    public static JsonValue parse(String json, String source) throws JsonParseException {
        return parse(new StringReader(json), source);
    }

    /** Parses UTF-8 bytes, with a leading byte-order mark tolerated. */
    public static JsonValue parse(byte[] utf8, String source) throws JsonParseException {
        return parse(new ByteArrayInputStream(utf8), source);
    }

    public static JsonValue parse(InputStream in, String source) throws JsonParseException {
        return parse(new InputStreamReader(in, StandardCharsets.UTF_8), source);
    }

    /** Parses a document whose root must be an object, failing with a clear message otherwise. */
    public static JsonObject parseObject(Reader in, String source) throws JsonParseException {
        return requireObject(parse(in, source), source);
    }

    public static JsonObject parseObject(String json, String source) throws JsonParseException {
        return requireObject(parse(json, source), source);
    }

    public static JsonObject parseObject(byte[] utf8, String source) throws JsonParseException {
        return requireObject(parse(utf8, source), source);
    }

    public static JsonObject parseObject(InputStream in, String source) throws JsonParseException {
        return requireObject(parse(in, source), source);
    }

    private static JsonObject requireObject(JsonValue value, String source) throws JsonParseException {
        if (!value.isObject()) {
            throw new JsonParseException(source + ": the document root must be a JSON object but is a "
                    + value.type());
        }
        return value.asObject();
    }

    private JsonValue parseDocument() throws JsonParseException {
        tokenizer.next();
        JsonValue root = parseValue(null, null, -1);
        // Anything but padding after the root value means the document is two documents, or a
        // truncated one whose tail happens to parse: both are malformed.
        tokenizer.next();
        if (tokenizer.current() != JsonTokenizer.Token.EOF) {
            throw tokenizer.tokenError("trailing content after the top-level value: found "
                    + tokenizer.describeToken());
        }
        return root;
    }

    private JsonValue parseValue(JsonValue parent, String memberPath, int elementIndex)
            throws JsonParseException {
        switch (tokenizer.current()) {
            case BEGIN_OBJECT:
                return parseObjectValue(parent, memberPath, elementIndex);
            case BEGIN_ARRAY:
                return parseArrayValue(parent, memberPath, elementIndex);
            case STRING:
                return new JsonPrimitive(parent, memberPath, elementIndex, tokenizer.string());
            case NUMBER:
                return new JsonPrimitive(parent, memberPath, elementIndex, tokenizer.number(),
                        tokenizer.numberIsIntegral(), tokenizer.numberFitsLong(),
                        tokenizer.numberLong());
            case TRUE:
                return new JsonPrimitive(parent, memberPath, elementIndex, true);
            case FALSE:
                return new JsonPrimitive(parent, memberPath, elementIndex, false);
            case NULL:
                return new JsonNull(parent, memberPath, elementIndex);
            default:
                throw tokenizer.tokenError("expected a JSON value but found "
                        + tokenizer.describeToken());
        }
    }

    private JsonObject parseObjectValue(JsonValue parent, String memberPath, int elementIndex)
            throws JsonParseException {
        enter();
        // The member map is created first so that each member value can be built with a parent link
        // to it, which is what makes paths in error messages possible.
        LinkedHashMap<String, JsonValue> members = new LinkedHashMap<>();
        JsonObject object = new JsonObject(parent, memberPath, elementIndex, members);
        tokenizer.next();
        if (tokenizer.current() == JsonTokenizer.Token.END_OBJECT) {
            leave();
            return object;
        }
        while (true) {
            // Invariant: the parser arrives here with the current token being a member name. A
            // dangling comma therefore fails here rather than being quietly accepted.
            if (tokenizer.current() != JsonTokenizer.Token.STRING) {
                throw tokenizer.tokenError("expected a member name or '}' but found "
                        + tokenizer.describeToken());
            }
            String key = tokenizer.string();
            tokenizer.next();
            if (tokenizer.current() != JsonTokenizer.Token.COLON) {
                throw tokenizer.tokenError("expected ':' after member name \"" + key + "\" but found "
                        + tokenizer.describeToken());
            }
            tokenizer.next();
            members.put(key, parseValue(object, JsonValue.memberPath(key), -1));
            tokenizer.next();
            if (tokenizer.current() == JsonTokenizer.Token.COMMA) {
                tokenizer.next();
                continue;
            }
            if (tokenizer.current() == JsonTokenizer.Token.END_OBJECT) {
                break;
            }
            throw tokenizer.tokenError("expected ',' or '}' but found " + tokenizer.describeToken());
        }
        leave();
        return object;
    }

    private JsonArray parseArrayValue(JsonValue parent, String memberPath, int elementIndex)
            throws JsonParseException {
        enter();
        List<JsonValue> elements = new ArrayList<>();
        JsonArray array = new JsonArray(parent, memberPath, elementIndex, elements);
        tokenizer.next();
        if (tokenizer.current() == JsonTokenizer.Token.END_ARRAY) {
            leave();
            return array;
        }
        int index = 0;
        while (true) {
            elements.add(parseValue(array, null, index++));
            tokenizer.next();
            if (tokenizer.current() == JsonTokenizer.Token.COMMA) {
                tokenizer.next();
                continue;
            }
            if (tokenizer.current() == JsonTokenizer.Token.END_ARRAY) {
                break;
            }
            throw tokenizer.tokenError("expected ',' or ']' but found " + tokenizer.describeToken());
        }
        leave();
        return array;
    }

    private void enter() throws JsonParseException {
        if (++depth > MAX_DEPTH) {
            throw tokenizer.error("nesting is deeper than the supported maximum of " + MAX_DEPTH);
        }
    }

    private void leave() {
        depth--;
    }
}
