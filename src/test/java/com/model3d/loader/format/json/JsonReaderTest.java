package com.model3d.loader.format.json;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the dependency-free JSON reader, driven by the quirks that actually occur in
 * exported {@code .gltf} files rather than by a tidy fixture.
 */
class JsonReaderTest {

    private static final String SOURCE = "test.gltf";

    @Test
    @DisplayName("reads a nested document and keeps the JSON path of every value")
    void readsNestedDocument() throws Exception {
        JsonObject root = JsonParser.parseObject("""
                {
                  "asset": { "version": "2.0", "generator": "Blender" },
                  "scene": 0,
                  "scenes": [ { "nodes": [0, 1] } ],
                  "nodes": [
                    { "name": "root", "children": [1] },
                    { "name": "leaf", "mesh": 0, "translation": [0, 1.5, -2] }
                  ],
                  "counts": { "empty": [], "flag": true, "nothing": null }
                }
                """, SOURCE);

        assertEquals(5, root.size());
        assertEquals("2.0", root.requireObject("asset").requireString("version"));
        assertEquals(0, root.requireInt("scene"));
        assertEquals(2, root.requireArray("scenes").requireObject(0).getIntArray("nodes").length);
        JsonArray nodes = root.requireArray("nodes");
        assertEquals(2, nodes.size());
        JsonObject leaf = nodes.requireObject(1);
        assertEquals("leaf", leaf.getString("name"));
        assertEquals("$.nodes[1].translation", leaf.require("translation").path());
        float[] translation = leaf.getFloatArray("translation");
        assertEquals(3, translation.length);
        assertEquals(1.5f, translation[1]);

        JsonObject counts = root.requireObject("counts");
        assertTrue(counts.requireArray("empty").isEmpty());
        assertTrue(counts.getBoolean("flag", false));
        assertTrue(counts.require("nothing").isNull());
        assertFalse(counts.require("nothing").isBoolean());
        assertNull(root.get("nope"), "an absent member is null, not an error");
    }

    @Test
    @DisplayName("tolerates the byte-level quirks real exporters write")
    void toleratesExporterQuirks() throws Exception {
        // UTF-8 BOM, then escapes, a unicode escape including a surrogate pair, an escaped solidus,
        // exponent notation, and NUL/space padding of the kind a GLB JSON chunk carries.
        String text = "\uFEFF{\"bom\":\"\\u00e4\\u00df\",\"emoji\":\"\\ud83d\\ude00\",\"slash\":\"a\\/b\","
                + "\"tiny\":1.5e-7,\"big\":1E+2,\"zero\":-0,\"nul\":\"a\\u0000b\"}"
                + "   \u0000\u0000";
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseObject(utf8, SOURCE);

        assertEquals("\u00e4\u00df", root.requireString("bom"));
        assertEquals("\ud83d\ude00", root.requireString("emoji"));
        assertEquals("a/b", root.requireString("slash"));
        assertEquals(1.5e-7f, root.getFloat("tiny", 0f), 1e-13f);
        assertEquals(100, root.requireInt("big"));
        assertEquals(0, root.requireInt("zero"));
        assertEquals("a\u0000b", root.requireString("nul"));
    }

    @Test
    @DisplayName("errors name the JSON path of the offending member")
    void errorsCarryTheJsonPath() throws Exception {
        JsonObject root = JsonParser.parseObject("""
                { "meshes": [ { "primitives": [ { "mode": "TRIANGLES" } ] } ] }
                """, SOURCE);

        JsonParseException wrongType = assertThrows(JsonParseException.class, () ->
                root.requireArray("meshes").requireObject(0).requireArray("primitives")
                        .requireObject(0).requireInt("mode"));
        assertTrue(wrongType.getMessage().contains("$.meshes[0].primitives[0].mode"),
                "message should carry the full path, was: " + wrongType.getMessage());
        assertTrue(wrongType.getMessage().contains("expected NUMBER but found STRING"),
                wrongType.getMessage());

        JsonParseException missing = assertThrows(JsonParseException.class, () ->
                root.requireArray("meshes").requireObject(0).require("notThere"));
        assertTrue(missing.getMessage().contains("$.meshes[0]"), missing.getMessage());
        assertTrue(missing.getMessage().contains("missing required member 'notThere'"),
                missing.getMessage());

        JsonParseException badIndex = assertThrows(JsonParseException.class, () ->
                root.requireArray("meshes").get(7));
        assertTrue(badIndex.getMessage().contains("$.meshes"), badIndex.getMessage());
        assertTrue(badIndex.getMessage().contains("out of range"), badIndex.getMessage());
    }

    @Test
    @DisplayName("an explicit null reads as absent for defaulting getters and as NULL for required ones")
    void nullIsAbsentForDefaultsButTypedForRequiredMembers() throws Exception {
        JsonObject root = JsonParser.parseObject("{ \"materials\": [ { \"alphaCutoff\": null } ] }",
                SOURCE);
        JsonObject material = root.requireArray("materials").requireObject(0);
        // Interop choice: exporters that round-trip through a dynamically typed language write null
        // for "not set"; refusing the whole model over that would be worse than applying the default.
        assertEquals(0.5f, material.getFloat("alphaCutoff", 0.5f));
        assertTrue(material.has("alphaCutoff"), "the member is still present in the object");

        JsonParseException error = assertThrows(JsonParseException.class, () ->
                material.require("alphaCutoff").asFloat());
        assertEquals("$.materials[0].alphaCutoff: expected NUMBER but found NULL",
                error.getMessage());
    }

    @Test
    @DisplayName("integers stay exact, fractions are refused rather than truncated")
    void integerAccessorsAreExact() throws Exception {
        JsonObject root = JsonParser.parseObject(
                "{\"exact\":9007199254740993,\"fraction\":2.7,\"integralFloat\":12.0,"
                        + "\"huge\":123456789012345678901234567890,\"small\":-3}", SOURCE);

        assertEquals(9007199254740993L, root.require("exact").asLong());
        assertEquals(-3, root.requireInt("small"));
        assertEquals(12, root.requireInt("integralFloat"),
                "12.0 is integral, so an integer field accepts it");
        JsonParseException fraction = assertThrows(JsonParseException.class, () ->
                root.requireInt("fraction"));
        assertTrue(fraction.getMessage().contains("expected an integer but found 2.7"),
                fraction.getMessage());
        JsonParseException tooBig = assertThrows(JsonParseException.class, () ->
                root.requireInt("huge"));
        assertTrue(tooBig.getMessage().contains("does not fit"), tooBig.getMessage());
    }

    @Test
    @DisplayName("numbers that double cannot hold are rejected at parse time")
    void rejectsUnrepresentableNumbers() {
        JsonParseException overflow = assertThrows(JsonParseException.class, () ->
                JsonParser.parse("{\"offset\":1e999}", SOURCE));
        assertTrue(overflow.getMessage().contains("not representable as a finite double"),
                overflow.getMessage());
        assertTrue(overflow.getMessage().startsWith(SOURCE + ":"), overflow.getMessage());
    }

    @Test
    @DisplayName("malformed documents fail with a position, never with a wrong tree")
    void rejectsMalformedDocuments() {
        assertMessage("{ \"a\": 1, }", "expected a member name or '}'");
        assertMessage("{ \"a\" 1 }", "expected ':' after member name");
        assertMessage("{ \"a\": [1, ] }", "expected a JSON value but found ']'");
        assertMessage("{ \"a\": \"unterminated }", "unterminated string literal");
        assertMessage("{ \"a\": 01 }", "leading zero");
        assertMessage("{ \"a\": 1. }", "expected a digit after '.'");
        assertMessage("{ \"a\": 1e }", "expected a digit in the exponent");
        assertMessage("{ \"a\": tru }", "expected 'true'");
        assertMessage("{ \"a\": nulle }", "expected 'null'");
        assertMessage("{ \"a\": \"\\q\" }", "invalid escape sequence");
        assertMessage("{ \"a\": \"\\u12g4\" }", "expected four hexadecimal digits");
        assertMessage("{ \"a\": 1 } { \"b\": 2 }", "trailing content after the top-level value");
        assertMessage("{ \"a\": 1 ", "expected ',' or '}'");
        assertMessage("[1, 2", "expected ',' or ']'");
        assertMessage("{ \"a\": NaN }", "unexpected character 'N'");
        assertMessage("{ \"a\": +1 }", "unexpected character '+'");
        assertMessage("", "expected a JSON value but found end of input");
    }

    private static void assertMessage(String document, String expectedFragment) {
        JsonParseException error = assertThrows(JsonParseException.class,
                () -> JsonParser.parse(document, SOURCE), "should reject: " + document);
        assertTrue(error.getMessage().contains(expectedFragment),
                "for document " + document + " expected message to contain '" + expectedFragment
                        + "' but was: " + error.getMessage());
        assertTrue(error.getMessage().startsWith(SOURCE + ":"),
                "message should carry the source name and position: " + error.getMessage());
    }

    @Test
    @DisplayName("nesting is bounded, so hostile input fails instead of overflowing the stack")
    void depthIsBounded() {
        int depth = JsonParser.MAX_DEPTH + 20;
        StringBuilder document = new StringBuilder(depth * 2);
        document.append("[".repeat(depth));
        document.append("]".repeat(depth));

        // The point is that this is an ordinary checked failure; a StackOverflowError here would
        // escape every ModelParseException handler in the loader.
        JsonParseException error = assertThrows(JsonParseException.class,
                () -> JsonParser.parse(document.toString(), SOURCE));
        assertTrue(error.getMessage().contains("nesting is deeper than the supported maximum"),
                error.getMessage());
    }

    @Test
    @DisplayName("duplicate member names are last-wins, like every mainstream reader")
    void duplicateMembersAreLastWins() throws Exception {
        JsonObject root = JsonParser.parseObject("{\"a\":1,\"a\":2}", SOURCE);
        assertEquals(1, root.size());
        assertEquals(2, root.requireInt("a"));
    }

    @Test
    @DisplayName("the document root must be an object")
    void rootMustBeObject() {
        JsonParseException error = assertThrows(JsonParseException.class, () ->
                JsonParser.parseObject("[1,2,3]", SOURCE));
        assertTrue(error.getMessage().contains("root must be a JSON object"), error.getMessage());
    }

    @Test
    @DisplayName("values and wrong-type diagnostics expose their kind")
    void typesAndToString() throws Exception {
        JsonValue root = JsonParser.parse("{\"a\":{\"b\":[true,false,null]}}", SOURCE);
        assertEquals(JsonType.OBJECT, root.type());
        JsonValue array = root.asObject().requireObject("a").require("b");
        assertEquals(JsonType.ARRAY, array.type());
        assertEquals("$.a.b", array.path());
        assertEquals(3, array.asArray().size());
        assertInstanceOf(JsonNull.class, array.asArray().get(2));
        assertNotNull(array.toString());
        assertFalse(array.asArray().get(0).isNull());
    }

    @Test
    @DisplayName("large documents are read without quadratic copying")
    void readsLargeDocuments() throws Exception {
        // 200k numbers: the tokenizer's reusable buffers are what keep this from being a GC test,
        // and this is the size range a real animation-heavy .gltf reaches.
        int count = 200_000;
        List<String> parts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            parts.add(Float.toString(i * 0.5f));
        }
        String document = "{\"times\":[" + String.join(",", parts) + "]}";
        JsonObject root = JsonParser.parseObject(document, SOURCE);
        float[] values = root.requireArray("times").toFloatArray();
        assertEquals(count, values.length);
        assertEquals(0.0f, values[0]);
        assertEquals((count - 1) * 0.5f, values[count - 1]);
    }
}
